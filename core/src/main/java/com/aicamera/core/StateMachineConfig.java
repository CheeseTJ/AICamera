package com.aicamera.core;

/** 状态机时间阈值（规格 §12），作为配置而非散落硬编码。 */
public final class StateMachineConfig {

    /** 连续低质量多久后进入 DEGRADED。 */
    public long degradedAfterNanos = 100_000_000L;
    /** 质量低于此值判定为低质量。 */
    public double degradedQualityThreshold = 0.35;
    /** 无法可靠定位持续多久后进入 LOST。 */
    public long lostAfterNanos = 300_000_000L;
    /** 恢复 LOCKED 需要的连续合格帧数。 */
    public int relockConsecutiveFrames = 3;
    /** 恢复判定使用的最低质量。 */
    public double relockQualityThreshold = 0.55;
}
