package com.aicamera.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 状态机与自动变焦仲裁（规格 §10 / §11 / §12）。 */
class GuidanceControllerTest {

    private static final String SESSION = "session-1";
    private static final long FRAME_NS = 33_000_000L;

    private AlignmentConfig alignmentConfig;
    private StateMachineConfig stateConfig;
    private GuidanceController controller;
    private final List<String> events = new ArrayList<>();
    private final List<Double> zoomRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        alignmentConfig = new AlignmentConfig();
        stateConfig = new StateMachineConfig();
        controller = new GuidanceController(SESSION, alignmentConfig, stateConfig);
        controller.setListener(new GuidanceListener() {
            @Override
            public void onRequestZoom(double ratio, long durationMs) {
                zoomRequests.add(ratio);
                events.add("zoom:" + String.format("%.4f", ratio));
            }

            @Override
            public void onCancelZoom() {
                events.add("cancel");
            }

            @Override
            public void onStateChanged(GuidanceState oldState, GuidanceState newState, String reasonCode) {
                events.add(oldState + "->" + newState);
            }
        });
    }

    private FramePacket frame(long id, long ns, double zoom) {
        return new FramePacket(SESSION, id, ns, 640, 480, 0, zoom, null);
    }

    /** 由矩形四元组构造锚点。 */
    private CompositionAnchor anchor(double[] rect) {
        double[] quad = Quad.fromRect(rect[0], rect[1], rect[2], rect[3]).toArray();
        return new CompositionAnchor("a1", 1, 0L, 0L, 1.0, quad, 4.0 / 3.0);
    }

    /** 由矩形四元组构造四边形。 */
    private static Quad rect(double x1, double y1, double x2, double y2) {
        return Quad.fromRect(x1, y1, x2, y2);
    }

    /** 由矩形四元组数组构造四边形。 */
    private static Quad rectOf(double[] r) {
        return Quad.fromRect(r[0], r[1], r[2], r[3]);
    }

    /** 驱动控制器进入 LOCKED 且准备好自动变焦。 */
    private void driveUntilZoomRequested(double[] quad, double currentZoom, long startNs) {
        long ns = startNs;
        long id = 100;
        for (int i = 0; i < 60 && zoomRequests.isEmpty(); i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(id++, ns, currentZoom),
                new TrackingUpdate(id, ns, controller.anchor().generation,
                    rectOf(quad), 0.9, null));
        }
    }

    @Test
    @DisplayName("IDLE → SEARCHING → LOCKED 基本流程")
    void basicFlow() {
        assertEquals(GuidanceState.IDLE, controller.state());
        controller.startSearching();
        assertEquals(GuidanceState.SEARCHING, controller.state());

        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        assertEquals(GuidanceState.LOCKED, controller.state());
        assertNotNull(controller.anchor());    }

    @Test
    @DisplayName("锁定后不因其他候选更好而换目标")
    void anchorIsSticky() {
        controller.startSearching();
        CompositionAnchor a = anchor(new double[] {0.2, 0.2, 0.8, 0.8});
        controller.lockAnchor(a);
        // 再次 lockAnchor 不应替换
        controller.lockAnchor(anchor(new double[] {0.1, 0.1, 0.4, 0.4}));
        assertEquals(a.anchorId, controller.anchor().anchorId);
        assertEquals(a.referenceFrameId, controller.anchor().referenceFrameId);
    }

    @Test
    @DisplayName("居中稳定后触发一次自动变焦，倍率约 1.67x")
    void autoZoomTriggered() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        driveUntilZoomRequested(new double[] {0.2, 0.2, 0.8, 0.8}, 1.0, 0L);

        assertEquals(1, zoomRequests.size(), "should request zoom exactly once: " + events);
        assertEquals(1.0 / 0.6, zoomRequests.get(0), 1e-3);
        assertEquals(GuidanceState.AUTO_ZOOMING, controller.state());
    }

    @Test
    @DisplayName("偏心区域不触发自动变焦（防止主体被裁掉）")
    void offCenterNeverZooms() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.35, 0.2, 0.95, 0.8}));
        long ns = 0;
        for (int i = 0; i < 80; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i, ns, 1.0),
                new TrackingUpdate(i, ns, controller.anchor().generation,
                    rect(0.35, 0.2, 0.95, 0.8), 0.9, null));
        }
        assertTrue(zoomRequests.isEmpty());
        assertEquals(GuidanceState.LOCKED, controller.state());
    }

    @Test
    @DisplayName("倍率超限时不执行部分放大，返回 ZOOM_RANGE_EXCEEDED")
    void zoomRangeExceeded() {
        controller.startSearching();
        // 区域仅占 6% 宽：需要约 16.7x，远超默认上限 5.0x
        double[] small = {0.47, 0.47, 0.53, 0.53};
        controller.lockAnchor(anchor(small));
        long ns = 0;
        GuidanceResult last = null;
        for (int i = 0; i < 80; i++) {
            ns += FRAME_NS;
            last = controller.onTrackingUpdate(frame(i, ns, 1.0),
                new TrackingUpdate(i, ns, controller.anchor().generation, rectOf(small), 0.9, null));
        }
        assertTrue(zoomRequests.isEmpty());
        assertNotNull(last);
        assertEquals("ZOOM_RANGE_EXCEEDED", last.reasonCode);
    }

    @Test
    @DisplayName("默认上限为 5.0x，且会按设备能力裁剪")
    void maxZoomLimitDefaultsAndClampsToDevice() {
        AlignmentConfig fresh = new AlignmentConfig();
        assertEquals(5.0, fresh.maxAutoZoomRatio, 1e-9);

        // 设备只支持到 3.2x 时，上限被裁剪
        GuidanceController c2 = new GuidanceController("s", new AlignmentConfig(), new StateMachineConfig());
        c2.setDeviceMaxZoomRatio(3.2);
        assertEquals(3.2, c2.alignmentConfig().maxAutoZoomRatio, 1e-9);

        // 设备能力高于 5.0 时仍受配置上限约束
        GuidanceController c3 = new GuidanceController("s", new AlignmentConfig(), new StateMachineConfig());
        c3.setDeviceMaxZoomRatio(30.0);
        assertEquals(5.0, c3.alignmentConfig().maxAutoZoomRatio, 1e-9);

        // 设备不支持变焦时不放宽
        GuidanceController c4 = new GuidanceController("s", new AlignmentConfig(), new StateMachineConfig());
        c4.setDeviceMaxZoomRatio(1.0);
        assertEquals(5.0, c4.alignmentConfig().maxAutoZoomRatio, 1e-9);
    }

    @Test
    @DisplayName("需要的倍率在 5.0x 上限内时会执行自动变焦")
    void zoomWithin5xIsAllowed() {
        controller.startSearching();
        // 区域宽高 30% -> 需要约 3.33x，处于 5.0x 上限内
        double[] rect = {0.35, 0.35, 0.65, 0.65};
        controller.lockAnchor(anchor(rect));
        long ns = 0;
        for (int i = 0; i < 80 && zoomRequests.isEmpty(); i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i, ns, 1.0),
                new TrackingUpdate(i, ns, controller.anchor().generation, rectOf(rect), 0.9, null));
        }
        assertEquals(1, zoomRequests.size(), "3.33x 应被允许");
        assertEquals(1.0 / 0.3, zoomRequests.get(0), 1e-2);
    }

    @Test
    @DisplayName("变焦完成后验证通过并标记 autoZoomCompleted，且不再重复变焦")
    void verifyAndComplete() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        driveUntilZoomRequested(new double[] {0.2, 0.2, 0.8, 0.8}, 1.0, 0L);

        long ns = 2_000_000_000L;
        long id = 500;
        double target = zoomRequests.get(0);
        // 变焦后原区域铺满画面
        double[] filled = {0.02, 0.02, 0.98, 0.98};
        for (int i = 0; i < 20; i++) {
            ns += FRAME_NS;
            id++;
            controller.onTrackingUpdate(frame(id, ns, target),
                new TrackingUpdate(id, ns, controller.anchor().generation, rectOf(filled), 0.9, null));
            if (controller.state() == GuidanceState.LOCKED && controller.anchor().isAutoZoomCompleted()) {
                break;
            }
        }
        assertTrue(controller.anchor().isAutoZoomCompleted(), "events=" + events);
        assertEquals(GuidanceState.LOCKED, controller.state());
        assertEquals(1, zoomRequests.size());
    }

    @Test
    @DisplayName("变焦中用户手动接管：立即取消且回到 DEGRADED")
    void manualZoomOverride() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        driveUntilZoomRequested(new double[] {0.2, 0.2, 0.8, 0.8}, 1.0, 0L);
        assertEquals(GuidanceState.AUTO_ZOOMING, controller.state());

        controller.onManualZoomStart(1_000_000_000L);
        assertTrue(events.contains("cancel"));
        assertEquals(GuidanceState.DEGRADED, controller.state());
        assertTrue(controller.isManualZoomActive());
    }

    @Test
    @DisplayName("手动变焦结束后 1 秒内不重新评估对齐")
    void manualZoomSettle() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        controller.onManualZoomStart(0L);
        controller.onManualZoomEnd(FRAME_NS);
        long ns = FRAME_NS;
        for (int i = 0; i < 20; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i, ns, 1.0),
                new TrackingUpdate(i, ns, controller.anchor().generation,
                    rect(0.2, 0.2, 0.8, 0.8), 0.9, null));
        }
        assertTrue(zoomRequests.isEmpty(), "should not zoom within settle window");
    }

    @Test
    @DisplayName("快门在变焦中可用，并停止后续变焦请求")
    void shutterDuringZoom() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        driveUntilZoomRequested(new double[] {0.2, 0.2, 0.8, 0.8}, 1.0, 0L);
        int cancelsBefore = count("cancel");

        assertTrue(controller.onShutter());
        assertEquals(cancelsBefore + 1, count("cancel"));
        assertEquals(GuidanceState.LOCKED, controller.state());
    }

    @Test
    @DisplayName("质量下降进入 DEGRADED，持续无位置进入 LOST 再 RELOCALIZING")
    void degradedAndLost() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));
        int gen = controller.anchor().generation;

        long ns = 0;
        for (int i = 0; i < 6; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i, ns, 1.0),
                new TrackingUpdate(i, ns, gen, rect(0.2, 0.2, 0.8, 0.8), 0.1, null));
        }
        assertEquals(GuidanceState.DEGRADED, controller.state());

        for (int i = 0; i < 20; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i + 100, ns, 1.0),
                TrackingUpdate.lost(i + 100, ns, gen, "INSUFFICIENT_FEATURES"));
        }
        assertTrue(controller.state() == GuidanceState.LOST
                || controller.state() == GuidanceState.RELOCALIZING,
            "state=" + controller.state());
    }

    @Test
    @DisplayName("重定位恢复后回到 LOCKED，且锚点未更换")
    void relocalizationRecovers() {
        controller.startSearching();
        CompositionAnchor a = anchor(new double[] {0.2, 0.2, 0.8, 0.8});
        controller.lockAnchor(a);
        int gen = a.generation;

        long ns = 0;
        for (int i = 0; i < 20; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i, ns, 1.0), TrackingUpdate.lost(i, ns, gen, "LOST_FEATURES"));
        }
        // 恢复
        for (int i = 0; i < 10; i++) {
            ns += FRAME_NS;
            controller.onTrackingUpdate(frame(i + 200, ns, 1.0),
                new TrackingUpdate(i + 200, ns, gen, rect(0.21, 0.2, 0.81, 0.8), 0.9, null));
        }
        assertEquals(GuidanceState.LOCKED, controller.state());
        assertEquals("a1", controller.anchor().anchorId);
    }

    @Test
    @DisplayName("会话或锚点版本不一致的结果被丢弃")
    void staleResultsDropped() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.2, 0.2, 0.8, 0.8}));

        FramePacket otherSession = new FramePacket("other", 1L, FRAME_NS, 640, 480, 0, 1.0, null);
        GuidanceResult r1 = controller.onTrackingUpdate(otherSession,
            new TrackingUpdate(1L, FRAME_NS, controller.anchor().generation,
                rect(0.4, 0.4, 0.6, 0.6), 0.9, null));
        assertEquals("SESSION_MISMATCH", r1.reasonCode);

        GuidanceResult r2 = controller.onTrackingUpdate(frame(2L, 2 * FRAME_NS, 1.0),
            new TrackingUpdate(2L, 2 * FRAME_NS, 999, rect(0.4, 0.4, 0.6, 0.6), 0.9, null));
        assertEquals("ANCHOR_GENERATION_MISMATCH", r2.reasonCode);
    }

    @Test
    @DisplayName("方向提示指向目标所在侧，无效时不显示")
    void directionHint() {
        controller.startSearching();
        controller.lockAnchor(anchor(new double[] {0.55, 0.2, 1.0, 0.7}));
        int gen = controller.anchor().generation;
        GuidanceResult r = controller.onTrackingUpdate(frame(1, FRAME_NS, 1.0),
            new TrackingUpdate(1, FRAME_NS, gen, rect(0.55, 0.2, 1.0, 0.7), 0.9, null));
        assertTrue(r.directionValid);
        assertEquals(Direction.RIGHT, r.direction);
    }

    @Test
    @DisplayName("显示框裁切到画幅，但存储四边形保留出画部分")
    void displayClippedButStoredFull() {
        controller.startSearching();
        double[] outOfView = {0.7, 0.2, 1.3, 0.8};
        controller.lockAnchor(anchor(outOfView));
        int gen = controller.anchor().generation;
        GuidanceResult r = controller.onTrackingUpdate(frame(1, FRAME_NS, 1.0),
            new TrackingUpdate(1, FRAME_NS, gen, rectOf(outOfView), 0.9, null));

        assertEquals(1.3, r.anchorQuad[2], 1e-6);
        assertTrue(r.displayQuad[2] <= 1.0 + 1e-9);
        assertTrue(r.visibleRatio < 0.6);
        assertNull(r.direction, "方向提示在可见比例不足时应为 null");
    }

    private int count(String prefix) {
        return (int) events.stream().filter(e -> e.startsWith(prefix)).count();
    }
}
