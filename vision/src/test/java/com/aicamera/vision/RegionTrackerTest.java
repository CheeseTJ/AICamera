package com.aicamera.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicamera.core.Quad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

/**
 * 阶段一验收：在已知真值的合成序列上验证原区域跟踪。
 *
 * <p>对应规格 §17.2 中"静止持机／小幅转动／抬高压低／轻微平移"四类基础运动。
 */
class RegionTrackerTest {

    private SyntheticSequence sequence;
    private RegionTracker tracker;
    private Quad anchor;

    @BeforeAll
    static void loadOpenCv() {
        Assertions.assertTrue(OpenCvLoader.ensureLoaded(), "OpenCV 必须可用");
    }

    @BeforeEach
    void setUp() {
        sequence = new SyntheticSequence(20240501L);
        tracker = new RegionTracker(new TrackerConfig());
        // 归一化目标区域：中心 60%，与文档示例一致
        anchor = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        Mat first = sequence.render(SyntheticSequence.identity());
        Assertions.assertTrue(tracker.initialize(first, anchor), "初始化应成功");
        first.release();
    }

    @AfterEach
    void tearDown() {
        tracker.release();
        sequence.release();
    }

    /** 第 i 帧真值四边形（归一化坐标）。 */
    private Quad truth(double[] frameTransform) {
        double[] src = anchor.toArray();
        double[] dst = new double[8];
        for (int i = 0; i < 4; i++) {
            double[] p = SyntheticSequence.apply(frameTransform,
                src[i * 2] * SyntheticSequence.WIDTH, src[i * 2 + 1] * SyntheticSequence.HEIGHT);
            dst[i * 2] = p[0] / SyntheticSequence.WIDTH;
            dst[i * 2 + 1] = p[1] / SyntheticSequence.HEIGHT;
        }
        return Quad.of(dst);
    }

    private double maxCornerErrorPx(Quad a, Quad b) {
        double max = 0;
        for (int i = 0; i < 4; i++) {
            double dx = (a.x(i) - b.x(i)) * SyntheticSequence.WIDTH;
            double dy = (a.y(i) - b.y(i)) * SyntheticSequence.HEIGHT;
            max = Math.max(max, Math.hypot(dx, dy));
        }
        return max;
    }

    private double meanCornerErrorPx(Quad a, Quad b) {
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            double dx = (a.x(i) - b.x(i)) * SyntheticSequence.WIDTH;
            double dy = (a.y(i) - b.y(i)) * SyntheticSequence.HEIGHT;
            sum += Math.hypot(dx, dy);
        }
        return sum / 4.0;
    }

    @Test
    @DisplayName("静止持机：跟踪误差接近 0 且质量高")
    void staticCamera() {
        for (int i = 0; i < 15; i++) {
            Mat f = sequence.render(SyntheticSequence.identity());
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "frame " + i + " reason=" + r.reasonCode);
            double err = maxCornerErrorPx(r.quadNormalized, truth(SyntheticSequence.identity()));
            assertTrue(err < 1.5, "frame " + i + " err=" + err);
            assertTrue(r.quality > 0.6, "quality=" + r.quality);
        }
    }

    @Test
    @DisplayName("轻微平移：连续 1.5px/帧，跟踪误差低于 3px")
    void smallTranslation() {
        double totalDx = 0;
        double worst = 0;
        for (int i = 1; i <= 20; i++) {
            totalDx += 1.5;
            double[] m = SyntheticSequence.translation(totalDx, 0.5 * i / 4.0);
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "frame " + i + " reason=" + r.reasonCode);
            double err = maxCornerErrorPx(r.quadNormalized, truth(m));
            worst = Math.max(worst, err);
            assertTrue(err < 3.0, "frame " + i + " err=" + err);
        }
        System.out.println("[smallTranslation] worst corner error = " + worst + " px");
    }

    @Test
    @DisplayName("小幅转动：0.3°/帧累计 6°，跟踪误差低于 4px")
    void smallRotation() {
        double worst = 0;
        int frames = 20;
        for (int i = 1; i <= frames; i++) {
            double[] m = SyntheticSequence.rotation(320, 240, Math.toRadians(0.3 * i));
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "frame " + i + " reason=" + r.reasonCode);
            double err = maxCornerErrorPx(r.quadNormalized, truth(m));
            worst = Math.max(worst, err);
            assertTrue(err < 4.0, "frame " + i + " err=" + err);
        }
        System.out.println("[smallRotation] worst corner error = " + worst + " px");
    }

    @Test
    @DisplayName("抬高／压低：垂直 2px/帧平移")
    void verticalPan() {
        double worst = 0;
        for (int i = 1; i <= 18; i++) {
            double[] m = SyntheticSequence.translation(0, -2.0 * i);
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "frame " + i + " reason=" + r.reasonCode);
            double err = maxCornerErrorPx(r.quadNormalized, truth(m));
            worst = Math.max(worst, err);
            assertTrue(err < 3.5, "frame " + i + " err=" + err);
        }
        System.out.println("[verticalPan] worst corner error = " + worst + " px");
    }

    @Test
    @DisplayName("部分出画：可见比例正确下降，出画坐标不被截断")
    void partialOutOfView() {
        double worstVisibleDelta = 0;
        for (int i = 1; i <= 22; i++) {
            double[] m = SyntheticSequence.translation(-9.0 * i, 0);
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f);
            f.release();
            assertTrue(r.hasPosition(), "frame " + i + " reason=" + r.reasonCode);
            Quad t = truth(m);
            double delta = Math.abs(r.quadNormalized.visibleRatio() - t.visibleRatio());
            worstVisibleDelta = Math.max(worstVisibleDelta, delta);
            assertEquals(t.visibleRatio(), r.quadNormalized.visibleRatio(), 0.06,
                "frame " + i + " visible ratio");
        }
        System.out.println("[partialOutOfView] worst visible-ratio delta = " + worstVisibleDelta);
    }

    @Test
    @DisplayName("出画后返回：恢复原目标，不漂移到其他区域")
    void outAndBack() {
        // 逐步向左移出
        for (int i = 1; i <= 22; i++) {
            Mat f = sequence.render(SyntheticSequence.translation(-9.0 * i, 0));
            tracker.track(f);
            f.release();
        }
        // 逐步返回
        double[] last = null;
        for (int i = 22; i >= 1; i--) {
            double[] m = SyntheticSequence.translation(-9.0 * i, 0);
            Mat f = sequence.render(m);
            RegionTracker.Result r = tracker.track(f);
            f.release();
            if (r.hasPosition()) {
                last = r.quadNormalized.toArray();
            }
        }
        assertNotNull(last, "返回过程中应至少有一帧位置");
        Quad back = Quad.of(last);
        double err = meanCornerErrorPx(back, truth(SyntheticSequence.identity()));
        assertTrue(err < 12.0, "mean corner error after return = " + err);
        System.out.println("[outAndBack] mean corner error = " + err + " px");
    }

    @Test
    @DisplayName("低纹理纯色画面：明确报告特征不足，不假装跟踪成功")
    void lowTextureFails() {
        Mat blank = new Mat(SyntheticSequence.HEIGHT, SyntheticSequence.WIDTH,
            org.opencv.core.CvType.CV_8UC1, new org.opencv.core.Scalar(128));
        RegionTracker fresh = new RegionTracker(new TrackerConfig());
        boolean initialized = fresh.initialize(blank, anchor);
        assertTrue(!initialized, "纯色画面不应能初始化跟踪");
        fresh.release();
        blank.release();
    }

    @Test
    @DisplayName("帧尺寸变化被明确拒绝")
    void frameSizeChangeRejected() {
        Mat odd = new Mat(240, 320, org.opencv.core.CvType.CV_8UC1, new org.opencv.core.Scalar(0));
        RegionTracker.Result r = tracker.track(odd);
        assertTrue(!r.hasPosition());
        assertEquals("FRAME_SIZE_CHANGED", r.reasonCode);
        odd.release();
    }
}
