package com.aicamera.core;

/**
 * 对齐检测（规格 §10.1）。
 *
 * <p>只做单帧几何判定，不做时间连续性判断；时间连续性由 {@link AlignmentStabilizer} 负责。
 */
public final class AlignmentEvaluator {

    private final AlignmentConfig config;

    public AlignmentEvaluator(AlignmentConfig config) {
        this.config = config;
    }

    public AlignmentEvaluation evaluate(Quad anchorQuad, GuidanceState state, FramePacket frame) {
        if (!state.allowsAutoZoomEvaluation()) {
            return AlignmentEvaluation.invalid("STATE_NOT_LOCKED");
        }
        if (anchorQuad == null || !anchorQuad.isFinite()) {
            return AlignmentEvaluation.invalid("TRACKING_UNRELIABLE");
        }

        double visible = anchorQuad.visibleRatio();
        if (visible < config.minVisibleRatio) {
            return AlignmentEvaluation.of(visible, 0, 0, 0, 0, null, false, "TARGET_OUT_OF_VIEW");
        }

        double[] center = anchorQuad.center();
        double errX = Math.abs(center[0] - 0.5);
        double errY = Math.abs(center[1] - 0.5);
        double rotation = anchorQuad.rotationDegrees();

        double sx = anchorQuad.meanWidth();
        double sy = anchorQuad.meanHeight();
        double maxS = Math.max(sx, sy);
        double scaleErr = maxS <= 1e-9 ? 1.0 : Math.abs(sx - sy) / maxS;

        double dev = anchorQuad.maxCornerDeviationDegrees();
        boolean shapeOk = Double.isFinite(dev) && dev <= config.maxCornerDeviationDegrees;

        double aspectFactor = aspectFactor(frame);
        double[] captureRect = shapeOk
            ? anchorQuad.toAspectRect(aspectFactor, config.maxCornerDeviationDegrees, config.maxRotationDegrees)
            : null;

        if (errX > config.maxCenterErrorXRatio || errY > config.maxCenterErrorYRatio) {
            return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, captureRect, false, "NOT_CENTERED");
        }
        if (Math.abs(rotation) > config.maxRotationDegrees) {
            return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, captureRect, false, "ROTATED");
        }
        if (!shapeOk) {
            return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, null, false, "PERSPECTIVE_TOO_LARGE");
        }
        if (scaleErr > config.maxScaleInconsistency) {
            return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, captureRect, false, "ASPECT_MISMATCH");
        }
        if (captureRect == null) {
            return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, null, false, "PERSPECTIVE_TOO_LARGE");
        }
        return AlignmentEvaluation.of(visible, errX, errY, rotation, scaleErr, captureRect, true, null);
    }

    /**
     * 分析画面比例 / 输出比例的比值。
     *
     * <p>V1 强制预览、分析、照片同比例，因此该值为 1；保留参数以便未来接入不同比例输出。
     */
    private double aspectFactor(FramePacket frame) {
        if (frame == null) {
            return 1.0;
        }
        double output = config.outputAspectRatio > 0 ? config.outputAspectRatio : 4.0 / 3.0;
        return frame.aspectRatio() / output;
    }
}
