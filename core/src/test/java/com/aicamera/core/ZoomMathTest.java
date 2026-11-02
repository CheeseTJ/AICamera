package com.aicamera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 变焦数学（规格 §10.2 / §10.3 / §10.4）。 */
class ZoomMathTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("文档示例：区域宽高约 60% 时目标倍率约 1.67x")
    void documentExample() {
        double[] rect = {0.2, 0.2, 0.8, 0.8};
        assertEquals(1.0 / 0.6, ZoomMath.targetZoomRatio(1.0, rect), 1e-6);
        assertEquals(1.6667, ZoomMath.targetZoomRatio(1.0, rect), 1e-3);
    }

    @Test
    @DisplayName("只放大不缩小：区域大于画面时目标倍率小于当前倍率")
    void neverZoomOut() {
        double[] rect = {-0.1, -0.1, 1.1, 1.1};
        double target = ZoomMath.targetZoomRatio(1.0, rect);
        assertTrue(target < 1.0);
        assertFalse(ZoomMath.isZoomWithinLimit(1.0, target, 2.0));
    }

    @Test
    @DisplayName("超过上限时拒绝执行")
    void zoomLimit() {
        assertTrue(ZoomMath.isZoomWithinLimit(1.0, 2.0, 2.0));
        assertFalse(ZoomMath.isZoomWithinLimit(1.0, 2.01, 2.0));
        assertFalse(ZoomMath.isZoomWithinLimit(1.5, 3.0, 2.0));
    }

    @Test
    @DisplayName("对数插值端点精确、中间单调递增")
    void logInterpolation() {
        assertEquals(1.0, ZoomMath.interpolateLog(1.0, 2.0, 0.0), EPS);
        assertEquals(2.0, ZoomMath.interpolateLog(1.0, 2.0, 1.0), EPS);
        assertEquals(Math.sqrt(2.0), ZoomMath.interpolateLog(1.0, 2.0, 0.5), EPS);
        double prev = 0;
        for (int i = 0; i <= 10; i++) {
            double v = ZoomMath.interpolateLog(1.2, 1.9, i / 10.0);
            assertTrue(v > prev);
            prev = v;
        }
    }

    @Test
    @DisplayName("径向扩张预测把点推离光轴中心")
    void expandPoint() {
        double[] p = ZoomMath.expandPoint(0.6, 0.5, 0.5, 0.5, 2.0);
        assertEquals(0.7, p[0], EPS);
        assertEquals(0.5, p[1], EPS);
        assertEquals(2.0, ZoomMath.expansionFactor(1.0, 2.0), EPS);
        assertEquals(1.0, ZoomMath.expansionFactor(0.0, 2.0), EPS);
    }

    @Test
    @DisplayName("smoothstep 端点与对称性")
    void smoothStep() {
        assertEquals(0.0, ZoomMath.smoothStep(0.0), EPS);
        assertEquals(1.0, ZoomMath.smoothStep(1.0), EPS);
        assertEquals(0.5, ZoomMath.smoothStep(0.5), EPS);
        assertEquals(0.5, ZoomMath.smoothStep(0.5) + ZoomMath.smoothStep(0.5) - 0.5, EPS);
    }
}
