package com.aicamera.core;

import java.util.Locale;

/** 单帧对齐判定结果。 */
public final class AlignmentEvaluation {

    public final boolean trackingLocked;
    public final double visibleRatio;

    public final double centerErrorX;
    public final double centerErrorY;
    public final double rotationDegrees;
    /** (sx - sy) / max(sx, sy)。 */
    public final double scaleInconsistency;
    /** 由四边形推出的、与输出比例一致的候选矩形；无效时为 null。 */
    public final double[] captureRect;

    public final boolean alignmentReady;
    public final String reasonCode;

    private AlignmentEvaluation(
        boolean trackingLocked,
        double visibleRatio,
        double centerErrorX,
        double centerErrorY,
        double rotationDegrees,
        double scaleInconsistency,
        double[] captureRect,
        boolean alignmentReady,
        String reasonCode) {
        this.trackingLocked = trackingLocked;
        this.visibleRatio = visibleRatio;
        this.centerErrorX = centerErrorX;
        this.centerErrorY = centerErrorY;
        this.rotationDegrees = rotationDegrees;
        this.scaleInconsistency = scaleInconsistency;
        this.captureRect = captureRect;
        this.alignmentReady = alignmentReady;
        this.reasonCode = reasonCode;
    }

    static AlignmentEvaluation invalid(String reasonCode) {
        return new AlignmentEvaluation(false, 0, 0, 0, 0, 0, null, false, reasonCode);
    }

    static AlignmentEvaluation of(
        double visibleRatio,
        double centerErrorX,
        double centerErrorY,
        double rotationDegrees,
        double scaleInconsistency,
        double[] captureRect,
        boolean ready,
        String reasonCode) {
        return new AlignmentEvaluation(
            true, visibleRatio, centerErrorX, centerErrorY, rotationDegrees, scaleInconsistency, captureRect, ready, reasonCode);
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
            "Alignment{ready=%s, vis=%.3f, dx=%.4f, dy=%.4f, rot=%.2f°, scaleErr=%.4f, reason=%s}",
            alignmentReady, visibleRatio, centerErrorX, centerErrorY, rotationDegrees, scaleInconsistency, reasonCode);
    }
}
