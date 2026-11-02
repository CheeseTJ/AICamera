package com.aicamera.core;

/**
 * 四边形几何工具：与单位画幅求交、可见比例、近似矩形判定等。
 *
 * <p>顶点顺序固定为 左上、右上、右下、左下（规格 §5.1）。
 * 存储坐标允许超出 [0,1]，禁止为显示方便截断。
 */
public final class Quad {

    /** 8 个 double：x0,y0,x1,y1,x2,y2,x3,y3。 */
    private final double[] p;

    private Quad(double[] p) {
        this.p = p;
    }

    public static Quad of(double... xy) {
        if (xy == null || xy.length != 8) {
            throw new IllegalArgumentException("Quad 需要 8 个坐标，实际 " + (xy == null ? "null" : xy.length));
        }
        return new Quad(xy.clone());
    }

    /** 由矩形 [x1,y1,x2,y2] 构造（归一化坐标）。 */
    public static Quad fromRect(double x1, double y1, double x2, double y2) {
        return new Quad(new double[] {x1, y1, x2, y1, x2, y2, x1, y2});
    }

    public double x(int i) {
        return p[i * 2];
    }

    public double y(int i) {
        return p[i * 2 + 1];
    }

    public double[] toArray() {
        return p.clone();
    }

    public Quad transform(Mat3 m) {
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            double[] r = m.apply(p[i * 2], p[i * 2 + 1]);
            out[i * 2] = r[0];
            out[i * 2 + 1] = r[1];
        }
        return new Quad(out);
    }

    public boolean isFinite() {
        for (double v : p) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    public double[] center() {
        return new double[] {
            (p[0] + p[2] + p[4] + p[6]) / 4.0,
            (p[1] + p[3] + p[5] + p[7]) / 4.0,
        };
    }

    /** 上边与下边的平均长度（近似宽度）。 */
    public double meanWidth() {
        return (dist(0, 1) + dist(3, 2)) / 2.0;
    }

    /** 左边与右边的平均长度（近似高度）。 */
    public double meanHeight() {
        return (dist(0, 3) + dist(1, 2)) / 2.0;
    }

    private double dist(int a, int b) {
        double dx = p[b * 2] - p[a * 2];
        double dy = p[b * 2 + 1] - p[a * 2 + 1];
        return Math.hypot(dx, dy);
    }

    /** 用鞋带公式计算的面积（可能为负，取绝对值）。 */
    public double area() {
        return Math.abs(Polygons.signedArea(p));
    }

    /** 与画幅 [0,1]x[0,1] 的交集面积。 */
    public double visibleArea() {
        return Polygons.intersectionAreaWithUnitSquare(p);
    }

    /** 可见比例 V = Area(Qt ∩ F) / Area(Qt)（规格 §9.1）。 */
    public double visibleRatio() {
        double a = area();
        if (a <= 1e-9) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, visibleArea() / a));
    }

    /** 裁切到画幅内用于显示；内部仍保留完整四边形。 */
    public Quad clippedForDisplay() {
        double[][] clipped = Polygons.clipToUnitSquare(p);
        if (clipped.length < 4) {
            return null;
        }
        // 交点数量可能大于 4，这里取外接矩形四角以保持显示稳定。
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] pt : clipped) {
            minX = Math.min(minX, pt[0]);
            minY = Math.min(minY, pt[1]);
            maxX = Math.max(maxX, pt[0]);
            maxY = Math.max(maxY, pt[1]);
        }
        return Quad.fromRect(minX, minY, maxX, maxY);
    }

    /** 上边相对水平方向的旋转角，单位弧度（范围 [-π/2, π/2]）。 */
    public double rotationRadians() {
        double dx = p[2] - p[0];
        double dy = p[3] - p[1];
        double a = Math.atan2(dy, dx);
        if (a > Math.PI / 2) {
            a -= Math.PI;
        } else if (a < -Math.PI / 2) {
            a += Math.PI;
        }
        return a;
    }

    public double rotationDegrees() {
        return Math.toDegrees(rotationRadians());
    }

    /** 四个内角偏离 90° 的最大值（度）。 */
    public double maxCornerDeviationDegrees() {
        double max = 0;
        for (int i = 0; i < 4; i++) {
            int prev = (i + 3) % 4;
            int next = (i + 1) % 4;
            double ax = p[prev * 2] - p[i * 2];
            double ay = p[prev * 2 + 1] - p[i * 2 + 1];
            double bx = p[next * 2] - p[i * 2];
            double by = p[next * 2 + 1] - p[i * 2 + 1];
            double na = Math.hypot(ax, ay);
            double nb = Math.hypot(bx, by);
            if (na < 1e-9 || nb < 1e-9) {
                return Double.NaN;
            }
            double cos = (ax * bx + ay * by) / (na * nb);
            cos = Math.max(-1.0, Math.min(1.0, cos));
            double deg = Math.toDegrees(Math.acos(cos));
            max = Math.max(max, Math.abs(90.0 - deg));
        }
        return max;
    }

    /**
     * 由四边形估计当前帧的有效捕获矩形（规格 §2.2 / §10.1）。
     *
     * <p>归一化坐标按轴各自归一，因此与输出画面同比例的构图框在归一化空间中宽高相等。
     * 这里用四边形平均宽高的均值 s 作为边长，中心取四边形中心，得到 [x1,y1,x2,y2]。
     *
     * @param aspectFactor 分析画面比例 / 输出比例；V1 两者相同，固定为 1
     * @return 形状不满足矩形近似或旋转超限时返回 null
     */
    public double[] toAspectRect(
        double aspectFactor, double maxCornerDeviationDegrees, double rotationToleranceDegrees) {
        if (!isFinite() || area() <= 1e-9) {
            return null;
        }
        double dev = maxCornerDeviationDegrees();
        if (!Double.isFinite(dev) || dev > maxCornerDeviationDegrees) {
            return null;
        }
        if (Math.abs(rotationDegrees()) > rotationToleranceDegrees) {
            return null;
        }
        double f = aspectFactor > 0 ? aspectFactor : 1.0;
        double w = meanWidth();
        double h = meanHeight() * f;
        double s = (w + h) / 2.0;
        if (!(s > 1e-6) || !Double.isFinite(s)) {
            return null;
        }
        double cx = center()[0];
        double cy = center()[1];
        double halfW = s / 2.0;
        double halfH = (s / f) / 2.0;
        return new double[] {cx - halfW, cy - halfH, cx + halfW, cy + halfH};
    }

    @Override
    public String toString() {
        return String.format("Quad[(%.5f,%.5f) (%.5f,%.5f) (%.5f,%.5f) (%.5f,%.5f)]",
            p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
    }
}
