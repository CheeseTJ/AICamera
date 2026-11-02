package com.aicamera.vision;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

/** 验证桌面 JVM 上 OpenCV 原生库可加载且基本算子可用。 */
class OpenCvBootstrapTest {

    @Test
    @DisplayName("OpenCV 原生库可加载并执行基本矩阵运算")
    void openCvAvailable() {
        boolean ok = OpenCvLoader.ensureLoaded();
        Assumptions.assumeTrue(ok, "OpenCV 原生库不可用，跳过桌面验证");
        System.out.println("OpenCV version = " + Core.VERSION);

        Mat a = Mat.zeros(4, 4, CvType.CV_8UC1);
        a.put(0, 0, 1, 2, 3, 4);
        double sum = Core.sumElems(a).val[0];
        Assertions.assertEquals(10.0, sum, 1e-6);
        a.release();
    }
}
