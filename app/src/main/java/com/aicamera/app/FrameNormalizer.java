package com.aicamera.app;

import android.media.Image;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import android.util.Size;
import com.aicamera.core.Mat3;
import java.nio.ByteBuffer;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * 帧预处理与坐标映射（规格 §3.1 FrameNormalizer / §5.1 坐标系）。
 *
 * <p>关键点：
 * <ul>
 *   <li>用 INTER_AREA 做等比例降采样，避免最近邻别名导致角点抖动；</li>
 *   <li>旋转矩阵按「顺时针」语义，和 CameraX 的 rotationDegrees 定义一致；</li>
 *   <li>4:3 预览在竖屏上以 letterbox 呈现，由 {@link PreviewViewport} 给出真实显示矩形。</li>
 * </ul>
 */
public final class FrameNormalizer {

    public static final int ANALYSIS_WIDTH = 640;
    public static final int ANALYSIS_HEIGHT = 480;

    private FrameNormalizer() {
    }

    /** 分析用例配置（规格 §4.2）。 */
    public static ImageAnalysis buildAnalysisUseCase(int width, int height) {
        ResolutionSelector selector = new ResolutionSelector.Builder()
            .setResolutionStrategy(new ResolutionStrategy(
                new Size(width, height),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build();
        return new ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build();
    }

    /**
     * Y 平面 → 固定尺寸灰度 Mat（调用方负责 release）。
     *
     * <p>使用 INTER_AREA 降采样：相机常见输出为 4:3，缩放比例在两个方向一致，
     * 不会引入各向异性形变。
     *
     * @return 灰度 Mat，失败返回 null
     */
    public static Mat toAnalysisGrayMat(@NonNull ImageProxy proxy) {
        Image image = proxy.getImage();
        if (image == null) {
            return null;
        }
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        int srcW = proxy.getWidth();
        int srcH = proxy.getHeight();
        if (srcW <= 0 || srcH <= 0 || rowStride < srcW * pixelStride) {
            return null;
        }

        Mat raw = new Mat(srcH, rowStride, CvType.CV_8UC1);
        Mat valid = null;
        Mat gray = new Mat();
        try {
            byte[] rowBuf = new byte[rowStride];
            for (int r = 0; r < srcH; r++) {
                int offset = r * rowStride;
                if (offset >= buffer.limit()) {
                    return null;
                }
                buffer.position(offset);
                int avail = Math.min(rowStride, buffer.remaining());
                buffer.get(rowBuf, 0, avail);
                raw.put(r, 0, rowBuf);
            }

            // 每行有效像素为 srcW，像素间可能有 pixelStride 填充
            int validCols = (srcW - 1) * pixelStride + 1;
            valid = raw.submat(0, srcH, 0, validCols);

            if (srcW == ANALYSIS_WIDTH && srcH == ANALYSIS_HEIGHT && pixelStride == 1) {
                valid.copyTo(gray);
            } else {
                Imgproc.resize(valid, gray,
                    new org.opencv.core.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                    0, 0, Imgproc.INTER_AREA);
            }
            return gray;
        } catch (Throwable t) {
            gray.release();
            return null;
        } finally {
            if (valid != null) {
                valid.release();
            }
            raw.release();
        }
    }

    /**
     * 归一化分析坐标 → 归一化预览坐标的映射。
     *
     * <p>{@code rotationDegrees} 为 CameraX 报告的顺时针旋转量，
     * 用于把传感器方向的图像转正到屏幕方向。
     */
    public static Mat3 analysisToPreview(int rotationDegrees, boolean mirrored) {
        Mat3 rot;
        switch (((rotationDegrees % 360) + 360) % 360) {
            case 90:
                // 顺时针 90°：(x, y) -> (1 - y, x)
                rot = Mat3.of(0, -1, 1,
                              1, 0, 0,
                              0, 0, 1);
                break;
            case 180:
                // 顺时针 180°：(x, y) -> (1 - x, 1 - y)
                rot = Mat3.of(-1, 0, 1,
                              0, -1, 1,
                              0, 0, 1);
                break;
            case 270:
                // 顺时针 270°：(x, y) -> (y, 1 - x)
                rot = Mat3.of(0, 1, 0,
                              -1, 0, 1,
                              0, 0, 1);
                break;
            default:
                rot = Mat3.identity();
                break;
        }
        if (mirrored) {
            // 水平镜像后与旋转复合（先旋转再镜像）
            rot = Mat3.of(-1, 0, 1, 0, 1, 0, 0, 0, 1).multiply(rot);
        }
        return rot;
    }

    /**
     * 计算 4:3 画面在 View 中以 FIT_CENTER 方式显示时的矩形。
     *
     * <p>规格 §4.2 要求预览与照片同比例，因此预览必须以完整 4:3 取景呈现，
     * 不能裁切，否则引导框与真实画面错位。
     */
    public static float[] fitCenterRect(int viewWidth, int viewHeight, double aspect) {
        if (viewWidth <= 0 || viewHeight <= 0 || aspect <= 0) {
            return new float[] {0, 0, viewWidth, viewHeight};
        }
        double viewAspect = (double) viewWidth / viewHeight;
        double w;
        double h;
        if (viewAspect > aspect) {
            h = viewHeight;
            w = h * aspect;
        } else {
            w = viewWidth;
            h = w / aspect;
        }
        float left = (float) ((viewWidth - w) / 2.0);
        float top = (float) ((viewHeight - h) / 2.0);
        return new float[] {left, top, (float) w, (float) h};
    }
}
