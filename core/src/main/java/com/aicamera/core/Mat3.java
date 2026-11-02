package com.aicamera.core;

/**
 * 3x3 矩阵，行主序。
 *
 * <p>仅用于二维归一化坐标到二维归一化坐标的变换（单应 / 仿射 / 预览映射）。
 * 保持纯 Java 实现，便于在桌面端单元测试与 Android 端共用（规格 §5.1）。
 */
public final class Mat3 {

    private final double[] m;

    private Mat3(double[] rowMajor) {
        this.m = rowMajor;
    }

    public static Mat3 identity() {
        return new Mat3(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
    }

    public static Mat3 of(double... rowMajor) {
        if (rowMajor == null || rowMajor.length != 9) {
            throw new IllegalArgumentException("Mat3 需要 9 个元素，实际 " + (rowMajor == null ? "null" : rowMajor.length));
        }
        return new Mat3(rowMajor.clone());
    }

    public static Mat3 translation(double tx, double ty) {
        return new Mat3(new double[] {1, 0, tx, 0, 1, ty, 0, 0, 1});
    }

    public static Mat3 scale(double sx, double sy) {
        return new Mat3(new double[] {sx, 0, 0, 0, sy, 0, 0, 0, 1});
    }

    /** 绕归一化中心点 (cx, cy) 的旋转，角度为弧度。 */
    public static Mat3 rotationAbout(double cx, double cy, double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat3(new double[] {
            c, -s, cx - c * cx + s * cy,
            s, c, cy - s * cx - c * cy,
            0, 0, 1,
        });
    }

    /** 以 (cx, cy) 为中心的均匀缩放。 */
    public static Mat3 scaleAbout(double cx, double cy, double factor) {
        return new Mat3(new double[] {
            factor, 0, cx - factor * cx,
            0, factor, cy - factor * cy,
            0, 0, 1,
        });
    }

    public double get(int row, int col) {
        return m[row * 3 + col];
    }

    public double[] toArray() {
        return m.clone();
    }

    /** 与列向量 (x, y, 1) 相乘并做透视归一。返回 {x', y'}。 */
    public double[] apply(double x, double y) {
        double w = m[6] * x + m[7] * y + m[8];
        if (Math.abs(w) < 1e-12) {
            return new double[] {Double.NaN, Double.NaN};
        }
        return new double[] {(m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w};
    }

    /** this * other （先应用 other，再应用 this）。 */
    public Mat3 multiply(Mat3 other) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += m[i * 3 + k] * other.m[k * 3 + j];
                }
                r[i * 3 + j] = sum;
            }
        }
        return new Mat3(r);
    }

    public double determinant() {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
            - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    /** 逆矩阵；不可逆时返回 null。 */
    public Mat3 invert() {
        double det = determinant();
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double inv = 1.0 / det;
        double[] r = new double[9];
        r[0] = (m[4] * m[8] - m[5] * m[7]) * inv;
        r[1] = (m[2] * m[7] - m[1] * m[8]) * inv;
        r[2] = (m[1] * m[5] - m[2] * m[4]) * inv;
        r[3] = (m[5] * m[6] - m[3] * m[8]) * inv;
        r[4] = (m[0] * m[8] - m[2] * m[6]) * inv;
        r[5] = (m[2] * m[3] - m[0] * m[5]) * inv;
        r[6] = (m[3] * m[7] - m[4] * m[6]) * inv;
        r[7] = (m[1] * m[6] - m[0] * m[7]) * inv;
        r[8] = (m[0] * m[4] - m[1] * m[3]) * inv;
        return new Mat3(r);
    }

    @Override
    public String toString() {
        return String.format("Mat3[[%.6f, %.6f, %.6f], [%.6f, %.6f, %.6f], [%.6f, %.6f, %.6f]]",
            m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]);
    }
}
