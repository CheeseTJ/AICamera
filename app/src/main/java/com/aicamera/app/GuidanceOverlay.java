package com.aicamera.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import com.aicamera.core.Direction;
import com.aicamera.core.GuidanceResult;
import com.aicamera.core.GuidanceState;
import com.aicamera.core.Mat3;

/**
 * 引导绘制层（规格 §9.2 / §12）。
 *
 * <p>坐标链路：归一化分析坐标 --(analysisToPreview)--> 归一化预览坐标 --> View 像素。
 * 必须经过映射，否则竖屏下框会与实际预览位置错位。
 */
public final class GuidanceOverlay extends View {

    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 对齐引导线专用画笔
    private final Paint guideLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guideArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase;

    private final Path path = new Path();

    private GuidanceResult result;
    private Mat3 analysisToPreview = Mat3.identity();
    private boolean showDebug;
    /** 预览画面在 View 中的实际显示矩形：left, top, width, height。 */
    private float[] displayRect;
    private double outputAspect = 4.0 / 3.0;

    public GuidanceOverlay(android.content.Context context) {
        this(context, null);
    }

    public GuidanceOverlay(android.content.Context context, AttributeSet attrs) {
        super(context, attrs);
        solidPaint.setStyle(Paint.Style.STROKE);
        solidPaint.setStrokeWidth(5f);
        solidPaint.setColor(Color.rgb(120, 255, 160));

        dashedPaint.setStyle(Paint.Style.STROKE);
        dashedPaint.setStrokeWidth(5f);
        dashedPaint.setColor(Color.rgb(255, 210, 90));
        dashedPaint.setPathEffect(new DashPathEffect(new float[] {16f, 12f}, 0f));

        fadedPaint.setStyle(Paint.Style.STROKE);
        fadedPaint.setStrokeWidth(3f);
        fadedPaint.setColor(Color.argb(110, 255, 255, 255));

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(Color.rgb(255, 255, 255));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(38f);
        textPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK);

        debugPaint.setColor(Color.rgb(90, 200, 255));
        debugPaint.setTextSize(26f);

        guideLinePaint.setStyle(Paint.Style.STROKE);
        guideRingPaint.setStyle(Paint.Style.STROKE);
        guideArrowPaint.setStyle(Paint.Style.FILL);
        targetPaint.setStyle(Paint.Style.FILL);
    }

    /** 设置分析坐标 → 预览坐标的映射（必须与相机 targetRotation 一致）。 */
    public void setAnalysisToPreview(Mat3 matrix) {
        this.analysisToPreview = matrix == null ? Mat3.identity() : matrix;
        invalidate();
    }

    /**
     * 设置画面比例与旋转。
     *
     * <p>外层使用 {@link AspectFrameLayout} 固定容器比例，视频精确填满容器，
     * 因此这里不需要计算 letterbox，显示矩形就是整个 View。
     */
    public void setOutputFrame(double aspect, int rotationDegrees) {
        int deg = ((rotationDegrees % 360) + 360) % 360;
        boolean rotated = deg == 90 || deg == 270;
        this.outputAspect = rotated ? (1.0 / aspect) : aspect;
        this.displayRect = null;
        invalidate();
    }

    public void setResult(GuidanceResult result) {
        this.result = result;
        invalidate();
        // 引导线使用虚线动画，需要持续重绘；仅在需要引导时保持节奏
        if (result != null && result.displayQuad != null && !result.autoZoomCompleted
            && (result.state == GuidanceState.LOCKED
                || result.state == GuidanceState.AUTO_ZOOMING
                || result.state == GuidanceState.VERIFYING)) {
            postInvalidateOnAnimation();
        }
    }

    public void setShowDebug(boolean showDebug) {
        this.showDebug = showDebug;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null) {
            return;
        }
        GuidanceState state = result.state;

        if (state == GuidanceState.SEARCHING) {
            canvas.drawText("正在识别主体…", 40, 110, textPaint);
            return;
        }

        double[] quad = result.displayQuad;
        if (quad != null && state != GuidanceState.LOST) {
            Paint paint;
            if (state == GuidanceState.LOCKED) {
                paint = solidPaint;
            } else if (state == GuidanceState.DEGRADED || state == GuidanceState.RELOCALIZING) {
                paint = dashedPaint;
            } else {
                paint = fadedPaint;
            }
            drawQuad(canvas, quad, paint);
        }

        if (state == GuidanceState.LOST) {
            canvas.drawText("正在找回原构图…", 40, 110, textPaint);
        }

        // 对齐引导线：锁定后把目标中心牵向画面中心（规格 §2.3 / §10.1）
        if ((state == GuidanceState.LOCKED || state == GuidanceState.AUTO_ZOOMING
            || state == GuidanceState.VERIFYING)
            && !result.autoZoomCompleted
            && result.displayQuad != null) {
            drawAlignmentGuide(canvas);
        }

        if (result.directionValid && result.direction != null) {
            drawDirectionHint(canvas, result.direction);
        }

        float statusY = 110f;
        if (state == GuidanceState.AUTO_ZOOMING) {
            canvas.drawText("正在自动变焦…", 40, statusY, textPaint);
        } else if (state == GuidanceState.VERIFYING) {
            canvas.drawText("正在验证构图…", 40, statusY, textPaint);
        } else if (result.autoZoomCompleted) {
            canvas.drawText("构图已对齐", 40, statusY, textPaint);
        } else if (state == GuidanceState.LOCKED) {
            canvas.drawText(result.alignmentReady ? "保持稳定，即将自动变焦" : "请把绿框移到画面中心",
                40, statusY, textPaint);
        }

        String hint = hintForReason(result.reasonCode);
        // 已对齐时不再显示"部分出画"之类冲突提示
        if (hint != null && !(result.autoZoomCompleted && "TARGET_OUT_OF_VIEW".equals(result.reasonCode))) {
            canvas.drawText(hint, 40, getHeight() - 70f, textPaint);
        }

        if (showDebug) {
            drawDebug(canvas);
        }
    }

    private String hintForReason(String reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        switch (reasonCode) {
            case "NO_BETTER_COMPOSITION":
                return "当前构图已较好";
            case "ZOOM_RANGE_EXCEEDED":
                return "推荐区域超出自动变焦范围";
            case "ZOOM_UNSUPPORTED":
                return "此设备暂不支持自动变焦";
            case "MANUAL_ZOOM_OVERRIDE":
                return "已暂停自动变焦（手动接管）";
            case "VERIFY_FAILED":
                return "请重新对齐推荐区域";
            case "TARGET_OUT_OF_VIEW":
                return "推荐区域部分出画，请转向";
            case "INSUFFICIENT_FEATURES":
                return "画面纹理不足，无法稳定跟踪";
            case "NOT_CENTERED":
                // 该状态已由顶部引导文案表达，底部不再重复
                return null;
            case "STATE_NOT_LOCKED":
            case "ASPECT_MISMATCH":
            case "ROTATED":
            case "PERSPECTIVE_TOO_LARGE":
                return null;
            default:
                return reasonCode;
        }
    }

    private void drawQuad(Canvas canvas, double[] quad, Paint paint) {
        path.reset();
        for (int i = 0; i < 4; i++) {
            float[] p = mapPoint(quad[i * 2], quad[i * 2 + 1]);
            if (i == 0) {
                path.moveTo(p[0], p[1]);
            } else {
                path.lineTo(p[0], p[1]);
            }
        }
        path.close();
        canvas.drawPath(path, paint);

        double[] c = com.aicamera.core.Quad.of(quad).center();
        float[] cpt = mapPoint(c[0], c[1]);
        canvas.drawCircle(cpt[0], cpt[1], 6f, arrowPaint);
    }

    /** 归一化预览坐标 → View 像素坐标（使用真实显示矩形）。 */
    private float[] mapPoint(double x, double y) {
        double[] mapped = analysisToPreview != null ? analysisToPreview.apply(x, y) : new double[] {x, y};
        double mx = mapped[0];
        double my = mapped[1];
        if (!Double.isFinite(mx)) {
            mx = x;
        }
        if (!Double.isFinite(my)) {
            my = y;
        }
        float[] rect = currentDisplayRect();
        return new float[] {
            rect[0] + (float) (mx * rect[2]),
            rect[1] + (float) (my * rect[3]),
        };
    }

    private float[] currentDisplayRect() {
        // 预览容器已固定为取景比例且视频填满容器，显示矩形即整个 View
        if (displayRect == null) {
            displayRect = new float[] {0, 0, getWidth(), getHeight()};
        }
        return displayRect;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        displayRect = null;
    }

    /**
     * 对齐引导线（规格 §2.3）。
     *
     * <p>普通变焦只改变视野大小、不能把偏心区域搬进画面中心，因此必须先引导用户
     * 把目标框移动/转动到画面中心。这里绘制三条信息：
     * <ol>
     *   <li>画面中心的目标位指示（虚线方框）；</li>
     *   <li>从当前框中心指向目标位的引导线，线越长表示偏心越大；</li>
     *   <li>框外框的差异染色：对齐后转为实心高亮。</li>
     * </ol>
     * 引导线长度同时受对齐进度影响：接近对齐时会缩短并淡出，避免遮挡。
     */
    private void drawAlignmentGuide(Canvas canvas) {
        float[] rect = currentDisplayRect();
        if (rect[2] <= 0 || rect[3] <= 0) {
            return;
        }

        // 画面中心（显示矩形中心）
        float targetX = rect[0] + rect[2] / 2f;
        float targetY = rect[1] + rect[3] / 2f;

        double[] quad = result.displayQuad;
        double[] centerNorm = com.aicamera.core.Quad.of(quad).center();
        float[] centerPx = mapPoint(centerNorm[0], centerNorm[1]);

        double errX = result.alignmentErrorX;
        double errY = result.alignmentErrorY;
        double errorNorm = Math.hypot(errX, errY);
        // 3% 画幅以内视为已对齐
        double alignedThreshold = 0.03;
        boolean aligned = errorNorm <= alignedThreshold || result.alignmentReady;

        float alpha = aligned
            ? 255f
            : (float) Math.min(255.0, 120.0 + 135.0 * Math.min(1.0, errorNorm / 0.15));

        guideLinePaint.setColor(Color.argb((int) alpha, 90, 235, 190));
        guideLinePaint.setStrokeWidth(aligned ? 6f : 4f);
        guideLinePaint.setPathEffect(aligned
            ? null
            : new DashPathEffect(new float[] {18f, 14f}, phase));

        if (!aligned) {
            // 从当前框中心指向画面中心
            canvas.drawLine(centerPx[0], centerPx[1], targetX, targetY, guideLinePaint);
            drawGuideArrowHead(canvas, centerPx[0], centerPx[1], targetX, targetY, alpha);
            // 当前中心点
            targetPaint.setColor(Color.argb((int) alpha, 90, 235, 190));
            canvas.drawCircle(centerPx[0], centerPx[1], 9f, targetPaint);
        }

        // 目标位指示：一个随对齐进度收紧的圆环
        float ringRadius = (float) (Math.min(rect[2], rect[3]) * 0.16);
        float tighten = (float) (1.0 - 0.55 * Math.min(1.0, result.alignmentProgress));
        ringRadius *= tighten;

        guideRingPaint.setStrokeWidth(aligned ? 7f : 4f);
        guideRingPaint.setColor(aligned
            ? Color.argb(255, 120, 255, 160)
            : Color.argb((int) alpha, 90, 235, 190));
        canvas.drawCircle(targetX, targetY, ringRadius, guideRingPaint);

        if (aligned) {
            // 对齐后：十字准星 + 中心实心点
            canvas.drawLine(targetX - ringRadius * 1.35f, targetY, targetX + ringRadius * 1.35f,
                targetY, guideLinePaint);
            canvas.drawLine(targetX, targetY - ringRadius * 1.35f, targetX,
                targetY + ringRadius * 1.35f, guideLinePaint);
            targetPaint.setColor(Color.argb(255, 120, 255, 160));
            canvas.drawCircle(targetX, targetY, 8f, targetPaint);
        }

        phase = (phase + 2.0f) % 1000f;
    }

    private void drawGuideArrowHead(Canvas canvas, float x0, float y0, float x1, float y1, float alpha) {
        double angle = Math.atan2(y1 - y0, x1 - x0);
        float size = 26f;
        guideArrowPaint.setColor(Color.argb((int) alpha, 90, 235, 190));
        Path head = new Path();
        head.moveTo(x1, y1);
        head.lineTo(x1 - size * (float) Math.cos(angle - 0.42),
            y1 - size * (float) Math.sin(angle - 0.42));
        head.lineTo(x1 - size * (float) Math.cos(angle + 0.42),
            y1 - size * (float) Math.sin(angle + 0.42));
        head.close();
        canvas.drawPath(head, guideArrowPaint);
    }

    private void drawDirectionHint(Canvas canvas, Direction direction) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.18f;
        Path arrow = new Path();
        switch (direction) {
            case RIGHT:
                arrow.moveTo(cx + r, cy);
                arrow.lineTo(cx + r * 0.55f, cy - r * 0.35f);
                arrow.lineTo(cx + r * 0.55f, cy + r * 0.35f);
                break;
            case LEFT:
                arrow.moveTo(cx - r, cy);
                arrow.lineTo(cx - r * 0.55f, cy - r * 0.35f);
                arrow.lineTo(cx - r * 0.55f, cy + r * 0.35f);
                break;
            case UP:
                arrow.moveTo(cx, cy - r);
                arrow.lineTo(cx - r * 0.35f, cy - r * 0.55f);
                arrow.lineTo(cx + r * 0.35f, cy - r * 0.55f);
                break;
            case DOWN:
                arrow.moveTo(cx, cy + r);
                arrow.lineTo(cx - r * 0.35f, cy + r * 0.55f);
                arrow.lineTo(cx + r * 0.35f, cy + r * 0.55f);
                break;
            default:
                return;
        }
        arrow.close();
        canvas.drawPath(arrow, arrowPaint);
        canvas.drawText(direction.hintText(), 40, getHeight() - 130f, textPaint);
    }

    private void drawDebug(Canvas canvas) {
        // 右上角显示，避免与左上角的状态文案重叠
        debugPaint.setTextAlign(Paint.Align.RIGHT);
        float x = getWidth() - 30f;
        float y = 64f;
        canvas.drawText("state=" + result.state, x, y, debugPaint);
        y += 34f;
        canvas.drawText(String.format(java.util.Locale.US, "q=%.2f vis=%.3f",
            result.trackingQuality, result.visibleRatio), x, y, debugPaint);
        y += 34f;
        canvas.drawText(String.format(java.util.Locale.US, "zoom=%.2f target=%.2f",
            result.actualZoomRatio, result.targetZoomRatio), x, y, debugPaint);
        y += 34f;
        canvas.drawText(String.format(java.util.Locale.US, "err=(%.3f,%.3f)",
            result.alignmentErrorX, result.alignmentErrorY), x, y, debugPaint);
        debugPaint.setTextAlign(Paint.Align.LEFT);
    }
}
