package com.aicamera.core;

/** 控制器对外副作用接口，便于在单测中记录呼叫而不触碰相机。 */
public interface GuidanceListener {

    /** 请求相机把倍率平滑推到 ratio，建议持续 durationMs。 */
    default void onRequestZoom(double ratio, long durationMs) {
    }

    /** 请求取消尚未完成的变焦（用户接管或按快门）。 */
    default void onCancelZoom() {
    }

    /** 状态或提示变化。 */
    default void onStateChanged(GuidanceState oldState, GuidanceState newState, String reasonCode) {
    }
}
