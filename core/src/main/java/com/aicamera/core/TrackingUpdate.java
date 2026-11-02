package com.aicamera.core;

/** 跟踪结果（由 vision 层产生，core 层消费）。 */
public final class TrackingUpdate {

    public final long frameId;
    public final long timestampNs;
    public final int generation;

    /** 当前帧中的原锚点四边形；无法定位时为 null。 */
    public final Quad quad;

    /** 跟踪质量评分 [0,1]，非概率。 */
    public final double quality;

    public final String reasonCode;

    public TrackingUpdate(long frameId, long timestampNs, int generation, Quad quad, double quality, String reasonCode) {
        this.frameId = frameId;
        this.timestampNs = timestampNs;
        this.generation = generation;
        this.quad = quad;
        this.quality = quality;
        this.reasonCode = reasonCode;
    }

    public static TrackingUpdate lost(long frameId, long timestampNs, int generation, String reasonCode) {
        return new TrackingUpdate(frameId, timestampNs, generation, null, 0.0, reasonCode);
    }

    public boolean hasPosition() {
        return quad != null && quad.isFinite();
    }
}
