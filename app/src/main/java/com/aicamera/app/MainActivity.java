package com.aicamera.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.aicamera.core.AlignmentConfig;
import com.aicamera.core.GuidanceController;
import com.aicamera.core.GuidanceResult;
import com.aicamera.core.StateMachineConfig;
import com.aicamera.vision.OpenCvLoader;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opencv.core.Mat;

/**
 * 阶段一主界面。
 *
 * <p>交互（按用户要求调整）：
 * <ol>
 *   <li>打开应用后只显示预览，不自动锁定；</li>
 *   <li>画面稳定后「锁定构图」按钮变为可用；</li>
 *   <li>点击按钮后锁定当前参考帧并开始跟踪；</li>
 *   <li>单击画面拍照，双指缩放为手动变焦。</li>
 * </ol>
 */
public final class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_CAMERA = 1001;

    private PreviewView previewView;
    private GuidanceOverlay overlay;
    private AspectFrameLayout previewContainer;
    private Button lockButton;
    private Button relockButton;
    private TextView statusText;

    private Camera camera;
    private ImageAnalysis analysis;
    private ImageCapture imageCapture;
    private ProcessCameraProvider provider;
    private final ExecutorService preprocessExecutor = Executors.newSingleThreadExecutor();

    private GuidanceController controller;
    private TrackingPipeline pipeline;
    private ZoomController zoomController;

    private ScaleGestureDetector scaleDetector;
    private long shutterAtNs;
    private volatile boolean previewMappingApplied;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean openCvReady = OpenCvLoader.ensureLoaded();
        Log.i(TAG, "OpenCV loaded=" + openCvReady + " (" + OpenCvLoader.diagnostics() + ")");

        FrameLayout root = new FrameLayout(this);

        // 预览与引导层放进同一个固定 4:3（竖屏 3:4）容器：
        // 视频精确填满容器，引导层用同样的矩形，坐标天然对齐。
        previewContainer = new AspectFrameLayout(this);
        previewContainer.setAspectRatio(3.0 / 4.0);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        overlay = new GuidanceOverlay(this);
        overlay.setShowDebug(true);
        // 覆盖层只负责绘制，不能拦截预览的触摸事件（点击拍照/双指缩放）
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setOnTouchListener((v, event) -> false);

        previewContainer.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewContainer.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        containerParams.gravity = Gravity.CENTER;
        root.addView(previewContainer, containerParams);
        root.addView(buildControlBar(), controlBarLayoutParams());
        setContentView(root);

        controller = new GuidanceController(
            "session-" + System.currentTimeMillis(), new AlignmentConfig(), new StateMachineConfig());
        pipeline = new TrackingPipeline(controller, new TrackingPipeline.Callback() {
            @Override
            public void onStability(boolean stable, double progress, TrackingPipeline.Stage stage) {
                runOnUiThread(() -> updateLockUi(stable, progress, stage));
            }

            @Override
            public void onGuidance(GuidanceResult result) {
                runOnUiThread(() -> {
                    overlay.setResult(result);
                    // 已锁定时同步顶部提示，避免文案与画面状态不一致
                    if (pipeline.stage() == TrackingPipeline.Stage.TRACKING) {
                        if (result.autoZoomCompleted) {
                            statusText.setText("构图已对齐，可直接拍摄；双指可再调整倍率");
                        } else if (result.state == com.aicamera.core.GuidanceState.AUTO_ZOOMING) {
                            statusText.setText(String.format(Locale.US,
                                "正在自动变焦… %.2fx → %.2fx",
                                result.actualZoomRatio, result.targetZoomRatio));
                        } else if (result.alignmentReady) {
                            statusText.setText("保持稳定，即将自动变焦");
                        } else {
                            statusText.setText("请把绿框移到画面中心，对齐后自动变焦");
                        }
                    }
                });
            }

            @Override
            public void onLockFailed(String reasonCode) {
                runOnUiThread(() -> {
                    if (reasonCode == null) {
                        lockButton.setText("已锁定");
                        lockButton.setEnabled(false);
                        relockButton.setVisibility(android.view.View.VISIBLE);
                        statusText.setText("请把绿框移到画面中心，对齐后自动变焦");
                        Toast.makeText(MainActivity.this, "已锁定构图，开始跟踪", Toast.LENGTH_SHORT).show();
                    } else {
                        lockButton.setEnabled(true);
                        statusText.setText("锁定失败：" + reasonCode + "，请持稳后重试");
                        Toast.makeText(MainActivity.this,
                            "锁定失败：" + reasonCode, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        setupGestures();
        ensurePermission();

        if (!openCvReady) {
            Toast.makeText(this, "OpenCV 初始化失败：" + OpenCvLoader.diagnostics(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout buildControlBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(32, 24, 32, 48);

        statusText = new TextView(this);
        statusText.setText("正在准备相机…");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(15f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setShadowLayer(3f, 0f, 2f, Color.BLACK);

        lockButton = new Button(this);
        lockButton.setText("锁定构图");
        lockButton.setEnabled(false);
        lockButton.setOnClickListener(v -> onLockClicked());

        relockButton = new Button(this);
        relockButton.setText("重新构图");
        relockButton.setVisibility(android.view.View.GONE);
        relockButton.setOnClickListener(v -> onRelockClicked());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.topMargin = 20;
        buttons.addView(lockButton, btnParams);
        LinearLayout.LayoutParams relockParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        relockParams.topMargin = 20;
        relockParams.leftMargin = 24;
        buttons.addView(relockButton, relockParams);

        bar.addView(statusText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(buttons, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private FrameLayout.LayoutParams controlBarLayoutParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.bottomMargin = 60;
        return p;
    }

    private void updateLockUi(boolean stable, double progress, TrackingPipeline.Stage stage) {
        switch (stage) {
            case WAITING_STABLE:
                lockButton.setEnabled(stable);
                lockButton.setText(stable ? "锁定构图" : "锁定构图");
                statusText.setText(stable
                    ? "画面已稳定，可以锁定构图"
                    : String.format(Locale.US, "请持稳相机… %d%%", (int) (progress * 100)));
                break;
            case LOCKING:
                lockButton.setEnabled(false);
                statusText.setText("正在锁定…");
                break;
            case TRACKING:
                lockButton.setEnabled(false);
                lockButton.setText("已锁定");
                lockButton.setVisibility(android.view.View.VISIBLE);
                relockButton.setVisibility(android.view.View.VISIBLE);
                statusText.setText("跟踪中；单击拍照，双指缩放");
                break;
            default:
                lockButton.setEnabled(false);
                statusText.setText("正在准备相机…");
                break;
        }
    }

    private void onLockClicked() {
        // 帧经预处理线程转交，这里置标记让流水线在下一帧处理时执行锁定
        lockRequestPending = true;
        lockButton.setEnabled(false);
        statusText.setText("正在锁定…");
    }

    private void onRelockClicked() {
        lockRequestPending = false;
        relockButton.setVisibility(android.view.View.GONE);
        lockButton.setText("锁定构图");
        lockButton.setVisibility(android.view.View.VISIBLE);
        lockButton.setEnabled(false);
        overlay.setResult(null);
        pipeline.resetForRelock();
        statusText.setText("请持稳相机…");
    }

    private volatile boolean lockRequestPending;

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                if (zoomController != null) {
                    zoomController.onUserZoomStart();
                }
                controller.onManualZoomStart(System.nanoTime());
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                if (camera == null) {
                    return true;
                }
                ZoomState state = camera.getCameraInfo().getZoomState().getValue();
                if (state == null) {
                    return true;
                }
                double next = state.getZoomRatio() * detector.getScaleFactor();
                next = Math.max(state.getMinZoomRatio(), Math.min(state.getMaxZoomRatio(), next));
                camera.getCameraControl().setZoomRatio((float) next);
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                if (zoomController != null) {
                    zoomController.onUserZoomEnd();
                }
                controller.onManualZoomEnd(System.nanoTime());
            }
        });

        previewView.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() >= 2) {
                scaleDetector.onTouchEvent(event);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                    takePhoto();
                }
            }
            return true;
        });
    }

    private void ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
        @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能继续", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                bindUseCases();
            } catch (Throwable t) {
                Log.e(TAG, "camera provider failed", t);
                Toast.makeText(this, "相机启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysisUseCase = FrameNormalizer.buildAnalysisUseCase(
            FrameNormalizer.ANALYSIS_WIDTH, FrameNormalizer.ANALYSIS_HEIGHT);
        imageCapture = new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build();

        try {
            provider.unbindAll();
            camera = provider.bindToLifecycle(this,
                CameraSelector.DEFAULT_BACK_CAMERA, preview, analysisUseCase, imageCapture);
        } catch (Throwable t) {
            Log.e(TAG, "bind failed", t);
            Toast.makeText(this, "相机配置不支持：" + t.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        analysis = analysisUseCase;
        zoomController = new ZoomController(camera, previewView);
        controller.setListener(zoomController);

        // 自动变焦上限取 min(5.0, 设备实际上限)（规格 §4.3）
        double deviceMax = zoomController.maxZoomRatio();
        controller.setDeviceMaxZoomRatio(deviceMax);
        Log.i(TAG, String.format(Locale.US,
            "auto zoom limit = %.2f (device max = %.2f)", 
            controller.alignmentConfig().maxAutoZoomRatio, deviceMax));

        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        Log.i(TAG, String.format(Locale.US, "preview ready, zoom=%.2f range=[%.2f, %.2f], supported=%s",
            zs == null ? 1 : zs.getZoomRatio(),
            zs == null ? 1 : zs.getMinZoomRatio(),
            zs == null ? 1 : zs.getMaxZoomRatio(),
            zoomController.isZoomSupported()));

        if (!zoomController.isZoomSupported()) {
            Toast.makeText(this, "此设备暂不支持自动变焦", Toast.LENGTH_LONG).show();
        }

        attachAnalyzer();
    }

    /**
     * 分析线程：只做 Y 平面拷贝，重活（缩放/光流）交给预处理线程，
     * 避免阻塞相机管线导致掉帧。
     */
    private void attachAnalyzer() {
        if (analysis == null) {
            return;
        }
        analysis.setAnalyzer(analysisExecutor, proxy -> {
            try {
                Mat gray = FrameNormalizer.toAnalysisGrayMat(proxy);
                if (gray == null) {
                    return;
                }
                long ts = proxy.getImageInfo().getTimestamp();
                if (ts <= 0) {
                    ts = System.nanoTime();
                }
                int rotation = proxy.getImageInfo().getRotationDegrees();
                if (!previewMappingApplied) {
                    previewMappingApplied = true;
                    final int rot = rotation;
                    runOnUiThread(() -> {
                        overlay.setAnalysisToPreview(FrameNormalizer.analysisToPreview(rot, false));
                        overlay.setOutputFrame(4.0 / 3.0, rot);
                    });
                }
                double zoom = currentZoom();
                pipeline.submit(gray, ts, zoom);
                if (lockRequestPending) {
                    lockRequestPending = false;
                    pipeline.requestLock(ts, zoom);
                }
            } finally {
                proxy.close();
            }
        });
    }

    private double currentZoom() {
        if (camera == null) {
            return 1.0;
        }
        ZoomState state = camera.getCameraInfo().getZoomState().getValue();
        return state == null ? 1.0 : state.getZoomRatio();
    }

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    private void takePhoto() {
        if (imageCapture == null) {
            return;
        }
        shutterAtNs = System.nanoTime();
        controller.onShutter();

        double actual = zoomController == null ? 1.0 : zoomController.currentZoomRatio();
        Log.i(TAG, String.format(Locale.US, "shutter, actualZoom=%.3f (no crop applied)", actual));

        File dir = new File(getExternalFilesDir(null), "captures");
        if (!dir.exists() && !dir.mkdirs()) {
            Toast.makeText(this, "无法创建照片目录", Toast.LENGTH_SHORT).show();
            return;
        }
        File out = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(out).build();
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(this),
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                    Toast.makeText(MainActivity.this, "已保存原片：" + out.getName(),
                        Toast.LENGTH_SHORT).show();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(out.getAbsolutePath(), o);
                    Log.i(TAG, "saved " + o.outWidth + "x" + o.outHeight
                        + ", elapsed=" + ((System.nanoTime() - shutterAtNs) / 1_000_000) + "ms");
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "capture failed", exception);
                    Toast.makeText(MainActivity.this, "拍照失败：" + exception.getMessage(),
                        Toast.LENGTH_LONG).show();
                }
            });
    }

    @Override
    protected void onDestroy() {
        if (analysis != null) {
            analysis.clearAnalyzer();
        }
        if (pipeline != null) {
            pipeline.close();
        }
        analysisExecutor.shutdown();
        preprocessExecutor.shutdown();
        if (provider != null) {
            provider.unbindAll();
        }
        super.onDestroy();
    }
}
