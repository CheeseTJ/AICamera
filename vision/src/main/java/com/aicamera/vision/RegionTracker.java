package com.aicamera.vision;

import com.aicamera.core.Mat3;
import com.aicamera.core.Quad;
import java.util.ArrayList;
import java.util.List;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/**
 * 原区域高频跟踪器（规格 §8）。
 *
 * <h3>漂移与鲁棒性的权衡</h3>
 * <p>「参考帧直连」不会累积漂移，但可跟踪的帧间位移受 LK 金字塔范围限制；
 * 「逐帧链式」能覆盖大位移，却会把每帧的估计误差累积成可见漂移。
 *
 * <p>本实现按规格 §8.5 采用<b>关键帧链</b>：
 * <ol>
 *   <li>维护 <b>基础关键帧</b>（锚点帧，永久不变）作为坐标系原点；</li>
 *   <li>维护 <b>滚动关键帧</b>：当基础关键帧的点在当帧匹配不足时，把当前帧升为滚动关键帧，
 *       并记录把它映射回基础关键帧的累计变换 {@code keyframeToBase}；</li>
 *   <li>每帧优先用<b>基础关键帧直连</b>（零漂移）；直连不足时用<b>滚动关键帧直连</b>
 *       （位移小、可靠），再把结果经 {@code keyframeToBase} 复合回基础坐标系；</li>
 *   <li>累计变换只在升关键帧时更新一次，且滚动关键帧会被定期丢弃重选，
 *       因此漂移不会无限累积。</li>
 * </ol>
 *
 * <p>内部几何量全部是分析图<b>像素</b>坐标；对外暴露前换算为归一化坐标。
 * 该类不做线程调度，由调用方保证串行访问。
 */
public final class RegionTracker {

    /** 对外输出的跟踪结果。 */
    public static final class Result {
        public final Quad quadNormalized;
        public final double quality;
        public final int validPoints;
        public final double inlierRatio;
        public final double medianReprojectionError;
        public final String reasonCode;
        /** 当前是否依赖滚动关键帧（依赖越深越容易漂移）。 */
        public final boolean viaRollingKeyframe;

        Result(Quad quadNormalized, double quality, int validPoints, double inlierRatio,
            double medianReprojectionError, String reasonCode, boolean viaRollingKeyframe) {
            this.quadNormalized = quadNormalized;
            this.quality = quality;
            this.validPoints = validPoints;
            this.inlierRatio = inlierRatio;
            this.medianReprojectionError = medianReprojectionError;
            this.reasonCode = reasonCode;
            this.viaRollingKeyframe = viaRollingKeyframe;
        }

        public boolean hasPosition() {
            return quadNormalized != null && quadNormalized.isFinite();
        }
    }

    /** 一个关键帧：灰度图 + 位于该帧自身坐标系下的角点 + 该帧坐标 → 基础坐标。 */
    private static final class Keyframe {
        final Mat gray;
        /** 角点在**该关键帧自身**坐标系下的位置。 */
        MatOfPoint2f points;
        /** 该关键帧坐标 → 基础关键帧坐标。 */
        final Mat3 toBase;

        Keyframe(Mat gray, MatOfPoint2f points, Mat3 toBase) {
            this.gray = gray;
            this.points = points;
            this.toBase = toBase;
        }

        void release() {
            if (gray != null) {
                gray.release();
            }
            if (points != null) {
                points.release();
            }
        }
    }

    private final TrackerConfig config;

    /** 基础关键帧（锚点帧），永不替换。 */
    private Mat baseGray;
    private MatOfPoint2f basePoints;
    private double[] baseQuadPx;

    /** 滚动关键帧，可为 null。 */
    private Keyframe rolling;

    private int frameWidth = 1;
    private int frameHeight = 1;
    private int consecutiveFailures;

    private double referenceZoom = 1.0;
    private double lastZoom = 1.0;

    private int lastInlierCount;
    private double lastMedianError = Double.MAX_VALUE;

    public RegionTracker(TrackerConfig config) {
        this.config = config == null ? new TrackerConfig() : config;
    }

    public TrackerConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 生命周期

    public boolean initialize(Mat referenceGray, Quad quadNormalized) {
        return initialize(referenceGray, quadNormalized, 1.0);
    }

    /**
     * 用参考帧初始化。
     *
     * @param zoomRatio 锚点时刻的实际变焦倍率，用于变焦期间的光流初值预测
     * @return false 表示该区域纹理不足，无法建立跟踪
     */
    public boolean initialize(Mat referenceGray, Quad quadNormalized, double zoomRatio) {
        release();
        if (referenceGray == null || referenceGray.empty() || quadNormalized == null) {
            return false;
        }
        frameWidth = referenceGray.cols();
        frameHeight = referenceGray.rows();
        baseQuadPx = denormalize(quadNormalized, frameWidth, frameHeight);
        if (baseQuadPx == null) {
            return false;
        }

        baseGray = new Mat();
        referenceGray.convertTo(baseGray, CvType.CV_8UC1);
        basePoints = detectFeatures(baseGray, baseQuadPx);
        if (basePoints.rows() < config.minTrackedPoints) {
            release();
            return false;
        }

        referenceZoom = zoomRatio > 1e-6 ? zoomRatio : 1.0;
        lastZoom = referenceZoom;
        consecutiveFailures = 0;
        return true;
    }

    public boolean isInitialized() {
        return baseGray != null && !baseGray.empty()
            && basePoints != null && basePoints.rows() > 0
            && baseQuadPx != null;
    }

    /** 锚点帧坐标系下的目标四边形（永远不变）。 */
    public Quad referenceQuad() {
        return baseQuadPx == null ? null : normalize(baseQuadPx, frameWidth, frameHeight);
    }

    public void release() {
        releaseMat(baseGray);
        baseGray = null;
        releasePoints(basePoints);
        basePoints = null;
        baseQuadPx = null;
        if (rolling != null) {
            rolling.release();
            rolling = null;
        }
        consecutiveFailures = 0;
        lastZoom = referenceZoom;
    }

    /**
     * 变焦完成后重建参考基准（规格 §10.4）。
     *
     * <p>变焦会把参考帧的角点大量推出画面，导致后续每帧都匹配不足。
     * 由于变焦后目标区域已经铺满画面且跟踪位置可信，此时把当前帧升格为
     * <b>新的基础关键帧</b>是正确做法：
     * <ul>
     *   <li>原锚点语义由调用方以"当前可信位置"延续，目标身份不变；</li>
     *   <li>新参考帧的角点密度恢复到满值，避免变焦后持续丢跟踪。</li>
     * </ul>
     *
     * @param currentGray   当前帧灰度
     * @param quadNormalized 当前帧中目标四边形（归一化坐标，即变焦后的可信位置）
     * @param zoomRatio     当前实际倍率
     * @return false 表示新区域纹理不足，保留原基准
     */
    public boolean rebaseReference(Mat currentGray, Quad quadNormalized, double zoomRatio) {
        if (currentGray == null || currentGray.empty() || quadNormalized == null) {
            return false;
        }
        if (currentGray.cols() != frameWidth || currentGray.rows() != frameHeight) {
            return false;
        }
        Mat candidateGray = new Mat();
        try {
            currentGray.convertTo(candidateGray, CvType.CV_8UC1);
            double[] candidateQuad = denormalize(quadNormalized, frameWidth, frameHeight);
            if (candidateQuad == null) {
                return false;
            }
            MatOfPoint2f candidatePoints = detectFeatures(candidateGray, candidateQuad);
            if (candidatePoints.rows() < config.minTrackedPoints) {
                candidatePoints.release();
                return false;
            }

            releaseMat(baseGray);
            releasePoints(basePoints);
            baseGray = candidateGray.clone();
            basePoints = candidatePoints;
            baseQuadPx = candidateQuad;
            if (rolling != null) {
                rolling.release();
                rolling = null;
            }
            referenceZoom = zoomRatio > 1e-6 ? zoomRatio : referenceZoom;
            lastZoom = referenceZoom;
            consecutiveFailures = 0;
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            candidateGray.release();
        }
    }

    // ------------------------------------------------------------------ 跟踪

    /** 不带倍率信息的跟踪。 */
    public Result track(Mat currentGray) {
        return trackInternal(currentGray, 1.0);
    }

    /**
     * 跟踪一帧。
     *
     * @param zoomRatio 当前帧的实际变焦倍率（取自帧元数据，不是请求值）
     */
    public Result track(Mat currentGray, double zoomRatio) {
        double gain = 1.0;
        if (zoomRatio > 1e-6 && lastZoom > 1e-6) {
            gain = zoomRatio / lastZoom;
        }
        if (zoomRatio > 1e-6) {
            lastZoom = zoomRatio;
        }
        return trackInternal(currentGray, gain);
    }

    private Result trackInternal(Mat currentGray, double zoomGain) {
        if (!isInitialized() || currentGray == null || currentGray.empty()) {
            return failed("TRACKER_NOT_INITIALIZED");
        }
        if (currentGray.cols() != frameWidth || currentGray.rows() != frameHeight) {
            return failed("FRAME_SIZE_CHANGED");
        }

        Mat gray = new Mat();
        boolean success = false;
        try {
            currentGray.convertTo(gray, CvType.CV_8UC1);

            // 1) 基础关键帧直连：零漂移，优先使用
            Match direct = match(baseGray, gray, basePoints, zoomGain);
            if (direct != null && direct.count >= config.referenceDirectMinPoints) {
                Mat3 baseToCurrent = estimateTransform(direct.src, direct.dst);
                if (baseToCurrent != null) {
                    Result r = buildResult(baseToCurrent, direct, false);
                    if (r.hasPosition()) {
                        success = true;
                        // 基础帧可用点数偏少时补充特征，避免后续帧衰减到不可用
                        if (direct.count < config.maxCorners / 2) {
                            replenishBasePoints(gray, baseToCurrent, direct.dst.size());
                        }
                        // 直连良好时丢弃滚动关键帧，避免残留误差
                        if (rolling != null) {
                            rolling.release();
                            rolling = null;
                        }
                        return r;
                    }
                }
            }

            // 2) 滚动关键帧直连：位移小、可靠，再把结果复合回基础坐标系
            if (rolling != null) {
                Match viaRolling = match(rolling.gray, gray, rolling.points, zoomGain);
                if (viaRolling != null && viaRolling.count >= config.minTrackedPoints) {
                    // 滚动关键帧 → 当前帧
                    Mat3 rollingToCurrent = estimateTransform(viaRolling.src, viaRolling.dst);
                    if (rollingToCurrent != null) {
                        // 需要 基础 → 当前 = (滚动 → 当前) ∘ (基础 → 滚动)
                        Mat3 baseToRolling = rolling.toBase.invert();
                        if (baseToRolling != null) {
                            Mat3 baseToCurrent = rollingToCurrent.multiply(baseToRolling);
                            Result r = buildResult(baseToCurrent, viaRolling, true);
                            if (r.hasPosition()) {
                                success = true;
                                // 滚动关键帧可用点数偏低时，直接用当前帧重建关键帧：
                                // 它的位移与当前帧几乎为零，重新检测的点数量充足且质量最好。
                                if (viaRolling.count < config.rollingRefreshMinPoints) {
                                    promoteRollingKeyframe(gray, baseToCurrent);
                                }
                                return r;
                            }
                        }
                    }
                }
            }

            // 3) 直连与滚动都不可用：用当前帧升级为滚动关键帧。
            //    只要直连还能给出可用几何（哪怕点数不足 45），就先定位再升关键帧，
            //    这样关键帧之间的位移始终较小。
            if (direct != null && direct.count >= config.minTrackedPoints) {
                Mat3 baseToCurrent = estimateTransform(direct.src, direct.dst);
                if (baseToCurrent != null) {
                    Result r = buildResult(baseToCurrent, direct, false);
                    if (r.hasPosition()) {
                        promoteRollingKeyframe(gray, baseToCurrent);
                        success = true;
                        return r;
                    }
                }
            } else if (rolling == null && direct == null) {
                // 首次跟踪就完全失败，保持参考状态并累计失败
                consecutiveFailures++;
                return failed("INSUFFICIENT_FEATURES");
            }

            // 4) 完全失败
            consecutiveFailures++;
            return failed("INSUFFICIENT_FEATURES");
        } catch (Throwable t) {
            consecutiveFailures++;
            return failed("TRACKING_EXCEPTION");
        } finally {
            if (!success) {
                gray.release();
            } else {
                gray.release();
            }
        }
    }

    /**
     * 给基础关键帧补点：在当前帧检测新角点后，反投影回基础帧坐标系再合并。
     *
     * <p>这是长时间跟踪的关键——目标逐渐出画会让点数单调衰减，
     * 不补点最终必然丢跟踪。
     */
    private void replenishBasePoints(Mat currentGray, Mat3 baseToCurrent, int currentCount) {
        if (basePoints == null) {
            return;
        }
        Mat3 currentToBase = baseToCurrent.invert();
        if (currentToBase == null) {
            return;
        }
        double[] currentQuadPx = applyTransform(baseToCurrent, baseQuadPx);
        if (currentQuadPx == null) {
            return;
        }
        MatOfPoint2f fresh = detectFeatures(currentGray, currentQuadPx);
        if (fresh.rows() == 0) {
            fresh.release();
            return;
        }
        Point[] candidates = fresh.toArray();
        fresh.release();

        Point[] existing = basePoints.toArray();
        List<Point> merged = new ArrayList<>(existing.length + candidates.length);
        for (Point p : existing) {
            merged.add(p);
        }
        mergeDistinct(merged, candidates, currentToBase, true);
        if (merged.size() <= currentCount) {
            return;
        }
        basePoints.release();
        basePoints = new MatOfPoint2f(merged.toArray(new Point[0]));
    }

    /** 给滚动关键帧补点（在其自身坐标系下合并）。 */
    private void replenishRollingPoints(Mat currentGray, Mat3 rollingToCurrent, int currentCount) {
        if (rolling == null) {
            return;
        }
        Mat3 currentToRolling = rollingToCurrent.invert();
        if (currentToRolling == null) {
            return;
        }
        double[] rollingQuadPx = applyTransform(rollingToCurrent, baseQuadPx);
        if (rollingQuadPx == null) {
            return;
        }
        MatOfPoint2f fresh = detectFeatures(currentGray, rollingQuadPx);
        if (fresh.rows() == 0) {
            fresh.release();
            return;
        }
        Point[] candidates = fresh.toArray();
        fresh.release();

        Point[] existing = rolling.points.toArray();
        List<Point> merged = new ArrayList<>(existing.length + candidates.length);
        for (Point p : existing) {
            merged.add(p);
        }
        mergeDistinct(merged, candidates, currentToRolling, true);
        if (merged.size() <= currentCount) {
            return;
        }
        rolling.points.release();
        rolling.points = new MatOfPoint2f(merged.toArray(new Point[0]));
    }

    /** 把候选点反投影后合并进 merged，按最小距离去重。 */
    private void mergeDistinct(List<Point> merged, Point[] candidates, Mat3 currentToTarget,
        boolean requireInsideFrame) {
        double minDistSq = config.minDistance * config.minDistance * 0.25;
        for (Point c : candidates) {
            double[] back = currentToTarget.apply(c.x, c.y);
            if (!Double.isFinite(back[0]) || !Double.isFinite(back[1])) {
                continue;
            }
            if (requireInsideFrame && !inside(back[0], back[1], frameWidth, frameHeight)) {
                continue;
            }
            boolean dup = false;
            for (Point e : merged) {
                double dx = e.x - back[0];
                double dy = e.y - back[1];
                if (dx * dx + dy * dy < minDistSq) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                merged.add(new Point(back[0], back[1]));
            }
            if (merged.size() >= config.maxCorners) {
                return;
            }
        }
    }

    /** 用当前帧建立/替换滚动关键帧，角点记录在该帧自身坐标系下。 */
    private void promoteRollingKeyframe(Mat currentGray, Mat3 baseToCurrent) {
        Mat3 currentToBase = baseToCurrent.invert();
        if (currentToBase == null) {
            return;
        }
        double[] currentQuadPx = applyTransform(baseToCurrent, baseQuadPx);
        if (currentQuadPx == null) {
            return;
        }
        MatOfPoint2f detected = detectFeatures(currentGray, currentQuadPx);
        if (detected.rows() < config.minTrackedPoints) {
            detected.release();
            return;
        }
        if (rolling != null) {
            rolling.release();
        }
        // 角点保留在当前帧坐标系；toBase 记录当前坐标 → 基础坐标
        rolling = new Keyframe(currentGray.clone(), detected, currentToBase);
        consecutiveFailures = 0;
    }

    /** 由变换与匹配结果构造输出。 */
    private Result buildResult(Mat3 sourceToCurrent, Match match, boolean viaRolling) {
        double[] quadPx = applyTransform(sourceToCurrent, baseQuadPx);
        if (quadPx == null) {
            consecutiveFailures++;
            return failed("GEOMETRY_ESTIMATION_FAILED");
        }
        double inlierRatio = lastInlierCount > 0
            ? (double) lastInlierCount / Math.max(1, match.count) : 1.0;
        double spread = spatialSpread(match.dst);
        double quality = qualityScore(match.count, inlierRatio, lastMedianError, spread);
        String reason = qualityReason(inlierRatio, lastMedianError, spread);

        double effectiveQuality = reason == null
            ? quality : Math.min(quality, config.degradedQualityCeiling);
        consecutiveFailures = reason == null ? 0 : consecutiveFailures + 1;
        return new Result(normalize(quadPx, frameWidth, frameHeight), effectiveQuality,
            match.count, inlierRatio, lastMedianError, reason, viaRolling);
    }

    private String qualityReason(double inlierRatio, double medianErr, double spread) {
        if (inlierRatio < config.minInlierRatio) {
            return "LOW_INLIER_RATIO";
        }
        if (medianErr > config.maxMedianReprojectionError) {
            return "HIGH_REPROJECTION_ERROR";
        }
        if (spread < config.minSpatialSpread) {
            return "POOR_SPATIAL_DISTRIBUTION";
        }
        return null;
    }

    // ------------------------------------------------------------------ 匹配

    /** 匹配结果。 */
    private static final class Match {
        final List<Point> src;
        final List<Point> dst;
        final int count;

        Match(List<Point> src, List<Point> dst) {
            this.src = src;
            this.dst = dst;
            this.count = Math.min(src.size(), dst.size());
        }
    }

    /**
     * 双向 KLT + 前后向一致性过滤。
     *
     * @param fromPoints 源帧坐标（若启用变焦预测则内部先扩张作为初值）
     * @param zoomGain   相邻帧倍率增益；1 表示无变焦
     */
    private Match match(Mat fromGray, Mat toGray, MatOfPoint2f fromPoints, double zoomGain) {
        if (fromPoints == null || fromPoints.rows() == 0) {
            return null;
        }
        MatOfPoint2f init = ZoomPredictor.predict(fromPoints,
            frameWidth / 2.0, frameHeight / 2.0, zoomGain);
        MatOfPoint2f forward = new MatOfPoint2f();
        MatOfPoint2f backward = new MatOfPoint2f();
        MatOfByte status = new MatOfByte();
        MatOfFloat err = new MatOfFloat();
        try {
            MatOfPoint2f actualInit = init != null ? init : fromPoints;
            Video.calcOpticalFlowPyrLK(fromGray, toGray, actualInit, forward, status, err,
                new Size(config.lkWinSize, config.lkWinSize), config.lkMaxLevel,
                new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS,
                    config.lkMaxIterations, config.lkEpsilon),
                Video.OPTFLOW_LK_GET_MIN_EIGENVALS, 1e-4);

            Video.calcOpticalFlowPyrLK(toGray, fromGray, forward, backward,
                new MatOfByte(), new MatOfFloat(),
                new Size(config.lkWinSize, config.lkWinSize), config.lkMaxLevel,
                new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS,
                    config.lkMaxIterations, config.lkEpsilon),
                Video.OPTFLOW_LK_GET_MIN_EIGENVALS, 1e-4);

            Point[] src = fromPoints.toArray();
            Point[] basis = actualInit.toArray();
            Point[] fwd = forward.toArray();
            Point[] back = backward.toArray();
            byte[] st = status.toArray();
            boolean predicted = init != null;
            int n = Math.min(Math.min(src.length, fwd.length),
                Math.min(back.length, Math.min(st.length, basis.length)));

            List<Point> outSrc = new ArrayList<>(n);
            List<Point> outDst = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (st[i] == 0) {
                    continue;
                }
                // 前后向一致性以正向光流的实际出发点为基准
                Point check = predicted ? basis[i] : src[i];
                double fb = Math.hypot(check.x - back[i].x, check.y - back[i].y);
                if (!Double.isFinite(fb) || fb > config.fbErrorThreshold) {
                    continue;
                }
                if (!inside(fwd[i].x, fwd[i].y, frameWidth, frameHeight)) {
                    continue;
                }
                outSrc.add(src[i]);
                outDst.add(fwd[i]);
            }
            return new Match(outSrc, outDst);
        } catch (Throwable t) {
            return null;
        } finally {
            if (init != null) {
                init.release();
            }
            forward.release();
            backward.release();
            status.release();
            err.release();
        }
    }

    /** RANSAC 相似变换估计；点不足或退化时返回 null。 */
    private Mat3 estimateTransform(List<Point> from, List<Point> to) {
        if (from.size() < 3 || to.size() < 3) {
            return null;
        }
        MatOfPoint2f src = new MatOfPoint2f(from.toArray(new Point[0]));
        MatOfPoint2f dst = new MatOfPoint2f(to.toArray(new Point[0]));
        Mat inliers = new Mat();
        try {
            Mat affine = Calib3d.estimateAffinePartial2D(src, dst, inliers,
                Calib3d.RANSAC, config.ransacReprojectionThreshold, 2000, 0.995, 10);
            if (affine == null || affine.empty()) {
                return null;
            }
            double[] a = new double[6];
            affine.get(0, 0, a);
            affine.release();

            byte[] mask = readMask(inliers);
            if (mask == null) {
                return null;
            }
            int inlierCount = 0;
            List<Double> errors = new ArrayList<>();
            for (int i = 0; i < from.size() && i < mask.length; i++) {
                if (mask[i] == 0) {
                    continue;
                }
                inlierCount++;
                Point p = from.get(i);
                Point q = to.get(i);
                double px = a[0] * p.x + a[1] * p.y + a[2];
                double py = a[3] * p.x + a[4] * p.y + a[5];
                errors.add(Math.hypot(px - q.x, py - q.y));
            }
            lastInlierCount = inlierCount;
            if (errors.isEmpty()) {
                lastMedianError = Double.MAX_VALUE;
            } else {
                errors.sort(Double::compareTo);
                lastMedianError = errors.get(errors.size() / 2);
            }

            Mat3 m = Mat3.of(a[0], a[1], a[2], a[3], a[4], a[5], 0, 0, 1);
            return isReasonable(m) ? m : null;
        } finally {
            src.release();
            dst.release();
            inliers.release();
        }
    }

    private static byte[] readMask(Mat inliers) {
        if (inliers == null || inliers.empty()) {
            return null;
        }
        byte[] mask = new byte[(int) inliers.total()];
        try {
            inliers.get(0, 0, mask);
            return mask;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isReasonable(Mat3 m) {
        if (!(m.determinant() > 0)) {
            return false;
        }
        double sx = Math.hypot(m.get(0, 0), m.get(1, 0));
        double sy = Math.hypot(m.get(0, 1), m.get(1, 1));
        if (sx < 0.5 || sx > 4.0 || sy < 0.5 || sy > 4.0) {
            return false;
        }
        double ratio = sx / Math.max(1e-9, sy);
        return ratio >= 0.6 && ratio <= 1.6;
    }

    // ------------------------------------------------------------------ 特征

    private MatOfPoint2f detectFeatures(Mat gray, double[] quadPx) {
        if (!hasSufficientTexture(gray, quadPx)) {
            return new MatOfPoint2f();
        }
        int x0 = clamp((int) Math.floor(minOf(0, 2, 4, 6, quadPx)), 0, gray.cols() - 1);
        int x1 = clamp((int) Math.ceil(maxOf(0, 2, 4, 6, quadPx)), 0, gray.cols() - 1);
        int y0 = clamp((int) Math.floor(minOf(1, 3, 5, 7, quadPx)), 0, gray.rows() - 1);
        int y1 = clamp((int) Math.ceil(maxOf(1, 3, 5, 7, quadPx)), 0, gray.rows() - 1);

        int padX = Math.max(4, (x1 - x0) * 15 / 100);
        int padY = Math.max(4, (y1 - y0) * 15 / 100);
        x0 = clamp(x0 - padX, 0, gray.cols() - 1);
        x1 = clamp(x1 + padX, 0, gray.cols() - 1);
        y0 = clamp(y0 - padY, 0, gray.rows() - 1);
        y1 = clamp(y1 + padY, 0, gray.rows() - 1);
        if (x1 - x0 < 8 || y1 - y0 < 8) {
            return new MatOfPoint2f();
        }

        Mat mask = Mat.zeros(gray.size(), CvType.CV_8UC1);
        Imgproc.rectangle(mask, new Point(x0, y0), new Point(x1, y1), new Scalar(255), -1);
        MatOfPoint corners = new MatOfPoint();
        try {
            Imgproc.goodFeaturesToTrack(gray, corners, config.maxCorners, config.qualityLevel,
                config.minDistance, mask, config.blockSize, false, 0.04);
            return new MatOfPoint2f(corners.toArray());
        } finally {
            mask.release();
            corners.release();
        }
    }

    /** 纹理门控：暗光/纯色画面下 KLT 会稳定地跟踪噪声，必须显式拒绝。 */
    private boolean hasSufficientTexture(Mat gray, double[] quadPx) {
        int x0 = clamp((int) Math.floor(minOf(0, 2, 4, 6, quadPx)), 0, gray.cols() - 1);
        int x1 = clamp((int) Math.ceil(maxOf(0, 2, 4, 6, quadPx)), 0, gray.cols() - 1);
        int y0 = clamp((int) Math.floor(minOf(1, 3, 5, 7, quadPx)), 0, gray.rows() - 1);
        int y1 = clamp((int) Math.ceil(maxOf(1, 3, 5, 7, quadPx)), 0, gray.rows() - 1);
        if (x1 - x0 < 8 || y1 - y0 < 8) {
            return false;
        }
        Mat roi = gray.submat(y0, y1, x0, x1);
        Mat gx = new Mat();
        Mat gy = new Mat();
        Mat absGx = new Mat();
        Mat absGy = new Mat();
        try {
            MatOfDouble stdDev = new MatOfDouble();
            MatOfDouble mean = new MatOfDouble();
            Core.meanStdDev(roi, mean, stdDev);
            double[] sd = stdDev.toArray();
            stdDev.release();
            mean.release();
            if (sd.length == 0 || sd[0] < config.minGrayStdDev) {
                return false;
            }
            Imgproc.Sobel(roi, gx, CvType.CV_32F, 1, 0);
            Imgproc.Sobel(roi, gy, CvType.CV_32F, 0, 1);
            Core.absdiff(gx, Mat.zeros(gx.size(), gx.type()), absGx);
            Core.absdiff(gy, Mat.zeros(gy.size(), gy.type()), absGy);
            double energy = (Core.mean(absGx).val[0] + Core.mean(absGy).val[0]) / 2.0;
            return energy >= config.minTextureEnergy;
        } finally {
            gx.release();
            gy.release();
            absGx.release();
            absGy.release();
            roi.release();
        }
    }

    // ------------------------------------------------------------------ 工具

    private double spatialSpread(List<Point> pts) {
        if (pts.size() < 2) {
            return 0.0;
        }
        double mx = 0;
        double my = 0;
        for (Point p : pts) {
            mx += p.x;
            my += p.y;
        }
        mx /= pts.size();
        my /= pts.size();
        double vx = 0;
        double vy = 0;
        for (Point p : pts) {
            vx += (p.x - mx) * (p.x - mx);
            vy += (p.y - my) * (p.y - my);
        }
        double normW = frameWidth > 0 ? frameWidth : 1;
        double normH = frameHeight > 0 ? frameHeight : 1;
        return Math.min(Math.sqrt(vx / pts.size()) / normW, Math.sqrt(vy / pts.size()) / normH);
    }

    private double qualityScore(int validPoints, double inlierRatio, double medianErr, double spread) {
        double pointScore = Math.min(1.0, validPoints / (double) (config.minTrackedPoints * 2));
        double inlierScore = Math.min(1.0, Math.max(0.0, (inlierRatio - 0.3) / 0.7));
        double errorScore = Math.max(0.0, 1.0 - medianErr / (config.maxMedianReprojectionError * 2));
        double spreadScore = Math.min(1.0, spread / (config.minSpatialSpread * 3));
        double q = 0.35 * pointScore + 0.30 * inlierScore + 0.20 * errorScore + 0.15 * spreadScore;
        return Math.max(config.minQuality, Math.min(config.maxQuality, q));
    }

    private Result failed(String reasonCode) {
        return new Result(null, 0.0, 0, 0.0, Double.MAX_VALUE, reasonCode, false);
    }

    private static boolean inside(double x, double y, int w, int h) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double minOf(int a, int b, int c, int d, double[] v) {
        return Math.min(Math.min(v[a], v[b]), Math.min(v[c], v[d]));
    }

    private static double maxOf(int a, int b, int c, int d, double[] v) {
        return Math.max(Math.max(v[a], v[b]), Math.max(v[c], v[d]));
    }

    private static void releaseMat(Mat m) {
        if (m != null) {
            m.release();
        }
    }

    private static void releasePoints(MatOfPoint2f p) {
        if (p != null) {
            p.release();
        }
    }

    static double[] denormalize(Quad q, int w, int h) {
        if (q == null) {
            return null;
        }
        double[] src = q.toArray();
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = src[i * 2] * w;
            out[i * 2 + 1] = src[i * 2 + 1] * h;
        }
        return out;
    }

    static Quad normalize(double[] px, int w, int h) {
        if (px == null || w <= 0 || h <= 0) {
            return null;
        }
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = px[i * 2] / w;
            out[i * 2 + 1] = px[i * 2 + 1] / h;
        }
        return Quad.of(out);
    }

    private static double[] applyTransform(Mat3 m, double[] quad) {
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            double[] r = m.apply(quad[i * 2], quad[i * 2 + 1]);
            if (!Double.isFinite(r[0]) || !Double.isFinite(r[1])) {
                return null;
            }
            out[i * 2] = r[0];
            out[i * 2 + 1] = r[1];
        }
        return out;
    }
}
