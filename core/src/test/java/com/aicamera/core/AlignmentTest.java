package com.aicamera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对齐判定与时间闸门（规格 §10.1）。 */
class AlignmentTest {

    private AlignmentConfig config;
    private AlignmentEvaluator evaluator;
    private FramePacket frame;

    @BeforeEach
    void setUp() {
        config = new AlignmentConfig();
        evaluator = new AlignmentEvaluator(config);
        frame = new FramePacket("s1", 1L, 0L, 640, 480, 90, 1.0, null);
    }

    @Test
    @DisplayName("居中矩形通过全部前置条件")
    void centeredRectReady() {
        Quad q = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);
        assertTrue(e.alignmentReady, e.toString());
        assertNotNull(e.captureRect);
        assertNull(e.reasonCode);
    }

    @Test
    @DisplayName("偏右区域不放行，并给出 NOT_CENTERED")
    void notCentered() {
        Quad q = Quad.fromRect(0.35, 0.2, 0.95, 0.8);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);
        assertFalse(e.alignmentReady);
        assertEquals("NOT_CENTERED", e.reasonCode);
    }

    @Test
    @DisplayName("部分出画不放行")
    void outOfView() {
        Quad q = Quad.fromRect(0.9, 0.2, 1.5, 0.8);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);
        assertFalse(e.alignmentReady);
        assertEquals("TARGET_OUT_OF_VIEW", e.reasonCode);
        assertTrue(e.visibleRatio < 0.98);
    }

    @Test
    @DisplayName("旋转超限不放行")
    void rotated() {
        Quad q = Quad.of(0.28, 0.18, 0.82, 0.26, 0.78, 0.82, 0.24, 0.74);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);
        assertFalse(e.alignmentReady);
    }

    @Test
    @DisplayName("宽高不一致（已被拉伸）不放行")
    void aspectMismatch() {
        Quad q = Quad.fromRect(0.1, 0.28, 0.9, 0.72);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);
        assertFalse(e.alignmentReady);
        assertEquals("ASPECT_MISMATCH", e.reasonCode);
    }

    @Test
    @DisplayName("非 LOCKED 状态一律禁止自动变焦评估")
    void stateGate() {
        Quad q = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        for (GuidanceState s : GuidanceState.values()) {
            AlignmentEvaluation e = evaluator.evaluate(q, s, frame);
            if (s == GuidanceState.LOCKED) {
                assertTrue(e.alignmentReady);
            } else {
                assertFalse(e.alignmentReady, "state " + s + " should not be ready");
            }
        }
    }

    @Test
    @DisplayName("必须连续满足 600ms 且 ≥10 帧才放行")
    void stabilizerHold() {
        AlignmentStabilizer stabilizer = new AlignmentStabilizer(config);
        Quad q = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        AlignmentEvaluation e = evaluator.evaluate(q, GuidanceState.LOCKED, frame);

        long step = 50_000_000L; // 50 ms
        boolean ready = false;
        int frames = 0;
        for (int i = 0; i < 20 && !ready; i++) {
            frames++;
            ready = stabilizer.update(e, i * step);
        }
        assertTrue(ready, "should become ready");
        assertTrue(stabilizer.heldNanos() >= config.stableHoldNanos);
        assertTrue(frames >= config.minStableSamples + 1);
    }

    @Test
    @DisplayName("中断一次即清零计数")
    void stabilizerResetOnFailure() {
        AlignmentStabilizer stabilizer = new AlignmentStabilizer(config);
        Quad ok = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        Quad bad = Quad.fromRect(0.35, 0.2, 0.95, 0.8);
        AlignmentEvaluation eOk = evaluator.evaluate(ok, GuidanceState.LOCKED, frame);
        AlignmentEvaluation eBad = evaluator.evaluate(bad, GuidanceState.LOCKED, frame);

        for (int i = 0; i < 15; i++) {
            stabilizer.update(eOk, i * 50_000_000L);
        }
        assertTrue(stabilizer.consecutiveSamples() > 0);
        assertFalse(stabilizer.update(eBad, 15 * 50_000_000L));
        assertEquals(0, stabilizer.consecutiveSamples());
    }
}
