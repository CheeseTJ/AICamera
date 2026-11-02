package com.aicamera.core;

/** 对齐与自动变焦阈值（规格 §10.1），全部可配置，不散落硬编码。 */
public final class AlignmentConfig {

    public double minVisibleRatio = 0.98;
    /** 中心水平误差上限（占画面宽度比例）。 */
    public double maxCenterErrorXRatio = 0.02;
    /** 中心垂直误差上限（占画面高度比例）。 */
    public double maxCenterErrorYRatio = 0.02;
    public double maxRotationDegrees = 2.0;
    /** 宽高缩放一致性误差上限。 */
    public double maxScaleInconsistency = 0.03;
    /** 条件需要连续满足的时长。 */
    public long stableHoldNanos = 600_000_000L;
    /** 条件需要连续满足的最小有效帧数。 */
    public int minStableSamples = 10;
    /** 四边形近似矩形的容差。 */
    public double maxCornerDeviationDegrees = 12.0;
    /**
     * 自动变焦倍率上限。
     *
     * <p>默认 5.0；运行时会被设备实际能力裁剪（见 GuidanceController#setMaxAutoZoomRatio），
     * 即取 min(设备上限, 本值)。超出上限时明确提示，不做部分放大。
     */
    public double maxAutoZoomRatio = 5.0;
    /** 触发尺度判定的最小可接受面积占比（防止过度裁剪）。 */
    public double minTargetAreaRatio = 0.08;
    /** 输出照片宽高比；V1 预览／分析／照片同比例。 */
    public double outputAspectRatio = 4.0 / 3.0;
}
