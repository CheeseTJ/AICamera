package com.aicamera.vision;

/**
 * 帧间稳定度评估：判断"相机是否已经稳定"。
 *
 * <p>用于在用户点击锁定按钮前确认画面稳定，避免把运动模糊帧当成参考帧。
 * 该判断基于连续帧之间的全局运动量与画面平均亮度变化，不依赖任何模型。
 */
public final class StabilityMonitor {

    /** 稳定判定阈值。 */
    public static final class Config {
        /** 相邻帧允许的最大全局平移（像素）。手持微抖通常在 1px 量级。 */
        public double maxTranslationPx = 2.5;
        /** 相邻帧允许的最大缩放变化。 */
        public double maxScaleDelta = 0.02;
        /** 相邻帧允许的最大旋转（度）。 */
        public double maxRotationDegDelta = 0.8;
        /** 允许的平均亮度变化（0-255）。 */
        public double maxMeanLumaDelta = 10.0;
        /** 需要连续稳定的帧数。 */
        public int requiredStableFrames = 8;
    }

    private final Config config;
    private int stableFrames;
    private double lastMetric = Double.NaN;

    public StabilityMonitor(Config config) {
        this.config = config == null ? new Config() : config;
    }

    public Config config() {
        return config;
    }

    public int stableFrames() {
        return stableFrames;
    }

    public double lastMetric() {
        return lastMetric;
    }

    public boolean isStable() {
        return stableFrames >= config.requiredStableFrames;
    }

    public void reset() {
        stableFrames = 0;
        lastMetric = Double.NaN;
    }

    /**
     * 用一帧的全局运动指标推进稳定性计数。
     *
     * @param translationPx  全局平移量（像素）
     * @param scaleDelta     尺度变化绝对值
     * @param rotationDelta  旋转变化（度，绝对值）
     * @param meanLumaDelta  平均亮度变化
     */
    public boolean update(double translationPx, double scaleDelta, double rotationDelta, double meanLumaDelta) {
        lastMetric = Math.max(translationPx / Math.max(1e-6, config.maxTranslationPx),
            Math.max(scaleDelta / Math.max(1e-6, config.maxScaleDelta),
                Math.max(rotationDelta / Math.max(1e-6, config.maxRotationDegDelta),
                    meanLumaDelta / Math.max(1e-6, config.maxMeanLumaDelta))));

        boolean ok = translationPx <= config.maxTranslationPx
            && scaleDelta <= config.maxScaleDelta
            && rotationDelta <= config.maxRotationDegDelta
            && meanLumaDelta <= config.maxMeanLumaDelta;
        if (ok) {
            stableFrames++;
        } else {
            stableFrames = 0;
        }
        return isStable();
    }

    /** 稳定进度 [0,1]，用于 UI 提示。 */
    public double progress() {
        if (config.requiredStableFrames <= 0) {
            return 1.0;
        }
        return Math.min(1.0, stableFrames / (double) config.requiredStableFrames);
    }
}
