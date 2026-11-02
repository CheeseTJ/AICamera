package com.aicamera.core;

/** 引导输出（规格 §13）。 */
public final class GuidanceResult {

    public String sessionId;
    public String anchorId;
    public int generation;

    public long frameId;
    public long timestampNs;

    public GuidanceState state = GuidanceState.IDLE;
    public double trackingQuality;

    /** 4x2 归一化坐标，允许出画；无锚点时 null。 */
    public double[] anchorQuad;
    /** 显示用框（已裁切到画幅）；无则不显示。 */
    public double[] displayQuad;
    public double[] captureRect;
    public boolean captureRectValid;

    public double visibleRatio = 1.0;

    public Direction direction;
    public boolean directionValid;

    public double actualZoomRatio;
    public double targetZoomRatio = Double.NaN;

    public boolean alignmentReady;
    /** 中心对齐误差（归一化）：>0 表示目标位于画面右侧/下方。 */
    public double alignmentErrorX;
    public double alignmentErrorY;
    /** 当前旋转误差（度）。 */
    public double alignmentRotationDegrees;
    /** 自动变焦完成进度 [0,1]，用于引导线强度。 */
    public double alignmentProgress;
    public boolean autoZoomCompleted;

    public String reasonCode;

    @Override
    public String toString() {
        return "GuidanceResult{state=" + state
            + ", q=" + (trackingQuality)
            + ", visible=" + visibleRatio
            + ", dir=" + (directionValid ? direction : "INVALID")
            + ", zoom=" + actualZoomRatio + (Double.isNaN(targetZoomRatio) ? "" : "→" + targetZoomRatio)
            + ", ready=" + alignmentReady
            + ", alignErr=(" + alignmentErrorX + "," + alignmentErrorY + ")"
            + (reasonCode == null ? "" : ", reason=" + reasonCode)
            + "}";
    }
}
