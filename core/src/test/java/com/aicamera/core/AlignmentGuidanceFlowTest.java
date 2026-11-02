package com.aicamera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端到端验证「锁定 → 引导对齐 → 自动变焦 → 验证完成」链路（规格 §10）。
 *
 * <p>对应真机交互：用户点击锁定后目标框可能在偏心位置，需要先引导到画面中心，
 * 稳定后系统才自动变焦。
 */
class AlignmentGuidanceFlowTest {

    private static final String SESSION = "guide-session";
    private static final long FRAME_NS = 33_000_000L;

    private GuidanceController controller;
    private final List<Double> zoomRequests = new ArrayList<>();
    private final List<GuidanceState> transitions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        controller = new GuidanceController(SESSION, new AlignmentConfig(), new StateMachineConfig());
        controller.setListener(new GuidanceListener() {
            @Override
            public void onRequestZoom(double ratio, long durationMs) {
                zoomRequests.add(ratio);
            }

            @Override
            public void onStateChanged(GuidanceState oldState, GuidanceState newState, String reasonCode) {
                transitions.add(newState);
            }
        });
        controller.startSearching();
        // 锚点故意偏右：模拟用户锁定了一个不在画面中心的区域
        double[] quad = Quad.fromRect(0.34, 0.30, 0.74, 0.70).toArray();
        controller.lockAnchor(new CompositionAnchor("a1", 1, 0L, 0L, 1.0, quad, 4.0 / 3.0));
    }

    private FramePacket frame(long id, long ns, double zoom) {
        return new FramePacket(SESSION, id, ns, 640, 480, 0, zoom, null);
    }

    /** 推进若干帧，目标框位于给定矩形。 */
    private GuidanceResult feed(long startId, long startNs, double[] rect, int frames, double zoom) {
        long ns = startNs;
        long id = startId;
        GuidanceResult last = null;
        int gen = controller.anchor().generation;
        for (int i = 0; i < frames; i++) {
            ns += FRAME_NS;
            id++;
            last = controller.onTrackingUpdate(frame(id, ns, zoom),
                new TrackingUpdate(id, ns, gen, Quad.fromRect(rect[0], rect[1], rect[2], rect[3]), 0.9, null));
        }
        return last;
    }

    @Test
    @DisplayName("偏心目标：给出对齐误差，且不触发变焦")
    void offCenterReportsErrorAndDoesNotZoom() {
        GuidanceResult r = feed(0, 0, new double[] {0.34, 0.30, 0.74, 0.70}, 40, 1.0);

        // 框中心 = (0.54, 0.50)：向右偏 0.04，垂直居中
        assertTrue(r.alignmentErrorX > 0.03, "应报告向右偏心: " + r.alignmentErrorX);
        assertEquals(0.0, r.alignmentErrorY, 1e-6, "垂直方向应基本居中");
        assertFalse(r.alignmentReady, "偏心时不应就绪");
        assertTrue(zoomRequests.isEmpty(), "偏心时不得自动变焦");
        assertEquals(GuidanceState.LOCKED, controller.state());
    }

    @Test
    @DisplayName("引导到画面中心并稳定后自动变焦")
    void alignThenAutoZoom() {
        // 第一段：偏心，确认不缩放
        feed(0, 0, new double[] {0.34, 0.30, 0.74, 0.70}, 40, 1.0);
        assertTrue(zoomRequests.isEmpty());

        // 第二段：用户把目标移到画面中心（0.2..0.8 宽高各 0.6）
        long ns = 40 * FRAME_NS;
        GuidanceResult r = null;
        long id = 100;
        for (int i = 0; i < 40 && zoomRequests.isEmpty(); i++) {
            r = feed(id, ns, new double[] {0.2, 0.2, 0.8, 0.8}, 1, 1.0);
            ns += FRAME_NS;
            id += 1;
        }
        assertFalse(zoomRequests.isEmpty(), "居中对齐稳定后应触发自动变焦");
        assertEquals(1.0 / 0.6, zoomRequests.get(0), 1e-3);
        assertEquals(GuidanceState.AUTO_ZOOMING, controller.state());
        assertTrue(r.alignmentErrorX < 0.01 && r.alignmentErrorY < 0.01);
    }

    @Test
    @DisplayName("变焦完成后进入 LOCKED 并标记完成，不会重复放大")
    void zoomThenVerifyCompletes() {
        // 直接对齐触发
        long ns = 0;
        long id = 0;
        for (int i = 0; i < 40 && zoomRequests.isEmpty(); i++) {
            feed(id++, ns, new double[] {0.2, 0.2, 0.8, 0.8}, 1, 1.0);
            ns += FRAME_NS;
        }
        assertFalse(zoomRequests.isEmpty());
        double target = zoomRequests.get(0);

        // 变焦后目标铺满画面
        int gen = controller.anchor().generation;
        for (int i = 0; i < 20; i++) {
            ns += FRAME_NS;
            id++;
            controller.onTrackingUpdate(frame(id, ns, target),
                new TrackingUpdate(id, ns, gen, Quad.fromRect(0.03, 0.03, 0.97, 0.97), 0.9, null));
            if (controller.anchor().isAutoZoomCompleted()) {
                break;
            }
        }
        assertTrue(controller.anchor().isAutoZoomCompleted(), "变焦应完成，transitions=" + transitions);
        assertEquals(GuidanceState.LOCKED, controller.state());
        assertEquals(1, zoomRequests.size(), "每个锚点只自动变焦一次");
    }

    @Test
    @DisplayName("变焦未达目标时会超时进入验证，不会永久卡住")
    void zoomTimeout() {
        long ns = 0;
        long id = 0;
        for (int i = 0; i < 40 && zoomRequests.isEmpty(); i++) {
            feed(id++, ns, new double[] {0.2, 0.2, 0.8, 0.8}, 1, 1.0);
            ns += FRAME_NS;
        }
        assertEquals(GuidanceState.AUTO_ZOOMING, controller.state());

        // 实际倍率始终不跟上目标值
        int gen = controller.anchor().generation;
        boolean leftAutoZooming = false;
        for (int i = 0; i < 120; i++) {
            ns += FRAME_NS;
            id++;
            controller.onTrackingUpdate(frame(id, ns, 1.02),
                new TrackingUpdate(id, ns, gen, Quad.fromRect(0.2, 0.2, 0.8, 0.8), 0.9, null));
            if (controller.state() != GuidanceState.AUTO_ZOOMING) {
                leftAutoZooming = true;
                break;
            }
        }
        assertTrue(leftAutoZooming, "超时后应离开 AUTO_ZOOMING，实际状态=" + controller.state());
        assertEquals(1, zoomRequests.size(), "超时不应重复请求变焦");
    }

    @Test
    @DisplayName("对齐进度会随时间累积，用于 UI 引导反馈")
    void alignmentProgressAccumulates() {
        GuidanceResult early = feed(0, 0, new double[] {0.2, 0.2, 0.8, 0.8}, 2, 1.0);
        GuidanceResult later = feed(10, 2 * FRAME_NS, new double[] {0.2, 0.2, 0.8, 0.8}, 6, 1.0);
        assertTrue(later.alignmentProgress > early.alignmentProgress,
            "进度应递增: " + early.alignmentProgress + " -> " + later.alignmentProgress);
    }
}
