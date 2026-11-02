package com.aicamera.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;

/** ORB 特征匹配与重定位（规格 §8.5），与跟踪器配合恢复丢失目标。 */
public final class OrbRelocalizer {

    private final Mat referenceDescriptors;
    private final List<Point> referencePoints;
    private final ORB orb;
    private final BFMatcher matcher;
    private final Mat emptyMask = new Mat();

    /**
     * @param referenceGray 参考帧灰度图
     * @param mask          仅在这些像素区域提取特征；null 表示全图
     */
    public OrbRelocalizer(Mat referenceGray, Mat mask) {
        this.orb = ORB.create(1000, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20);
        // crossCheck 与 knnMatch 互斥（batchDistance 会断言 K==1），必须使用 false
        this.matcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, false);

        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        orb.detectAndCompute(referenceGray, mask == null ? emptyMask : mask, keypoints, descriptors);

        this.referenceDescriptors = descriptors;
        List<Point> pts = new ArrayList<>();
        for (KeyPoint kp : keypoints.toArray()) {
            pts.add(kp.pt);
        }
        this.referencePoints = pts;
        keypoints.release();
    }

    public int referenceKeypointCount() {
        return referencePoints.size();
    }

    /**
     * 在当前帧中查找参考帧的对应点。
     *
     * @param currentGray 当前帧灰度图
     * @param ratioTest  knn ratio test 阈值，建议 0.75
     * @return 匹配点对；数量不足时返回空列表
     */
    public Matches match(Mat currentGray, double ratioTest) {
        if (referenceDescriptors.empty()) {
            return new Matches(new ArrayList<>(), new ArrayList<>(), 0);
        }
        MatOfKeyPoint curKeypoints = new MatOfKeyPoint();
        Mat curDesc = new Mat();
        orb.detectAndCompute(currentGray, emptyMask, curKeypoints, curDesc);
        KeyPoint[] curKps = curKeypoints.toArray();

        if (curDesc.empty()) {
            curKeypoints.release();
            curDesc.release();
            return new Matches(new ArrayList<>(), new ArrayList<>(), 0);
        }

        List<MatOfDMatch> knn = new ArrayList<>();
        matcher.knnMatch(referenceDescriptors, curDesc, knn, 2);

        List<Point> src = new ArrayList<>();
        List<Point> dst = new ArrayList<>();
        for (MatOfDMatch group : knn) {
            DMatch[] arr = group.toArray();
            group.release();
            if (arr.length < 2) {
                continue;
            }
            DMatch best = arr[0];
            DMatch second = arr[1];
            // Lowe ratio test + 绝对距离门限
            if (best.distance < ratioTest * second.distance
                && best.distance < 64.0f
                && best.queryIdx >= 0 && best.queryIdx < referencePoints.size()
                && best.trainIdx >= 0 && best.trainIdx < curKps.length) {
                src.add(referencePoints.get(best.queryIdx));
                dst.add(curKps[best.trainIdx].pt);
            }
        }
        curKeypoints.release();
        curDesc.release();
        return new Matches(src, dst, curKps.length);
    }

    public void release() {
        referenceDescriptors.release();
        emptyMask.release();
    }

    /** 匹配结果。 */
    public static final class Matches {
        public final List<Point> referencePoints;
        public final List<Point> currentPoints;
        public final int currentKeypointCount;

        Matches(List<Point> referencePoints, List<Point> currentPoints, int currentKeypointCount) {
            this.referencePoints = referencePoints;
            this.currentPoints = currentPoints;
            this.currentKeypointCount = currentKeypointCount;
        }

        public int size() {
            return Math.min(referencePoints.size(), currentPoints.size());
        }

        public MatOfPoint2f srcMat() {
            return new MatOfPoint2f(referencePoints.toArray(new Point[0]));
        }

        public MatOfPoint2f dstMat() {
            return new MatOfPoint2f(currentPoints.toArray(new Point[0]));
        }
    }
}
