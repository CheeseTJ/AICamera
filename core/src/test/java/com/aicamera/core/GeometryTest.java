package com.aicamera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 几何与坐标规范（规格 §5.1 / §9.1）。 */
class GeometryTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("完整可见的居中框可见比例为 1")
    void fullyVisibleQuad() {
        Quad q = Quad.fromRect(0.2, 0.2, 0.8, 0.8);
        assertEquals(1.0, q.visibleRatio(), EPS);
        assertEquals(0.6, q.meanWidth(), EPS);
        assertEquals(0.6, q.meanHeight(), EPS);
        assertEquals(0.36, q.area(), EPS);
    }

    @Test
    @DisplayName("部分出画时可见比例按交集面积计算，且不截断存储坐标")
    void partiallyVisibleQuad() {
        Quad q = Quad.fromRect(0.7, 0.2, 1.3, 0.8);
        // 交集宽 0.3，高 0.6 => 0.18；原面积 0.6*0.6 = 0.36
        assertEquals(0.5, q.visibleRatio(), 1e-5);
        // 原始坐标保留出画部分
        assertEquals(1.3, q.x(1), EPS);
    }

    @Test
    @DisplayName("完全出画时可见比例为 0，显示框为空")
    void fullyOutOfView() {
        Quad q = Quad.fromRect(1.2, 0.2, 1.8, 0.8);
        assertEquals(0.0, q.visibleRatio(), EPS);
        assertNull(q.clippedForDisplay());
    }

    @Test
    @DisplayName("旋转角在 ±90° 内折叠")
    void rotationFold() {
        Quad q = Quad.of(0.2, 0.3, 0.8, 0.3000001, 0.8, 0.9, 0.2, 0.9);
        assertEquals(0.0, q.rotationDegrees(), 1e-3);
    }

    @Test
    @DisplayName("仿射变换后顶点保持顺序与比例")
    void affineTransform() {
        Quad q = Quad.fromRect(0.2, 0.2, 0.6, 0.6);
        Quad moved = q.transform(Mat3.translation(0.1, -0.05));
        assertEquals(0.3, moved.x(0), EPS);
        assertEquals(0.15, moved.y(0), EPS);
        assertEquals(0.4, moved.meanWidth(), EPS);
    }

    @Test
    @DisplayName("以画面中心缩放会改变面积但保持中心")
    void scaleAboutCenter() {
        Quad q = Quad.fromRect(0.3, 0.3, 0.7, 0.7);
        Quad zoomed = q.transform(Mat3.scaleAbout(0.5, 0.5, 1.5));
        assertEquals(0.5, zoomed.center()[0], EPS);
        assertEquals(0.5, zoomed.center()[1], EPS);
        assertEquals(0.6, zoomed.meanWidth(), EPS);
    }

    @Test
    @DisplayName("单应性透视畸变可被识别为角度偏差")
    void perspectiveDeviation() {
        Quad skewed = Quad.of(0.2, 0.2, 0.8, 0.28, 0.8, 0.82, 0.2, 0.75);
        assertTrue(skewed.maxCornerDeviationDegrees() > 5.0,
            "expected >5° deviation but was " + skewed.maxCornerDeviationDegrees());
    }
}
