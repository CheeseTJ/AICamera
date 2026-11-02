package com.aicamera.core;

/** 单帧元数据（规格 §5.2）。 */
public final class FramePacket {

    public final String sessionId;
    public final long frameId;
    public final long timestampNs;
    /** 分析坐标下的宽（已按最终取景方向归一）。 */
    public final int width;
    /** 分析坐标下的高（已按最终取景方向归一）。 */
    public final int height;
    /** 分析图相对屏幕的旋转角，仅用于预览坐标映射。 */
    public final int rotationDegrees;
    public final double actualZoomRatio;
    /** 分析图 → 预览 UI 的 3x3 矩阵；可为 null。 */
    public final Mat3 analysisToPreview;

    public FramePacket(
        String sessionId,
        long frameId,
        long timestampNs,
        int width,
        int height,
        int rotationDegrees,
        double actualZoomRatio,
        Mat3 analysisToPreview) {
        this.sessionId = sessionId;
        this.frameId = frameId;
        this.timestampNs = timestampNs;
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
        this.actualZoomRatio = actualZoomRatio;
        this.analysisToPreview = analysisToPreview;
    }

    /**
     * 分析坐标下的宽高比。
     *
     * <p>按规格 §4.2，width/height 已经表达旋转后的最终取景比例；
     * {@link #rotationDegrees} 只用于把分析坐标映射到预览 UI，不参与比例计算。
     */
    public double aspectRatio() {
        if (height <= 0) {
            return 4.0 / 3.0;
        }
        return (double) width / height;
    }
}
