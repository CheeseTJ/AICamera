package com.aicamera.core;

/**
 * 引导会话控制器：状态机 + 对齐闸门 + 变焦仲裁（规格 §10 / §11 / §12）。
 *
 * <p>该类不依赖 Android / OpenCV，可在桌面端完整单测。
 * 所有时间参数由调用方传入（纳秒），便于构造确定性测试序列。
 */
public final class GuidanceController {

    /** 变焦完成判定容差。 */
    private static final double ZOOM_REACHED_TOLERANCE = 0.05;
    /** 变焦命令建议时长。 */
    private static final long ZOOM_DURATION_MS = 800L;
    /** 变焦最长等待时长；超时则按当前实际倍率进入验证，避免卡在 AUTO_ZOOMING。 */
    private static final long ZOOM_TIMEOUT_NANOS = 2_500_000_000L;
    /** 手动变焦结束后需要的稳定时长（§11.1）。 */
    private static final long MANUAL_ZOOM_SETTLE_NANOS = 1_000_000_000L;
    /** 变焦完成后验证需要的连续合格帧。 */
    private static final int VERIFY_FRAMES = 3;

    private final String sessionId;
    private final AlignmentConfig alignmentConfig;
    private final StateMachineConfig stateConfig;
    private final AlignmentEvaluator evaluator;
    private final AlignmentStabilizer stabilizer;

    private GuidanceListener listener = new GuidanceListener() {};

    private GuidanceState state = GuidanceState.IDLE;
    private CompositionAnchor anchor;
    private int generation;

    private Quad lastQuad;
    private double lastQuality;
    private double lastActualZoom = 1.0;

    private long lowQualitySince = -1;
    private long noPositionSince = -1;
    private int recoveryStreak;

    private boolean manualZoomActive;
    private long manualZoomEndedAt = -1;

    private double pendingTargetZoom = Double.NaN;
    /** 进入 AUTO_ZOOMING 的时间，用于超时保护。 */
    private long autoZoomStartedAt = -1;
    private int verifyStreak;
    private double lastCaptureCoverage;

    private GuidanceResult lastResult = new GuidanceResult();

    public GuidanceController(String sessionId, AlignmentConfig alignmentConfig, StateMachineConfig stateConfig) {
        this.sessionId = sessionId;
        this.alignmentConfig = alignmentConfig;
        this.stateConfig = stateConfig;
        this.evaluator = new AlignmentEvaluator(alignmentConfig);
        this.stabilizer = new AlignmentStabilizer(alignmentConfig);
        this.lastResult.sessionId = sessionId;
    }

    public void setListener(GuidanceListener listener) {
        this.listener = listener == null ? new GuidanceListener() {} : listener;
    }

    public GuidanceState state() {
        return state;
    }

    public CompositionAnchor anchor() {
        return anchor;
    }

    public String sessionId() {
        return sessionId;
    }

    public AlignmentConfig alignmentConfig() {
        return alignmentConfig;
    }

    /**
     * 用设备实际支持的最大倍率裁剪自动变焦上限（规格 §4.3）。
     *
     * <p>取 min(配置上限, 设备上限)：配置给出期望上限，设备能力是不可逾越的边界。
     * 设备不支持变焦时传 ≤1 的值，方法不做修改，由调用方明确提示。
     *
     * @param deviceMaxZoomRatio 设备报告的最大变焦倍率
     */
    public void setDeviceMaxZoomRatio(double deviceMaxZoomRatio) {
        if (!(deviceMaxZoomRatio > 1.0)) {
            return;
        }
        this.alignmentConfig.maxAutoZoomRatio =
            Math.min(this.alignmentConfig.maxAutoZoomRatio, deviceMaxZoomRatio);
    }

    // ---------------------------------------------------------------- 会话控制

    /** 用户点击“构图引导”或“重新构图”：作废旧锚点并进入 SEARCHING。 */
    public void startSearching() {
        generation++;
        anchor = null;
        lastQuad = null;
        lowQualitySince = -1;
        noPositionSince = -1;
        recoveryStreak = 0;
        pendingTargetZoom = Double.NaN;
        verifyStreak = 0;
        manualZoomActive = false;
        manualZoomEndedAt = -1;
        stabilizer.reset();
        transition(GuidanceState.SEARCHING, null);
    }

    /** 搜索完成后锁定推荐构图。锚点不可变，只有再次 startSearching 才换目标。 */
    public void lockAnchor(CompositionAnchor candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("anchor 不能为空");
        }
        if (state != GuidanceState.SEARCHING && state != GuidanceState.IDLE) {
            return;
        }
        this.anchor = candidate;
        this.generation = candidate.generation;
        this.lastQuad = candidate.referenceQuad();
        this.lastQuality = 1.0;
        this.lowQualitySince = -1;
        this.noPositionSince = -1;
        this.recoveryStreak = 0;
        this.stabilizer.reset();
        transition(GuidanceState.LOCKED, null);
    }

    /** 结束会话（例如退出构图引导）。 */
    public void stop() {
        generation++;
        anchor = null;
        lastQuad = null;
        stabilizer.reset();
        transition(GuidanceState.IDLE, null);
    }

    // ---------------------------------------------------------------- 跟踪输入

    public GuidanceResult onTrackingUpdate(FramePacket frame, TrackingUpdate update) {
        if (frame != null) {
            lastActualZoom = frame.actualZoomRatio;
        }
        // 时间同步：会话或锚点版本不一致一律丢弃（§5.3）
        if (frame != null && !sessionId.equals(frame.sessionId)) {
            return staleResult(frame, "SESSION_MISMATCH");
        }
        if (update == null) {
            return buildResult(frame);
        }
        if (update.generation != generation) {
            return staleResult(frame, "ANCHOR_GENERATION_MISMATCH");
        }
        if (state == GuidanceState.IDLE || state == GuidanceState.SEARCHING) {
            return buildResult(frame);
        }

        lastQuality = update.quality;

        boolean positioned = update.hasPosition();
        if (positioned) {
            lastQuad = update.quad;
            noPositionSince = -1;
        } else if (noPositionSince < 0) {
            noPositionSince = update.timestampNs;
        }

        boolean lowQuality = !positioned || update.quality < stateConfig.degradedQualityThreshold;
        if (lowQuality) {
            recoveryStreak = 0;
            if (lowQualitySince < 0) {
                lowQualitySince = update.timestampNs;
            }
        } else {
            lowQualitySince = -1;
            recoveryStreak++;
        }

        applyStateTransitions(update, lowQuality, positioned);
        if (state == GuidanceState.LOCKED || state == GuidanceState.AUTO_ZOOMING || state == GuidanceState.VERIFYING) {
            evaluateAutoZoom(frame, update);
        }
        return buildResult(frame);
    }

    private void applyStateTransitions(TrackingUpdate update, boolean lowQuality, boolean positioned) {
        switch (state) {
            case LOCKED:
                if (lowQuality && update.timestampNs - lowQualitySince >= stateConfig.degradedAfterNanos) {
                    transition(GuidanceState.DEGRADED, update.reasonCode);
                    stabilizer.reset();
                }
                break;
            case DEGRADED:
                if (canRecover(update, lowQuality)) {
                    transition(GuidanceState.LOCKED, null);
                } else if (!positioned && update.timestampNs - noPositionSince >= stateConfig.lostAfterNanos) {
                    transition(GuidanceState.LOST, update.reasonCode);
                }
                break;
            case LOST:
                transition(GuidanceState.RELOCALIZING, update.reasonCode);
                recoveryStreak = 0;
                break;
            case RELOCALIZING:
                if (canRecover(update, lowQuality)) {
                    transition(GuidanceState.LOCKED, null);
                } else if (positioned) {
                    // 仍有位置但质量不足，继续重定位
                }
                break;
            case AUTO_ZOOMING:
                onAutoZoomFrame(update);
                break;
            case VERIFYING:
                onVerifyingFrame(update, lowQuality);
                break;
            default:
                break;
        }
    }

    private boolean canRecover(TrackingUpdate update, boolean lowQuality) {
        return !lowQuality
            && update.hasPosition()
            && update.quality >= stateConfig.relockQualityThreshold
            && recoveryStreak >= stateConfig.relockConsecutiveFrames;
    }

    // ---------------------------------------------------------------- 自动变焦

    private void evaluateAutoZoom(FramePacket frame, TrackingUpdate update) {
        if (state != GuidanceState.LOCKED) {
            return;
        }
        if (manualZoomActive) {
            stabilizer.reset();
            return;
        }
        if (manualZoomEndedAt > 0 && update.timestampNs - manualZoomEndedAt < MANUAL_ZOOM_SETTLE_NANOS) {
            stabilizer.reset();
            return;
        }
        if (anchor == null || anchor.isAutoZoomCompleted()) {
            stabilizer.reset();
            return;
        }
        AlignmentEvaluation evaluation = evaluator.evaluate(lastQuad, state, frame);
        if (!stabilizer.update(evaluation, update.timestampNs)) {
            return;
        }
        if (evaluation.captureRect == null) {
            return;
        }
        double target = ZoomMath.targetZoomRatio(lastActualZoom, evaluation.captureRect);
        if (!ZoomMath.isZoomWithinLimit(lastActualZoom, target, alignmentConfig.maxAutoZoomRatio)) {
            lastResult.reasonCode = target <= lastActualZoom + 1e-3 ? "NO_BETTER_COMPOSITION" : "ZOOM_RANGE_EXCEEDED";
            return;
        }
        double areaRatio = (evaluation.captureRect[2] - evaluation.captureRect[0])
            * (evaluation.captureRect[3] - evaluation.captureRect[1]);
        if (areaRatio > 1.0 - 1e-3) {
            lastResult.reasonCode = "NO_BETTER_COMPOSITION";
            return;
        }
        pendingTargetZoom = target;
        verifyStreak = 0;
        autoZoomStartedAt = update.timestampNs;
        transition(GuidanceState.AUTO_ZOOMING, null);
        listener.onRequestZoom(target, ZOOM_DURATION_MS);
    }

    private void onAutoZoomFrame(TrackingUpdate update) {
        if (manualZoomActive) {
            cancelAutoZoom("MANUAL_ZOOM_OVERRIDE");
            return;
        }
        if (!update.hasPosition()) {
            cancelAutoZoom("TRACKING_UNRELIABLE");
            return;
        }
        if (update.quality < stateConfig.degradedQualityThreshold) {
            cancelAutoZoom("TRACKING_UNRELIABLE");
            return;
        }
        boolean reached = Math.abs(lastActualZoom - pendingTargetZoom)
            <= ZOOM_REACHED_TOLERANCE * pendingTargetZoom;
        boolean timedOut = autoZoomStartedAt > 0
            && update.timestampNs - autoZoomStartedAt >= ZOOM_TIMEOUT_NANOS;
        if (reached || timedOut) {
            // 超时时按当前实际倍率验证，不继续无限等待也不要无限放大
            verifyStreak = 0;
            transition(GuidanceState.VERIFYING, null);
        }
    }

    private void onVerifyingFrame(TrackingUpdate update, boolean lowQuality) {
        if (!update.hasPosition() || lowQuality) {
            cancelAutoZoom("TRACKING_UNRELIABLE");
            return;
        }
        Quad quad = update.quad;
        double visible = quad.visibleRatio();
        double coverage = quad.area();
        double[] center = quad.center();
        // 变焦后目标区域应接近铺满画面，因此宽容度取对齐阈值的 4 倍
        boolean centered = Math.abs(center[0] - 0.5) <= alignmentConfig.maxCenterErrorXRatio * 4
            && Math.abs(center[1] - 0.5) <= alignmentConfig.maxCenterErrorYRatio * 4;
        double targetCoverage = pendingTargetZoom > 1e-6
            ? Math.max(0.5, 1.0 / (pendingTargetZoom * pendingTargetZoom))
            : 0.5;
        boolean sizeOk = coverage >= targetCoverage * 0.6;
        boolean ok = visible >= alignmentConfig.minVisibleRatio * 0.98 && centered && sizeOk;
        if (!ok) {
            cancelAutoZoom("VERIFY_FAILED");
            return;
        }
        verifyStreak++;
        lastCaptureCoverage = coverage;
        if (verifyStreak >= VERIFY_FRAMES) {
            anchor.markAutoZoomCompleted();
            transition(GuidanceState.LOCKED, null);
        }
    }

    private void cancelAutoZoom(String reasonCode) {
        if (state != GuidanceState.AUTO_ZOOMING && state != GuidanceState.VERIFYING) {
            return;
        }
        pendingTargetZoom = Double.NaN;
        verifyStreak = 0;
        listener.onCancelZoom();
        transition(GuidanceState.DEGRADED, reasonCode);
        stabilizer.reset();
        recoveryStreak = 0;
    }

    // ---------------------------------------------------------------- 用户仲裁

    /** 用户开始手动变焦：立即停止自动变焦，绝不与手势抢控制权（§11.1）。 */
    public void onManualZoomStart(long timestampNs) {
        manualZoomActive = true;
        stabilizer.reset();
        if (state == GuidanceState.AUTO_ZOOMING || state == GuidanceState.VERIFYING) {
            pendingTargetZoom = Double.NaN;
            verifyStreak = 0;
            listener.onCancelZoom();
            transition(GuidanceState.DEGRADED, "MANUAL_ZOOM_OVERRIDE");
        }
    }

    /** 用户结束手动变焦：需要 1 秒稳定后才重新评估对齐。 */
    public void onManualZoomEnd(long timestampNs) {
        manualZoomActive = false;
        manualZoomEndedAt = timestampNs;
        stabilizer.reset();
    }

    public boolean isManualZoomActive() {
        return manualZoomActive;
    }

    /**
     * 用户按快门：任何状态都允许拍摄；先停掉后续变焦请求（§11.2）。
     *
     * @return 是否允许拍摄（V1 恒为 true）
     */
    public boolean onShutter() {
        listener.onCancelZoom();
        if (state == GuidanceState.AUTO_ZOOMING || state == GuidanceState.VERIFYING) {
            pendingTargetZoom = Double.NaN;
            verifyStreak = 0;
            transition(GuidanceState.LOCKED, "SHUTTER_DURING_ZOOM");
        }
        // 拍照后引导继续，锚点不变（§11.2）
        return true;
    }

    // ---------------------------------------------------------------- 结果构建

    /** 分析画面比例 / 输出比例；V1 两者相同，恒为 1（规格 §4.2）。 */
    private double aspectFactor(FramePacket frame) {
        if (frame == null) {
            return 1.0;
        }
        double output = alignmentConfig.outputAspectRatio > 0 ? alignmentConfig.outputAspectRatio : 4.0 / 3.0;
        return frame.aspectRatio() / output;
    }

    private GuidanceResult staleResult(FramePacket frame, String reasonCode) {
        GuidanceResult r = buildResult(frame);
        r.reasonCode = reasonCode;
        return r;
    }

    private GuidanceResult buildResult(FramePacket frame) {
        GuidanceResult r = new GuidanceResult();
        r.sessionId = sessionId;
        r.state = state;
        r.generation = generation;
        r.anchorId = anchor == null ? null : anchor.anchorId;
        r.trackingQuality = lastQuality;
        r.actualZoomRatio = lastActualZoom;
        r.autoZoomCompleted = anchor != null && anchor.isAutoZoomCompleted();
        if (anchor != null) {
            r.targetZoomRatio = Double.isNaN(pendingTargetZoom) ? r.targetZoomRatio : pendingTargetZoom;
        }
        if (frame != null) {
            r.frameId = frame.frameId;
            r.timestampNs = frame.timestampNs;
        }
        if (lastQuad != null) {
            r.anchorQuad = lastQuad.toArray();
            r.visibleRatio = lastQuad.visibleRatio();
            Quad display = lastQuad.clippedForDisplay();
            r.displayQuad = display == null ? null : display.toArray();
            double[] center = lastQuad.center();

            // 对齐引导所需的误差量：目标中心相对画面中心的偏移
            r.alignmentErrorX = center[0] - 0.5;
            r.alignmentErrorY = center[1] - 0.5;
            r.alignmentRotationDegrees = lastQuad.rotationDegrees();
            r.alignmentProgress = stabilizer.consecutiveSamples() > 0
                ? Math.min(1.0, (double) stabilizer.consecutiveSamples() / alignmentConfig.minStableSamples)
                : 0.0;
            if (state == GuidanceState.LOCKED) {
                AlignmentEvaluation ev = evaluator.evaluate(lastQuad, state, frame);
                r.alignmentReady = ev.alignmentReady;
                if (ev.reasonCode != null && r.reasonCode == null) {
                    r.reasonCode = ev.reasonCode;
                }
            }

            r.direction = Direction.fromCenter(center[0], center[1], alignmentConfig.maxCenterErrorXRatio);
            // 自动变焦完成后目标本就铺满画面，边界处会出现极小出画量，
            // 此时不应再提示"部分出画"，否则与"构图已对齐"自相矛盾。
            boolean fullyFramed = r.autoZoomCompleted
                && r.visibleRatio >= alignmentConfig.minVisibleRatio * 0.97;
            r.directionValid = state == GuidanceState.LOCKED
                && (r.visibleRatio >= alignmentConfig.minVisibleRatio || fullyFramed)
                && lastQuality >= stateConfig.degradedQualityThreshold;
            if (fullyFramed && state == GuidanceState.LOCKED && r.reasonCode == null) {
                r.reasonCode = null;
            }
            if (!r.directionValid) {
                r.direction = null;
            }
            double[] rect = lastQuad.toAspectRect(
                aspectFactor(frame),
                alignmentConfig.maxCornerDeviationDegrees,
                alignmentConfig.maxRotationDegrees);
            r.captureRect = rect;
            r.captureRectValid = rect != null;
        } else {
            r.visibleRatio = 0.0;
            r.captureRectValid = false;
        }
        if (r.reasonCode == null) {
            r.reasonCode = lastResult.reasonCode;
        }
        lastResult = r;
        return r;
    }

    private void transition(GuidanceState next, String reasonCode) {
        GuidanceState old = state;
        if (old == next) {
            return;
        }
        state = next;
        listener.onStateChanged(old, next, reasonCode);
    }
}
