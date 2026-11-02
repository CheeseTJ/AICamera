package com.aicamera.vision;

import java.util.Random;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

/**
 * 合成测试序列生成器：用已知仿射变换逐帧重采样一张高纹理底图。
 *
 * <p>这样测试拥有像素级真值，可以定量评估跟踪误差，而不依赖真实拍摄素材。
 */
final class SyntheticSequence {

    static final int WIDTH = 640;
    static final int HEIGHT = 480;

    /** 底图（比画幅大，保证平移后仍有内容）。 */
    final Mat canvas;

    SyntheticSequence(long seed) {
        canvas = buildTexturedCanvas(seed);
    }

    /**
     * 生成一帧：canvas 经 M 变换后填入 WIDTH×HEIGHT 画幅。
     *
     * @param m 底图坐标 → 当前帧坐标 的 2x3 仿射矩阵
     */
    Mat render(double[] m) {
        Mat dst = new Mat(HEIGHT, WIDTH, CvType.CV_8UC1, new Scalar(0));
        Mat mm = new Mat(2, 3, CvType.CV_64F);
        mm.put(0, 0, m);
        Imgproc.warpAffine(canvas, dst, mm, new org.opencv.core.Size(WIDTH, HEIGHT),
            Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
        mm.release();
        return dst;
    }

    void release() {
        canvas.release();
    }

    /** 恒等变换。 */
    static double[] identity() {
        return new double[] {1, 0, 0, 0, 1, 0};
    }

    /** 平移。 */
    static double[] translation(double dx, double dy) {
        return new double[] {1, 0, dx, 0, 1, dy};
    }

    /** 绕 (cx,cy) 旋转 radians。 */
    static double[] rotation(double cx, double cy, double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new double[] {c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy};
    }

    /** 绕 (cx,cy) 缩放 factor。 */
    static double[] scale(double cx, double cy, double factor) {
        return new double[] {factor, 0, cx - factor * cx, 0, factor, cy - factor * cy};
    }

    /** 复合：先 a 后 b。 */
    static double[] compose(double[] a, double[] b) {
        double[] r = new double[6];
        r[0] = b[0] * a[0] + b[1] * a[3];
        r[1] = b[0] * a[1] + b[1] * a[4];
        r[2] = b[0] * a[2] + b[1] * a[5] + b[2];
        r[3] = b[3] * a[0] + b[4] * a[3];
        r[4] = b[3] * a[1] + b[4] * a[4];
        r[5] = b[3] * a[2] + b[4] * a[5] + b[5];
        return r;
    }

    /** 用 2x3 矩阵变换归一化点，返回归一化坐标。 */
    static double[] apply(double[] m, double x, double y) {
        return new double[] {m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5]};
    }

    private static Mat buildTexturedCanvas(long seed) {
        int w = WIDTH * 2;
        int h = HEIGHT * 2;
        Mat img = new Mat(h, w, CvType.CV_8UC1, new Scalar(30));
        Random rnd = new Random(seed);

        // 随机矩形与圆，制造丰富角点
        for (int i = 0; i < 260; i++) {
            int x = rnd.nextInt(w - 60);
            int y = rnd.nextInt(h - 60);
            int ww = 10 + rnd.nextInt(60);
            int hh = 10 + rnd.nextInt(60);
            int v = 90 + rnd.nextInt(160);
            Imgproc.rectangle(img, new Point(x, y), new Point(x + ww, y + hh),
                new Scalar(v), rnd.nextBoolean() ? -1 : 2);
        }
        for (int i = 0; i < 160; i++) {
            int x = rnd.nextInt(w - 40);
            int y = rnd.nextInt(h - 40);
            int r = 4 + rnd.nextInt(18);
            int v = 120 + rnd.nextInt(135);
            Imgproc.circle(img, new Point(x, y), r, new Scalar(v), rnd.nextBoolean() ? -1 : 2);
        }
        // 细网格强化角点分布
        for (int x = 0; x < w; x += 23) {
            Imgproc.line(img, new Point(x, 0), new Point(x, h), new Scalar(70), 1);
        }
        for (int y = 0; y < h; y += 23) {
            Imgproc.line(img, new Point(0, y), new Point(w, y), new Scalar(70), 1);
        }
        return img;
    }
}
