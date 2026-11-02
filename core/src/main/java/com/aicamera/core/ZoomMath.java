package com.aicamera.core;

/**
 * 自动变焦数学（规格 §10.2 / §10.3 / §10.4）。
 *
 * <p>所有函数均为纯函数，便于单测覆盖。
 */
public final class ZoomMath {

    private ZoomMath() {
    }

    /**
     * 目标倍率 z = z_current / s，其中 s 取 sx、sy 的均值（§10.2）。
     *
     * @param currentZoomRatio 当前实际倍率
     * @param captureRect      归一化坐标下的推荐捕获矩形 [x1,y1,x2,y2]
     */
    public static double targetZoomRatio(double currentZoomRatio, double[] captureRect) {
        double sx = captureRect[2] - captureRect[0];
        double sy = captureRect[3] - captureRect[1];
        double s = (sx + sy) / 2.0;
        if (!(s > 1e-6)) {
            return currentZoomRatio;
        }
        return currentZoomRatio / s;
    }

    /** 对数插值（§10.3）。u 应在 [0,1]。 */
    public static double interpolateLog(double z0, double z1, double u) {
        double clamped = Math.max(0.0, Math.min(1.0, u));
        return Math.exp((1.0 - clamped) * Math.log(z0) + clamped * Math.log(z1));
    }

    /** 平滑缓入缓出（smoothstep）。 */
    public static double smoothStep(double u) {
        double t = Math.max(0.0, Math.min(1.0, u));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * 变焦引起的径向扩张因子 g = zNew / zOld（§10.4）。
     */
    public static double expansionFactor(double zOld, double zNew) {
        if (zOld <= 1e-9) {
            return 1.0;
        }
        return zNew / zOld;
    }

    /** 以光轴中心 (cx, cy) 为原点做径向扩张；只用于光流初值预测。 */
    public static double[] expandPoint(double x, double y, double cx, double cy, double g) {
        return new double[] {cx + g * (x - cx), cy + g * (y - cy)};
    }

    /**
     * 判断目标倍率是否在上限内（规格 §4.3）。
     *
     * <p>只允许自动放大，不自动缩小。
     */
    public static boolean isZoomWithinLimit(double current, double target, double maxRatio) {
        if (!(target > current + 1e-3)) {
            return false;
        }
        return target <= maxRatio + 1e-9;
    }
}
