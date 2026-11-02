package com.aicamera.core;

/** 引导状态（规格 §12）。 */
public enum GuidanceState {
    IDLE,
    SEARCHING,
    LOCKED,
    DEGRADED,
    LOST,
    RELOCALIZING,
    AUTO_ZOOMING,
    VERIFYING;

    /** 只有 LOCKED 才允许评估自动变焦（§10.1）。 */
    public boolean allowsAutoZoomEvaluation() {
        return this == LOCKED;
    }
}
