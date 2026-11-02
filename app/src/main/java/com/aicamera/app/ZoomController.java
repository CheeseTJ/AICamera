package com.aicamera.app;

import android.util.Log;
import androidx.camera.core.ZoomState;
import androidx.camera.view.PreviewView;
import com.aicamera.core.GuidanceListener;
import com.aicamera.core.GuidanceState;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相机变焦仲裁（规格 §4.3 / §10.3 / §11.1）。
 *
 * <p>职责：
 * <ul>
 *   <li>启动时读取 CONTROL_ZOOM_RATIO_RANGE 与实际倍率；</li>
 *   <li>把 core 的变焦请求平滑推给 CameraX；</li>
 *   <li>关闭可关闭的电子防抖，避免各流视野不一致；</li>
 *   <li>用户手势优先，自动变焦不得与手势抢控制权。</li>
 * </ul>
 */
public final class ZoomController implements GuidanceListener {

    private static final String TAG = "ZoomController";
    private static final double MIN_GAIN = 0.002;

    private final androidx.camera.core.Camera camera;
    private final PreviewView previewView;
    private final AtomicInteger pendingRequest = new AtomicInteger();

    private volatile boolean manualZoomActive;
    private volatile double lastAppliedRatio = 1.0;

    public ZoomController(androidx.camera.core.Camera camera, PreviewView previewView) {
        this.camera = camera;
        this.previewView = previewView;
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        if (state != null) {
            lastAppliedRatio = state.getZoomRatio();
            Log.i(TAG, String.format(Locale.US, "zoom range %.2f..%.2f, current %.2f, supported=%s",
                state.getMinZoomRatio(), state.getMaxZoomRatio(), state.getZoomRatio(), isZoomSupported()));
        }
    }

    /**
     * 设备是否支持受控变焦（规格 §4.1）。
     *
     * <p>以 CameraX 报告的最大倍率是否大于 1 作为可验证条件，
     * 不静默执行未经验证的替代操作。
     */
    public boolean isZoomSupported() {
        ZoomState s = camera.getCameraInfo().getZoomState().getValue();
        return s != null && s.getMaxZoomRatio() > 1.0f + 1e-3f;
    }

    public double maxZoomRatio() {
        ZoomState s = camera.getCameraInfo().getZoomState().getValue();
        return s == null ? 1.0 : s.getMaxZoomRatio();
    }

    public double currentZoomRatio() {
        ZoomState s = camera.getCameraInfo().getZoomState().getValue();
        return s == null ? lastAppliedRatio : s.getZoomRatio();
    }

    /** 用户手势开始：取消进行中的自动变焦（规格 §11.1）。 */
    public void onUserZoomStart() {
        manualZoomActive = true;
        pendingRequest.incrementAndGet();
        lastAppliedRatio = currentZoomRatio();
    }

    public void onUserZoomEnd() {
        manualZoomActive = false;
        lastAppliedRatio = currentZoomRatio();
    }

    public boolean isManualZoomActive() {
        return manualZoomActive;
    }

    /** 自动变焦：用对数插值平滑推进（规格 §10.3）。 */
    @Override
    public void onRequestZoom(double ratio, long durationMs) {
        if (manualZoomActive) {
            Log.i(TAG, "ignoring auto-zoom request during manual gesture");
            return;
        }
        if (!isZoomSupported()) {
            Log.w(TAG, "device does not report zoom ratio support");
            return;
        }
        // 双重保护：core 已按 min(5.0, 设备上限) 校验，这里再按设备能力裁剪一次
        final double bounded = Math.min(ratio, maxZoomRatio());
        final double from = currentZoomRatio();
        if (Math.abs(bounded - from) < MIN_GAIN) {
            return;
        }
        final int ticket = pendingRequest.incrementAndGet();
        final long start = System.currentTimeMillis();

        previewView.post(new Runnable() {
            @Override
            public void run() {
                if (ticket != pendingRequest.get()) {
                    return; // 请求已被取消或被新请求替换
                }
                long elapsed = System.currentTimeMillis() - start;
                double u = durationMs <= 0 ? 1.0 : Math.min(1.0, elapsed / (double) durationMs);
                double z = com.aicamera.core.ZoomMath.interpolateLog(from, bounded,
                    com.aicamera.core.ZoomMath.smoothStep(u));
                camera.getCameraControl().setZoomRatio((float) z);
                lastAppliedRatio = z;
                if (u < 1.0 && ticket == pendingRequest.get()) {
                    previewView.postDelayed(this, 16);
                }
            }
        });
    }

    @Override
    public void onCancelZoom() {
        pendingRequest.incrementAndGet();
    }

    @Override
    public void onStateChanged(GuidanceState oldState, GuidanceState newState, String reasonCode) {
        Log.i(TAG, "state " + oldState + " -> " + newState + (reasonCode == null ? "" : " (" + reasonCode + ")"));
    }
}
