package com.aicamera.core;

import java.util.ArrayList;
import java.util.List;

/** 多边形裁剪与面积计算（Sutherland-Hodgman），无第三方依赖。 */
public final class Polygons {

    private Polygons() {
    }

    public static double signedArea(double[] poly) {
        if (poly == null || poly.length < 6) {
            return 0;
        }
        int n = poly.length / 2;
        double s = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            s += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1];
        }
        return s / 2.0;
    }

    public static double area(double[][] poly) {
        double s = 0;
        for (int i = 0; i < poly.length; i++) {
            double[] a = poly[i];
            double[] b = poly[(i + 1) % poly.length];
            s += a[0] * b[1] - b[0] * a[1];
        }
        return Math.abs(s) / 2.0;
    }

    /** 与单位正方形 [0,1]x[0,1] 求交后的面积。 */
    public static double intersectionAreaWithUnitSquare(double[] quad) {
        double[][] clipped = clipToUnitSquare(quad);
        if (clipped.length < 3) {
            return 0.0;
        }
        return area(clipped);
    }

    /** 用四条半平面依次裁剪输入四边形，返回顶点列表。 */
    public static double[][] clipToUnitSquare(double[] quad) {
        if (quad == null || quad.length != 8) {
            return new double[0][];
        }
        List<double[]> poly = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            poly.add(new double[] {quad[i * 2], quad[i * 2 + 1]});
        }
        // x >= 0
        poly = clip(poly, 0, 1.0, 0.0, true);
        if (poly.size() < 3) {
            return new double[0][];
        }
        // x <= 1
        poly = clip(poly, 0, -1.0, 1.0, true);
        if (poly.size() < 3) {
            return new double[0][];
        }
        // y >= 0
        poly = clip(poly, 1, 1.0, 0.0, true);
        if (poly.size() < 3) {
            return new double[0][];
        }
        // y <= 1
        poly = clip(poly, 1, -1.0, 1.0, true);
        if (poly.size() < 3) {
            return new double[0][];
        }
        double[][] out = new double[poly.size()][];
        for (int i = 0; i < poly.size(); i++) {
            out[i] = poly.get(i);
        }
        return out;
    }

    /**
     * 半平面裁剪：axis 0 表示 x 分量，1 表示 y 分量。
     * 保留满足 {@code axisComponent * sign + offset >= 0} 的点。
     */
    private static List<double[]> clip(List<double[]> poly, int axis, double sign, double offset, boolean keepInside) {
        List<double[]> out = new ArrayList<>(poly.size() + 2);
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double[] cur = poly.get(i);
            double[] prev = poly.get((i + n - 1) % n);
            double curV = cur[axis] * sign + offset;
            double prevV = prev[axis] * sign + offset;
            boolean curIn = keepInside ? curV >= 0 : curV <= 0;
            boolean prevIn = keepInside ? prevV >= 0 : prevV <= 0;
            if (curIn) {
                if (!prevIn) {
                    out.add(intersect(prev, cur, prevV, curV));
                }
                out.add(cur);
            } else if (prevIn) {
                out.add(intersect(prev, cur, prevV, curV));
            }
        }
        return out;
    }

    private static double[] intersect(double[] a, double[] b, double va, double vb) {
        double denom = va - vb;
        double t = Math.abs(denom) < 1e-12 ? 0.0 : va / denom;
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }
}
