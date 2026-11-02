package com.aicamera.vision;

/** 特征提取与光流参数（规格 §8.2 / §8.3）。 */
public final class TrackerConfig {

    // goodFeaturesToTrack
    public int maxCorners = 300;
    public double qualityLevel = 0.01;
    public double minDistance = 8.0;
    public int blockSize = 7;

    // calcOpticalFlowPyrLK
    public int lkWinSize = 21;
    public int lkMaxLevel = 3;
    public int lkMaxIterations = 30;
    public double lkEpsilon = 0.01;
    /** 前后向误差阈值（像素）。规格初始值 1.5，实测放宽至 2.0 以容忍亚像素重采样。 */
    public double fbErrorThreshold = 2.0;

    /** 参考帧直连跟踪可接受的最低点数；低于此值回退到上一帧链路。 */
    public int referenceDirectMinPoints = 45;
    /** 滚动关键帧可用点数低于此值时，直接用当前帧重建关键帧。 */
    public int rollingRefreshMinPoints = 90;

    // 几何估计可靠性门限（§8.4）
    public int minTrackedPoints = 30;
    public double minInlierRatio = 0.60;
    public double maxMedianReprojectionError = 2.5;
    public double ransacReprojectionThreshold = 3.0;
    /** 有效点最小空间分布（归一化标准差）。 */
    public double minSpatialSpread = 0.02;

    // 质量评分
    public double minQuality = 0.0;
    public double maxQuality = 1.0;
    /** 几何可用但指标不达标时质量评分的上限。 */
    public double degradedQualityCeiling = 0.34;

    // 纹理门控（防止暗光噪声被当作可靠特征）
    /** 目标区域平均背向差分能量下限；低于此值认为画面无有效纹理。 */
    public double minTextureEnergy = 6.0;
    /** 目标区域灰度标准差下限。 */
    public double minGrayStdDev = 6.0;
}
