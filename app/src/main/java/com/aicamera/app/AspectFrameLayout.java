package com.aicamera.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 固定宽高比的容器。
 *
 * <p>把预览与引导层放进同一个固定比例的容器，视频将精确填满容器，
 * 引导层也就不需要猜测 letterbox 尺寸，坐标天然对齐（规格 §5.1）。
 */
public final class AspectFrameLayout extends FrameLayout {

    private double aspectRatio = 3.0 / 4.0;

    public AspectFrameLayout(Context context) {
        super(context);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** @param aspect 宽 / 高（竖屏 4:3 取景为 0.75） */
    public void setAspectRatio(double aspect) {
        if (aspect > 0 && Math.abs(aspect - aspectRatio) > 1e-6) {
            this.aspectRatio = aspect;
            requestLayout();
        }
    }

    public double aspectRatio() {
        return aspectRatio;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (width <= 0 || height <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        double viewAspect = (double) width / height;
        int finalWidth;
        int finalHeight;
        if (viewAspect > aspectRatio) {
            finalHeight = height;
            finalWidth = (int) Math.round(height * aspectRatio);
        } else {
            finalWidth = width;
            finalHeight = (int) Math.round(width / aspectRatio);
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY));
    }
}
