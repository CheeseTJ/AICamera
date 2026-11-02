package com.aicamera.vision;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicamera.core.Quad;
import com.aicamera.core.ZoomMath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/** 阶段性验证：变焦期间跟踪（§10.4）与 ORB 重定位（§8.5）。 */
class ZoomTrackingTest {

    @BeforeAll
    static void load() {
        Assertions.assertTrue(OpenCvLoader.ensureLoaded());
    }

    private SyntheticSequence sequence;

    @BeforeEach
    void setUp() {
        sequence = new SyntheticSequence(773311L);
    }

    @AfterEach
    void tearDown() {
        sequence.release();
    }

    @Test
    @DisplayName("连续放大到 1.67x：跟踪不丢失，误差仍可控")
    void trackingDuringZoom() {
        Quad anchor = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        RegionTracker tracker = new RegionTracker(new TrackerConfig());
        Mat first = sequence.render(SyntheticSequence.identity());
        Assertions.assertTrue(tracker.initialize(first, anchor));
        first.release();

        double target = 1.0 / 0.6;
        double worst = 0;
        for (int i = 1; i <= 24; i++) {
            double u = ZoomMath.smoothStep(i / 24.0);
            double z = ZoomMath.interpolateLog(1.0, target, u);
            Mat f = sequence.render(SyntheticSequence.scale(320, 240, z));
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "zoom frame " + i + " (z=" + z + ") lost: " + r.reasonCode);

            double[] truth = new double[8];
            double[] src = anchor.toArray();
            for (int k = 0; k < 4; k++) {
                truth[k * 2] = 0.5 + (src[k * 2] - 0.5) * z;
                truth[k * 2 + 1] = 0.5 + (src[k * 2 + 1] - 0.5) * z;
            }
            Quad t = Quad.of(truth);
            double err = 0;
            for (int k = 0; k < 4; k++) {
                err = Math.max(err, Math.hypot(
                    (r.quadNormalized.x(k) - t.x(k)) * SyntheticSequence.WIDTH,
                    (r.quadNormalized.y(k) - t.y(k)) * SyntheticSequence.HEIGHT));
            }
            worst = Math.max(worst, err);
        }
        System.out.println("[trackingDuringZoom] worst error = " + worst + " px");
        assertTrue(worst < 4.0, "worst error = " + worst);
        tracker.release();
    }

    @Test
    @DisplayName("变焦期间径向扩张预测与实际光流方向一致")
    void expansionMatchesOpticalFlow() {
        // 逐帧小幅缩放（每帧 1.05x），模拟真实平滑变焦；单帧大跳变会让 LK 落入重复纹理
        Mat prev = sequence.render(SyntheticSequence.identity());
        double z = 1.05;
        Mat next = zoomImage(prev, z);
        Mat prevGray = new Mat();
        prev.convertTo(prevGray, CvType.CV_8UC1);

        MatOfPoint corners = new MatOfPoint();
        Imgproc.goodFeaturesToTrack(prevGray, corners, 300, 0.01, 8.0);
        MatOfPoint2f p0 = new MatOfPoint2f(corners.toArray());
        MatOfPoint2f p1 = new MatOfPoint2f();
        MatByteStatus st = new MatByteStatus();
        Video.calcOpticalFlowPyrLK(prevGray, next, p0, p1, st.status, new MatOfFloat(),
            new Size(21, 21), 3, new TermCriteria(TermCriteria.COUNT, 30, 0.01));

        Point[] a0 = p0.toArray();
        Point[] a1 = p1.toArray();
        byte[] stArr = st.status.toArray();
        java.util.List<Double> deltas = new java.util.ArrayList<>();
        int used = 0;
        for (int i = 0; i < Math.min(a0.length, Math.min(a1.length, stArr.length)); i++) {
            if (stArr[i] == 0) {
                continue;
            }
            if (a0[i].x < 30 || a0[i].x > SyntheticSequence.WIDTH - 30
                || a0[i].y < 30 || a0[i].y > SyntheticSequence.HEIGHT - 30) {
                continue;
            }
            double[] pred = ZoomMath.expandPoint(a0[i].x, a0[i].y, 320, 240, z);
            if (pred[0] < 5 || pred[0] > SyntheticSequence.WIDTH - 5
                || pred[1] < 5 || pred[1] > SyntheticSequence.HEIGHT - 5) {
                continue;
            }
            used++;
            deltas.add(Math.hypot(pred[0] - a1[i].x, pred[1] - a1[i].y));
        }
        java.util.Collections.sort(deltas);
        double mean = deltas.stream().mapToDouble(Double::doubleValue).average().orElse(-1);
        double median = deltas.isEmpty() ? -1 : deltas.get(deltas.size() / 2);
        double p95 = deltas.isEmpty() ? -1 : deltas.get(Math.min(deltas.size() - 1, (int) (deltas.size() * 0.95)));
        System.out.println("[expansionPrediction] used = " + used + ", mean = " + mean
            + " px, median = " + median + " px, p95 = " + p95 + " px");
        assertTrue(used > 60, "too few valid points: " + used);
        // 预测只用于光流初值与合理性检查，关心的是整体一致性而非个别离群点
        assertTrue(median < 0.5, "median delta = " + median);
        assertTrue(p95 < 2.0, "p95 delta = " + p95);

        prev.release();
        next.release();
        prevGray.release();
        corners.release();
        p0.release();
        p1.release();
    }

    /** 承载光流状态掩码的小包装，避免测试中出现多份临时变量。 */
    private static final class MatByteStatus {
        final MatOfByte status = new MatOfByte();
    }

    /** 以画面中心为原点的等比缩放，用于构造确定的变焦序列。 */
    private static Mat zoomImage(Mat src, double z) {
        Mat dst = new Mat();
        Mat m = new Mat(2, 3, CvType.CV_64F);
        m.put(0, 0, new double[] {z, 0, 320 - z * 320, 0, z, 240 - z * 240});
        Imgproc.warpAffine(src, dst, m, new Size(src.cols(), src.rows()),
            Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE);
        m.release();
        return dst;
    }

    @Test
    @DisplayName("ORB 重定位：旋转 12° + 平移后仍能找回参考区域")
    void orbRelocalization() {
        Quad anchor = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        Mat ref = sequence.render(SyntheticSequence.identity());
        Mat refGray = new Mat();
        ref.convertTo(refGray, CvType.CV_8UC1);

        OrbRelocalizer relocalizer = new OrbRelocalizer(refGray, null);
        System.out.println("[orb] reference keypoints = " + relocalizer.referenceKeypointCount());

        double[] m = SyntheticSequence.compose(
            SyntheticSequence.rotation(320, 240, Math.toRadians(12)),
            SyntheticSequence.translation(45, -28));
        Mat cur = sequence.render(m);
        Mat curGray = new Mat();
        cur.convertTo(curGray, CvType.CV_8UC1);

        OrbRelocalizer.Matches matches = relocalizer.match(curGray, 0.75);
        System.out.println("[orb] current keypoints = " + matches.currentKeypointCount
            + ", good matches = " + matches.size());
        assertTrue(matches.size() >= 40, "good matches too few: " + matches.size());

        MatOfPoint2f srcM = matches.srcMat();
        MatOfPoint2f dstM = matches.dstMat();
        Mat homography = Calib3d.findHomography(srcM, dstM, Calib3d.RANSAC, 3.0);
        assertTrue(homography != null && !homography.empty(), "homography failed");
        double[] h = new double[9];
        homography.get(0, 0, h);

        double[] truth = new double[8];
        double[] srcQ = anchor.toArray();
        for (int k = 0; k < 4; k++) {
            double[] p = SyntheticSequence.apply(m,
                srcQ[k * 2] * SyntheticSequence.WIDTH, srcQ[k * 2 + 1] * SyntheticSequence.HEIGHT);
            truth[k * 2] = p[0];
            truth[k * 2 + 1] = p[1];
        }
        double maxErr = 0;
        for (int k = 0; k < 4; k++) {
            double x = srcQ[k * 2] * SyntheticSequence.WIDTH;
            double y = srcQ[k * 2 + 1] * SyntheticSequence.HEIGHT;
            double w = h[6] * x + h[7] * y + h[8];
            double mx = (h[0] * x + h[1] * y + h[2]) / w;
            double my = (h[3] * x + h[4] * y + h[5]) / w;
            maxErr = Math.max(maxErr, Math.hypot(mx - truth[k * 2], my - truth[k * 2 + 1]));
        }
        System.out.println("[orb] relocalization max corner error = " + maxErr + " px");
        assertTrue(maxErr < 15.0, "relocalization error = " + maxErr);

        relocalizer.release();
        ref.release();
        refGray.release();
        cur.release();
        curGray.release();
        srcM.release();
        dstM.release();
        homography.release();
    }

    @Test
    @DisplayName("ORB 在完全无关画面不产生错误恢复")
    void orbNoFalseRecovery() {
        Mat ref = sequence.render(SyntheticSequence.identity());
        Mat refGray = new Mat();
        ref.convertTo(refGray, CvType.CV_8UC1);
        OrbRelocalizer relocalizer = new OrbRelocalizer(refGray, null);

        SyntheticSequence other = new SyntheticSequence(999L);
        Mat unrelated = other.render(SyntheticSequence.identity());
        Mat unrelatedGray = new Mat();
        unrelated.convertTo(unrelatedGray, CvType.CV_8UC1);

        OrbRelocalizer.Matches matches = relocalizer.match(unrelatedGray, 0.6);
        System.out.println("[orb] unrelated good matches = " + matches.size());
        // 不同纹理底图之间不应产生大量可靠匹配
        assertTrue(matches.size() < 60, "unexpected matches: " + matches.size());

        relocalizer.release();
        other.release();
        ref.release();
        refGray.release();
        unrelated.release();
        unrelatedGray.release();
    }
}
