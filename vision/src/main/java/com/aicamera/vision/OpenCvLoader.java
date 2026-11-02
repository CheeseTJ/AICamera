package com.aicamera.vision;

/**
 * OpenCV 原生库加载入口。
 *
 * <p>Android 端由 {@code OpenCVLoader.initLocal()} 加载 AAR 中的 libopencv_java4.so；
 * 桌面验证环境使用 openpnp 提供的本地库。
 *
 * <p>注意：仅检查类是否存在并不代表原生库已就绪，必须真实触发加载并做一次原生调用验证，
 * 否则会在第一次使用 Mat 时抛出 UnsatisfiedLinkError。
 */
public final class OpenCvLoader {

    private static volatile boolean loaded;
    private static volatile String diagnostics = "not attempted";

    private OpenCvLoader() {
    }

    /**
     * 确保 OpenCV 原生库已加载，并做一次真实调用验证。
     *
     * @return true 表示可用
     */
    public static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        if (tryAndroidLoader()) {
            return true;
        }
        if (tryDesktopLoader()) {
            return true;
        }
        return false;
    }

    private static boolean tryAndroidLoader() {
        try {
            Class<?> loader = Class.forName("org.opencv.android.OpenCVLoader");
            Object result = loader.getMethod("initLocal").invoke(null);
            diagnostics = "android OpenCVLoader.initLocal -> " + result;
            if (Boolean.TRUE.equals(result)) {
                return probe();
            }
            return false;
        } catch (Throwable t) {
            diagnostics = "android loader unavailable: " + t;
            return false;
        }
    }

    private static boolean tryDesktopLoader() {
        try {
            Class<?> openCvPattern = Class.forName("nu.pattern.OpenCV");
            openCvPattern.getMethod("loadLocally").invoke(null);
            diagnostics = "desktop nu.pattern.OpenCV.loadLocally";
            return probe();
        } catch (Throwable t) {
            diagnostics = "desktop loader unavailable: " + t;
            return false;
        }
    }

    /** 真实调用一次原生方法，确认 JNI 符号已绑定。 */
    private static boolean probe() {
        try {
            org.opencv.core.Mat m = org.opencv.core.Mat.zeros(1, 1, org.opencv.core.CvType.CV_8UC1);
            m.release();
            loaded = true;
            return true;
        } catch (Throwable t) {
            diagnostics = diagnostics + " | probe failed: " + t;
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String diagnostics() {
        return diagnostics;
    }
}
