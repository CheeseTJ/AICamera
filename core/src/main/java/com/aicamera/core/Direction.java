package com.aicamera.core;

/** 方向提示（规格 §9.2）。只输出转镜方向，不输出空间位移建议。 */
public enum Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN;

    /**
     * 依据目标中心在画面中的位置给出转向提示。
     *
     * @param maxCenterError 死区内不给提示（占画幅比例）
     */
    public static Direction fromCenter(double cx, double cy, double maxCenterError) {
        double dx = cx - 0.5;
        double dy = cy - 0.5;
        if (Math.abs(dx) < maxCenterError && Math.abs(dy) < maxCenterError) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dy)) {
            return dx > 0 ? RIGHT : LEFT;
        }
        return dy > 0 ? DOWN : UP;
    }

    public String hintText() {
        switch (this) {
            case LEFT:
                return "向左转动镜头";
            case RIGHT:
                return "向右转动镜头";
            case UP:
                return "抬高镜头";
            case DOWN:
                return "压低镜头";
            default:
                return "";
        }
    }
}
