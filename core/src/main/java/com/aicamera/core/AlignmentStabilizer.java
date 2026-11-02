package com.aicamera.core;

/**
 * 时间连续性闸门（规格 §10.1）：条件连续满足 ≥600 ms 且 ≥10 帧才放行。
 *
 * <p>一旦某帧不满足，计数立刻归零，避免抖动误触发。
 */
public final class AlignmentStabilizer {

    private final AlignmentConfig config;

    private long firstSatisfiedTimestampNs = -1;
    private int consecutiveSamples;
    private long lastTimestampNs = -1;

    public AlignmentStabilizer(AlignmentConfig config) {
        this.config = config;
    }

    public void reset() {
        firstSatisfiedTimestampNs = -1;
        consecutiveSamples = 0;
        lastTimestampNs = -1;
    }

    public int consecutiveSamples() {
        return consecutiveSamples;
    }

    /** 连续满足时长（纳秒）；未开始时为 0。 */
    public long heldNanos() {
        if (firstSatisfiedTimestampNs < 0 || lastTimestampNs < 0) {
            return 0;
        }
        return lastTimestampNs - firstSatisfiedTimestampNs;
    }

    /**
     * 记录一次评估结果。
     *
     * @return true 表示此前置条件已满足，可用于触发自动变焦
     */
    public boolean update(AlignmentEvaluation evaluation, long timestampNs) {
        if (!evaluation.alignmentReady) {
            reset();
            return false;
        }
        if (firstSatisfiedTimestampNs < 0) {
            firstSatisfiedTimestampNs = timestampNs;
            consecutiveSamples = 1;
            lastTimestampNs = timestampNs;
            return false;
        }
        lastTimestampNs = timestampNs;
        consecutiveSamples++;
        return consecutiveSamples >= config.minStableSamples
            && heldNanos() >= config.stableHoldNanos;
    }
}
