# AI 相机构图引导（阶段一原型）

按《开发文档》规格实现的 Android 原型。**阶段一只打通交互链路，不接入任何 AI 模型**，
用于证明相机流、坐标映射、原区域跟踪、部分出画、对齐引导与自动变焦、原片保存是正确的。

> 阶段二（YOLOX 主体检测 + CropRankLite 构图排序）尚未开始。
> 当前锁定的是固定测试区域，不是 AI 推荐构图。

## 当前状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| 阶段一 A | 纯算法内核（几何 / 状态机 / 对齐 / 变焦数学） | 完成，43 项单测通过 |
| 阶段一 B | OpenCV 跟踪（KLT / RANSAC / ORB 重定位） | 完成，15 项单测通过 |
| 阶段一 C | Camera2 应用与 APK | 完成，真机验证通过 |
| 阶段二 | 自动构图 | 未开始 |
| 阶段三 | 自动变焦完整仲裁 | 部分完成（已含引导与执行） |

真机验证环境：HUAWEI BLK-AL00 / Android 12（API 31）。

## 交互流程

```
打开应用 → 只显示预览，评估画面稳定性（不自动锁定）
    ↓ 「锁定构图」按钮由灰变可用（需连续 8 帧稳定）
用户点击「锁定构图」
    ↓ 锁定当前参考帧，建立锚点
显示绿框 + 对齐引导线（从框中心指向画面中心）
    ↓ 用户把框转到画面中心，持续 600ms / ≥10 帧
自动变焦（对数插值平滑推进）
    ↓ 到达目标倍率，验证通过
提示「构图已对齐」，可单击拍照
```

单击画面拍照（保存相机原始输出，不做 App 裁剪），双指缩放为手动变焦。

## 工程结构

```
core/     纯 Java 算法内核，零 Android / OpenCV 依赖，可在桌面单测
  Mat3, Quad, Polygons        坐标系与几何
  GuidanceController          状态机 + 对齐闸门 + 变焦仲裁
  AlignmentEvaluator/Stabilizer  对齐判定与时间连续性
  ZoomMath, CompositionAnchor 变焦数学与不可变锚点

vision/   OpenCV 跟踪层
  RegionTracker      关键帧链 + KLT + RANSAC，抗漂移
  OrbRelocalizer     ORB 重定位
  ZoomPredictor      变焦扩张光流初值
  StabilityMonitor   画面稳定度评估

app/      CameraX 应用
  MainActivity       交互与相机绑定
  GuidanceOverlay    框、引导线、状态提示
  TrackingPipeline   流水线：预处理 → 稳定度 → 锁定 → 跟踪
  AspectFrameLayout  固定取景比例容器，保证坐标对齐
  FrameNormalizer    YUV→灰度、旋转变换、降采样
  ZoomController     变焦仲裁与平滑推进
```

## 构建

需要 JDK 17 与 Android SDK（compileSdk 34、minSdk 30）。

```bash
# 单元测试（不需要设备）
./gradlew :core:test :vision:test

# 构建 APK
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（arm64-v8a / armeabi-v7a）。

## 关键设计决策

**跟踪为什么用关键帧链而不是逐帧增量**

逐帧把增量叠加到上一帧结果上，会把每帧的估计误差持续累积成可见漂移；
而只用参考帧直连虽然零漂移，但能覆盖的帧间位移受 LK 金字塔范围限制。

因此采用：优先参考帧直连（零漂移），直连不足时用滚动关键帧（位移小、可靠），
并限制链路深度。滚动关键帧点数衰减时直接用当前帧重建，避免点数耗尽后必然丢失。

**变焦后必须重建参考基准**

变焦会把参考帧的角点大量推出画面（实测变焦到 1.65× 后 `pts=0`）。
若不重建，后续每帧都匹配不足，跟踪永久失效。变焦完成时把当前帧升格为新基础关键帧，
目标身份由锚点语义延续，不视为更换目标。

**预览与引导层必须同坐标系**

用固定比例容器承载预览与绘制层，视频精确填满容器，避免猜测 letterbox 尺寸导致框错位。

## 验证指标

在具有像素级真值的合成序列上测量（`vision/src/test`）：

| 场景 | 指标 |
|---|---|
| 轻微平移 | 角点误差 0.015 px |
| 小幅转动（累计 6°） | 角点误差 0.042 px |
| 垂直平移 | 角点误差 < 0.001 px |
| 连续放大到 1.67× | 角点误差 2.87 px |
| 部分出画 | 可见比例误差 2e-6 |
| 变焦后重建基准 | 15/15 帧命中，误差 0.05 px |
| ORB 重定位（12° + 平移） | 角点误差 0.85 px |

## 已知限制

- 目标区域是固定测试框（`TrackingPipeline#anchorRect`），不是 AI 选择的主体。
- 未集成 YOLOX-Nano 与 CropRankLite，构图质量指标尚不可测。
- 未实现 VIO / 深度，明显平移与三维遮挡场景不支持。
- 自动变焦上限默认 5.0×，运行时按 `min(5.0, 设备上限)` 裁剪。
- 性能预算（P95 延迟、功耗、温升）尚未按规格 §14.2 实测。

## 第三方组件许可

| 组件 | 许可 |
|---|---|
| OpenCV 4.9.0 | Apache-2.0 |
| AndroidX / CameraX | Apache-2.0 |

发布前需按规格 §16 补齐 `THIRD_PARTY_NOTICES`、`MODEL_LICENSES/` 与 `model_manifest.json`。
