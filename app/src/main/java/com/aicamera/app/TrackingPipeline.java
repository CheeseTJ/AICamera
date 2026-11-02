package com.aicamera.app;

import android.util.Log;
import com.aicamera.core.CompositionAnchor;
import com.aicamera.core.GuidanceController;
import com.aicamera.core.GuidanceResult;
import com.aicamera.core.GuidanceState;
import com.aicamera.core.Quad;
import com.aicamera.core.TrackingUpdate;
import com.aicamera.vision.OpenCvLoader;
import com.aicamera.vision.RegionTracker;
import com.aicamera.vision.StabilityMonitor;
import com.aicamera.vision.TrackerConfig;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opencv.core.Mat;

/**
 * 跟踪流水线：帧预处理 → 稳定度检测 → 锚点锁定 → 高频跟踪 → 驱动状态机。
 *
 * <p>运行在单线程 executor 上，保证 OpenCV Mat 串行访问；只处理最新帧，避免延迟堆积。
 *
 * <p>阶段一交互：应用先进入"等待稳定"，稳定后由用户点击按钮锁定参考帧，
 * 不再自动锁定第一帧（首帧往往处于对焦/曝光调整中，纹理不稳定）。
 */
public final class TrackingPipeline implements AutoCloseable {

    private static final String TAG = "TrackingPipeline";

    /** 流水线状态。 */
    public enum Stage {
        /** 尚未绑定相机。 */
        IDLE,
        /** 正在评估画面稳定性，等待用户点击锁定。 */
        WAITING_STABLE,
        /** 已发起锁定请求。 */
        LOCKING,
        /** 已锁定锚点，正在跟踪。 */
        TRACKING
    }

    public interface Callback {
        /** 稳定度或状态变化（主线程）。 */
        void onStability(boolean stable, double progress, Stage stage);

        /** 跟踪结果（主线程）。 */
        void onGuidance(GuidanceResult result);

        /** 锁定失败，附带原因码（主线程）。 */
        void onLockFailed(String reasonCode);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aicamera-tracker");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });

    private final GuidanceController controller;
    private final RegionTracker tracker = new RegionTracker(new TrackerConfig());
    private final StabilityMonitor stability = new StabilityMonitor(new StabilityMonitor.Config());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Callback callback;

    private final Mat grayMat = new Mat(FrameNormalizer.ANALYSIS_HEIGHT,
        FrameNormalizer.ANALYSIS_WIDTH, org.opencv.core.CvType.CV_8UC1);
    private final Mat prevFrame = new Mat();
    /**
     * 阶段一固定测试区域：略偏右，宽高各占画面 60%（对应目标倍率约 1.67×），
     * 用于验证「引导用户把目标移到画面中心 → 稳定 → 自动变焦」的完整链路
     * （规格 §10.1 / §10.2）。
     */
    private final Quad anchorRect = Quad.fromRect(0.22, 0.20, 0.82, 0.80);

    private volatile long frameId;
    private volatile Stage stage = Stage.IDLE;
    private final AtomicBoolean lockPending = new AtomicBoolean(false);
    /** 当前会话内的锚点代数，用于构造新锚点。 */
    private volatile int currentGeneration;
    /** 本次锚点是否已在变焦后重建参考基准。 */
    private volatile boolean referenceRebased;

    private double lastMeanLuma = Double.NaN;

    public TrackingPipeline(GuidanceController controller, Callback callback) {
        this.controller = controller;
        this.callback = callback;
    }

    public Stage stage() {
        return stage;
    }

    public boolean isReady() {
        return OpenCvLoader.ensureLoaded();
    }

    private void setStage(Stage next) {
        if (stage == next) {
            return;
        }
        stage = next;
        Log.i(TAG, "stage -> " + next);
        // 状态变化时主动通知 UI，避免按钮文案停留在上一阶段
        callback.onStability(stability.isStable(), stability.progress(), next);
    }

    /**
     * 提交一帧。
     *
     * <p>根据当前阶段决定做稳定度检测还是跟踪；未锁定时不会开始跟踪。
     *
     * @param gray 已缩放到 640x480 的灰度图
     */
    public void submit(Mat gray, long timestampNs, double zoomRatio) {
        if (!busy.compareAndSet(false, true)) {
            gray.release();
            return;
        }
        final long id = ++frameId;
        executor.execute(() -> {
            try {
                gray.copyTo(grayMat);
                gray.release();

                Stage current = stage;
                if (current == Stage.IDLE) {
                    stage = Stage.WAITING_STABLE;
                    current = stage;
                }

                if (current == Stage.WAITING_STABLE) {
                    handleStability(id, timestampNs);
                } else if (current == Stage.TRACKING) {
                    handleTracking(id, timestampNs, zoomRatio);
                } else if (current == Stage.LOCKING) {
                    // 等锁定任务完成，期间只维持稳定度统计
                    handleStability(id, timestampNs);
                }
            } catch (Throwable t) {
                Log.e(TAG, "submit failed", t);
            } finally {
                busy.set(false);
            }
        });
    }

    /** 用户点击"锁定构图"：要求当前已稳定。 */
    public boolean requestLock(long timestampNs, double zoomRatio) {
        if (stage != Stage.WAITING_STABLE) {
            Log.w(TAG, "lock request ignored, stage=" + stage);
            return false;
        }
        if (!stability.isStable()) {
            Log.w(TAG, "lock refused: not stable yet (" + stability.stableFrames() + " frames)");
            return false;
        }
        if (!lockPending.compareAndSet(false, true)) {
            return false;
        }
        setStage(Stage.LOCKING);
        executor.execute(() -> {
            // 注意：必须在本线程（跟踪线程）内克隆参考帧，保证与 tracker 状态一致
            final Mat reference = grayMat.clone();
            try {
                if (!tracker.initialize(reference, anchorRect, zoomRatio)) {
                    Log.w(TAG, "initialize failed: insufficient texture in target area");
                    lockPending.set(false);
                    setStage(Stage.WAITING_STABLE);
                    stability.reset();
                    callback.onLockFailed("INSUFFICIENT_FEATURES");
                    return;
                }
                // startSearching 会递增 generation，锚点必须使用递增后的值，
                // 否则后续所有跟踪结果都会因 generation 不匹配被丢弃。
                controller.startSearching();
                int generation = ++currentGeneration;
                String anchorId = "anchor-" + System.currentTimeMillis();
                CompositionAnchor anchor = new CompositionAnchor(
                    anchorId, generation, frameId, timestampNs, zoomRatio,
                    anchorRect.toArray(), 4.0 / 3.0);
                controller.lockAnchor(anchor);
                Log.i(TAG, "anchor locked, generation=" + anchor.generation);
                referenceRebased = false;
                setStage(Stage.TRACKING);
                callback.onLockFailed(null);
            } catch (Throwable t) {
                Log.e(TAG, "lock failed", t);
                lockPending.set(false);
                setStage(Stage.WAITING_STABLE);
                callback.onLockFailed("LOCK_FAILED");
            } finally {
                reference.release();
            }
        });
        return true;
    }

    /** 重新开始选择（用户再次点击"重新构图"）。 */
    public void resetForRelock() {
        executor.execute(() -> {
            tracker.release();
            stability.reset();
            prevFrame.release();
            lastMeanLuma = Double.NaN;
            lockPending.set(false);
            currentGeneration = 0;
            referenceRebased = false;
            controller.stop();
            setStage(Stage.WAITING_STABLE);
        });
    }

    public void close() {
        executor.execute(() -> {
            tracker.release();
            grayMat.release();
            prevFrame.release();
        });
        executor.shutdown();
    }

    // ------------------------------------------------------------------ 内部

    private void handleStability(long id, long timestampNs) {
        double meanLuma = grayMat.empty() ? 0 : org.opencv.core.Core.mean(grayMat).val[0];
        if (prevFrame.empty() || prevFrame.size().equals(grayMat.size()) == false) {
            grayMat.copyTo(prevFrame);
            lastMeanLuma = meanLuma;
            stability.reset();
            notifyStability();
            return;
        }

        double[] motion = estimateGlobalMotion(prevFrame, grayMat);
        grayMat.copyTo(prevFrame);
        double lumaDelta = Double.isNaN(lastMeanLuma) ? 0 : Math.abs(meanLuma - lastMeanLuma);
        lastMeanLuma = meanLuma;

        if (motion == null) {
            stability.reset();
            if (id % 15 == 1) {
                Log.i(TAG, "stability: motion estimation failed");
            }
        } else {
            boolean stable = stability.update(motion[0], motion[1], motion[2], lumaDelta);
            if (id % 15 == 1) {
                Log.i(TAG, String.format(Locale.US,
                    "stability: dx=%.2fpx scale=%.5f rot=%.3fdeg luma=%.2f -> stableFrames=%d stable=%s",
                    motion[0], motion[1], motion[2], lumaDelta, stability.stableFrames(), stable));
            }
        }
        notifyStability();
    }

    private void handleTracking(long id, long timestampNs, double zoomRatio) {
        RegionTracker.Result r = tracker.track(grayMat, zoomRatio);
        int generation = currentGeneration;

        TrackingUpdate update = r.hasPosition()
            ? new TrackingUpdate(id, timestampNs, generation, r.quadNormalized, r.quality, r.reasonCode)
            : TrackingUpdate.lost(id, timestampNs, generation, r.reasonCode);

        com.aicamera.core.FramePacket packet = new com.aicamera.core.FramePacket(
            controller.sessionId(), id, timestampNs,
            FrameNormalizer.ANALYSIS_WIDTH, FrameNormalizer.ANALYSIS_HEIGHT,
            0, zoomRatio, null);
        GuidanceResult result = controller.onTrackingUpdate(packet, update);

        // 自动变焦完成时重建参考基准：
        // 放大后参考帧角点大量出画，若不重建会持续丢跟踪（规格 §10.4）。
        if (result.autoZoomCompleted && !referenceRebased) {
            if (r.hasPosition() && tracker.rebaseReference(grayMat, r.quadNormalized, zoomRatio)) {
                referenceRebased = true;
                Log.i(TAG, String.format(Locale.US,
                    "reference rebased after zoom at %.2fx, pts=%d", zoomRatio, r.validPoints));
            }
        }

        if (id % 30 == 1) {
            Log.i(TAG, String.format(Locale.US,
                "frame=%d hasPos=%s q=%.2f pts=%d inl=%.2f err=%.2f reason=%s state=%s",
                id, r.hasPosition(), r.quality, r.validPoints, r.inlierRatio,
                r.medianReprojectionError, r.reasonCode, result.state));
        }
        callback.onGuidance(result);
    }

    private void notifyStability() {
        boolean stable = stability.isStable();
        double progress = stability.progress();
        Stage s = stage;
        callback.onStability(stable, progress, s);
    }

    /**
     * 用稀疏光流估计相邻帧的全局平移/缩放/旋转。
     *
     * @return {translationPx, |scaleDelta|, |rotationDeg|}；点不足返回 null
     */
    private double[] estimateGlobalMotion(Mat a, Mat b) {
        org.opencv.core.MatOfPoint corners = new org.opencv.core.MatOfPoint();
        org.opencv.imgproc.Imgproc.goodFeaturesToTrack(a, corners, 120, 0.01, 12.0);
        if (corners.rows() < 20) {
            corners.release();
            return null;
        }
        org.opencv.core.MatOfPoint2f p0 = new org.opencv.core.MatOfPoint2f(corners.toArray());
        corners.release();
        org.opencv.core.MatOfPoint2f p1 = new org.opencv.core.MatOfPoint2f();
        org.opencv.core.MatOfByte st = new org.opencv.core.MatOfByte();
        org.opencv.core.MatOfFloat err = new org.opencv.core.MatOfFloat();

        try {
            org.opencv.video.Video.calcOpticalFlowPyrLK(a, b, p0, p1, st, err,
                new org.opencv.core.Size(21, 21), 3,
                new org.opencv.core.TermCriteria(org.opencv.core.TermCriteria.COUNT, 20, 0.03));

            org.opencv.core.Point[] from = p0.toArray();
            org.opencv.core.Point[] to = p1.toArray();
            byte[] ok = st.toArray();
            java.util.List<org.opencv.core.Point> a0 = new java.util.ArrayList<>();
            java.util.List<org.opencv.core.Point> a1 = new java.util.ArrayList<>();
            int n = Math.min(Math.min(from.length, to.length), ok.length);
            for (int i = 0; i < n; i++) {
                if (ok[i] != 0 && Double.isFinite(to[i].x) && Double.isFinite(to[i].y)) {
                    a0.add(from[i]);
                    a1.add(to[i]);
                }
            }
            if (a1.size() < 20) {
                return null;
            }

            double sumDx = 0;
            double sumDy = 0;
            for (int i = 0; i < a1.size(); i++) {
                sumDx += a1.get(i).x - a0.get(i).x;
                sumDy += a1.get(i).y - a0.get(i).y;
            }
            double medDx = sumDx / a1.size();
            double medDy = sumDy / a1.size();
            double translation = Math.hypot(medDx, medDy);

            org.opencv.core.MatOfPoint2f src = new org.opencv.core.MatOfPoint2f(
                a0.toArray(new org.opencv.core.Point[0]));
            org.opencv.core.MatOfPoint2f dst = new org.opencv.core.MatOfPoint2f(
                a1.toArray(new org.opencv.core.Point[0]));
            org.opencv.core.Mat affine = org.opencv.calib3d.Calib3d.estimateAffinePartial2D(
                src, dst, new org.opencv.core.Mat(), org.opencv.calib3d.Calib3d.RANSAC, 2.0, 1000, 0.995, 10);
            double scaleDelta = 0;
            double rotationDeg = 0;
            if (affine != null && !affine.empty()) {
                double[] m = new double[6];
                affine.get(0, 0, m);
                double sx = Math.hypot(m[0], m[3]);
                double sy = Math.hypot(m[1], m[4]);
                scaleDelta = Math.max(Math.abs(sx - 1.0), Math.abs(sy - 1.0));
                rotationDeg = Math.abs(Math.toDegrees(Math.atan2(m[1], m[0])));
                affine.release();
            }
            src.release();
            dst.release();
            return new double[] {translation, scaleDelta, rotationDeg};
        } catch (Throwable t) {
            return null;
        } finally {
            p0.release();
            p1.release();
            st.release();
            err.release();
        }
    }
}
