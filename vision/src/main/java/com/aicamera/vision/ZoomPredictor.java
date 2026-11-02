package com.aicamera.vision;

import com.aicamera.core.Mat3;
import com.aicamera.core.ZoomMath;
import java.util.List;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;

/**
 * 变焦期间的跟踪初值预测（规格 §10.4）。
 *
 * <p>变焦会让所有点沿光轴中心径向扩张。把扩张预测作为 KLT 的初始点，
 * 可显著扩大可跟踪的帧间位移范围，避免变焦时因位移过大而丢失。
 *
 * <p>注意：预测只作为"初值"，绝不能在光流结果上再乘一次扩张因子。
 */
public final class ZoomPredictor {

    private ZoomPredictor() {
    }

    /**
     * 按倍率变化构造径向扩张的点集。
     *
     * @param points 源点（像素）
     * @param cx     光轴中心 X（像素）
     * @param cy     光轴中心 Y（像素）
     * @param gain   g = zNew / zOld
     * @return 预测点集；gain 接近 1 时返回 null 表示无需预测
     */
    public static MatOfPoint2f predict(org.opencv.core.MatOfPoint2f points,
        double cx, double cy, double gain) {
        if (points == null || points.rows() == 0) {
            return null;
        }
        if (Math.abs(gain - 1.0) < 0.002) {
            return null;
        }
        Point[] src = points.toArray();
        Point[] dst = new Point[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = new Point(cx + gain * (src[i].x - cx), cy + gain * (src[i].y - cy));
        }
        return new MatOfPoint2f(dst);
    }

    /** 由四次角点测得视野收缩比例，返回等价倍率增益。 */
    public static double gainFromQuadShrink(double[] prevQuadPx, double[] currentQuadPx) {
        if (prevQuadPx == null || currentQuadPx == null) {
            return 1.0;
        }
        double prevW = quadWidth(prevQuadPx);
        double curW = quadWidth(currentQuadPx);
        if (curW <= 1e-6 || prevW <= 1e-6) {
            return 1.0;
        }
        return prevW / curW;
    }

    /** 用倍率变化构造中心缩放矩阵（归一化坐标）。 */
    public static Mat3 scaleAboutCenter(double gain) {
        return Mat3.scaleAbout(0.5, 0.5, gain);
    }

    /** 由归一化四边形中心缩放后的结果。 */
    public static double[] expand(double[] quad, double gain) {
        double[] out = new double[quad.length];
        for (int i = 0; i < quad.length / 2; i++) {
            out[i * 2] = 0.5 + (quad[i * 2] - 0.5) * gain;
            out[i * 2 + 1] = 0.5 + (quad[i * 2 + 1] - 0.5) * gain;
        }
        return out;
    }

    private static double quadWidth(double[] quad) {
        double top = Math.hypot(quad[2] - quad[0], quad[3] - quad[1]);
        double bottom = Math.hypot(quad[6] - quad[4], quad[7] - quad[5]);
        return (top + bottom) / 2.0;
    }

    /** 便捷方法：把扩张预测应用到点列表。 */
    public static List<double[]> predictAll(List<double[]> pts, double cx, double cy, double gain) {
        return pts.stream()
            .map(p -> ZoomMath.expandPoint(p[0], p[1], cx, cy, gain))
            .collect(java.util.stream.Collectors.toList());
    }
}
