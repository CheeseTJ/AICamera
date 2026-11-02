package com.aicamera.core;

/**
 * 不可变构图锚点（规格 §8.1）。
 *
 * <p>{@code referenceQuad} 不因普通移动、重定位或用户变焦而重新定义。
 */
public final class CompositionAnchor {

    public final String anchorId;
    public final int generation;

    public final long referenceFrameId;
    public final long referenceTimestampNs;
    public final double referenceZoomRatio;

    /** 参考帧中的推荐区域四边形，8 个归一化坐标。 */
    private final double[] referenceQuad;

    public final double outputAspectRatio;

    private boolean autoZoomCompleted;

    public CompositionAnchor(
        String anchorId,
        int generation,
        long referenceFrameId,
        long referenceTimestampNs,
        double referenceZoomRatio,
        double[] referenceQuad,
        double outputAspectRatio) {
        if (referenceQuad == null || referenceQuad.length != 8) {
            throw new IllegalArgumentException("referenceQuad 必须是 8 个坐标");
        }
        this.anchorId = anchorId;
        this.generation = generation;
        this.referenceFrameId = referenceFrameId;
        this.referenceTimestampNs = referenceTimestampNs;
        this.referenceZoomRatio = referenceZoomRatio;
        this.referenceQuad = referenceQuad.clone();
        this.outputAspectRatio = outputAspectRatio;
        this.autoZoomCompleted = false;
    }

    public Quad referenceQuad() {
        return Quad.of(referenceQuad);
    }

    public double[] referenceQuadArray() {
        return referenceQuad.clone();
    }

    public boolean isAutoZoomCompleted() {
        return autoZoomCompleted;
    }

    /** 每个锚点只允许一次成功自动变焦（§10.5）。 */
    public void markAutoZoomCompleted() {
        this.autoZoomCompleted = true;
    }
}
