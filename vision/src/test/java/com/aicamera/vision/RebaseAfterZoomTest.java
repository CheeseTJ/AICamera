package com.aicamera.vision;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicamera.core.Quad;
import com.aicamera.core.ZoomMath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

/**
 * 验证变焦后重建参考基准（规格 §10.4）。
 *
 * <p>变焦会把原参考帧的角点大量推出画面，若不重建基准，后续帧会持续丢跟踪。
 */
class RebaseAfterZoomTest {

    @BeforeAll
    static void load() {
        Assertions.assertTrue(OpenCvLoader.ensureLoaded());
    }

    private SyntheticSequence sequence;

    @BeforeEach
    void setUp() {
        sequence = new SyntheticSequence(5150L);
    }

    @AfterEach
    void tearDown() {
        sequence.release();
    }

    @Test
    @DisplayName("变焦后重建基准可使跟踪继续工作")
    void rebaseRestoresTracking() {
        Quad anchor = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        RegionTracker tracker = new RegionTracker(new TrackerConfig());
        Mat first = sequence.render(SyntheticSequence.identity());
        Assertions.assertTrue(tracker.initialize(first, anchor, 1.0));
        first.release();

        // 放大到 1.65x
        double z = 1.0 / 0.6;
        Mat zoomed = zoomImage(sequence.render(SyntheticSequence.identity()), z);
        RegionTracker.Result before = tracker.track(zoomed, z);
        System.out.println("[rebase] before rebase: hasPos=" + before.hasPosition()
            + " pts=" + before.validPoints + " reason=" + before.reasonCode);

        // 重建基准：变焦后的目标位置（归一化）
        double[] rebasedQuad = new double[8];
        double[] src = anchor.toArray();
        for (int k = 0; k < 4; k++) {
            rebasedQuad[k * 2] = 0.5 + (src[k * 2] - 0.5) * z;
            rebasedQuad[k * 2 + 1] = 0.5 + (src[k * 2 + 1] - 0.5) * z;
        }
        Quad currentQuad = Quad.of(rebasedQuad);
        boolean ok = tracker.rebaseReference(zoomed, currentQuad, z);
        assertTrue(ok, "重建应成功");

        // 重建后继续小幅移动，应能稳定跟踪
        int okFrames = 0;
        double worst = 0;
        for (int i = 1; i <= 15; i++) {
            double[] m = SyntheticSequence.compose(SyntheticSequence.scale(320, 240, z),
                SyntheticSequence.translation(1.0 * i, 0.5 * i));
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f, z);
            f.release();
            if (r.hasPosition()) {
                okFrames++;
                double[] truth = new double[8];
                double[] s4 = anchor.toArray();
                for (int k = 0; k < 4; k++) {
                    double x = s4[k * 2] * SyntheticSequence.WIDTH;
                    double y = s4[k * 2 + 1] * SyntheticSequence.HEIGHT;
                    double zx = 320 + z * (x - 320);
                    double zy = 240 + z * (y - 240);
                    truth[k * 2] = (zx + i) / SyntheticSequence.WIDTH;
                    truth[k * 2 + 1] = (zy + 0.5 * i) / SyntheticSequence.HEIGHT;
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
        }
        System.out.println("[rebase] after rebase: okFrames=" + okFrames + "/15, worst err=" + worst + "px");
        assertTrue(okFrames >= 13, "重建后应能持续跟踪，实际 " + okFrames + "/15");
        assertTrue(worst < 6.0, "重建后误差应可控，实际 " + worst);
        zoomed.release();
        tracker.release();
    }

    @Test
    @DisplayName("区域纹理不足时重建被明确拒绝，保留原基准")
    void rebaseRejectedOnLowTexture() {
        Quad anchor = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        RegionTracker tracker = new RegionTracker(new TrackerConfig());
        Mat first = sequence.render(SyntheticSequence.identity());
        Assertions.assertTrue(tracker.initialize(first, anchor, 1.0));
        first.release();

        Mat blank = new Mat(SyntheticSequence.HEIGHT, SyntheticSequence.WIDTH,
            org.opencv.core.CvType.CV_8UC1, new org.opencv.core.Scalar(128));
        boolean ok = tracker.rebaseReference(blank, anchor, 1.0);
        assertTrue(!ok, "纯色画面不应接受重建");
        // 原基准仍可用
        Mat f = sequence.render(SyntheticSequence.translation(2, 0));
        assertTrue(tracker.track(f, 1.0).hasPosition(), "原基准应保持有效");
        blank.release();
        f.release();
        tracker.release();
    }

    private static Mat zoomImage(Mat src, double z) {
        Mat dst = new Mat();
        Mat m = new Mat(2, 3, org.opencv.core.CvType.CV_64F);
        m.put(0, 0, new double[] {z, 0, 320 - z * 320, 0, z, 240 - z * 240});
        org.opencv.imgproc.Imgproc.warpAffine(src, dst, m,
            new org.opencv.core.Size(src.cols(), src.rows()),
            org.opencv.imgproc.Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE);
        m.release();
        src.release();
        return dst;
    }
}
