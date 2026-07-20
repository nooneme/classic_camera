package com.classic.camera

import android.util.Log

object LutEngine {
    private const val TAG = "LutEngine"
    const val LUT_SIZE = 33

    init {
        try {
            System.loadLibrary("lut_generator")
            Log.d(TAG, "native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native library", e)
            throw e
        }
    }

    /**
     * 从原图和滤镜图的像素数组生成 33×33×33 3D LUT。
     *
     * @param origPixels      原图 ARGB 像素数组
     * @param filtPixels      滤镜图 ARGB 像素数组
     * @param numPixels       像素数量
     * @param outLutArray     预分配的 FloatArray(33*33*33*3)，接收 LUT 数据
     * @param outCoveredArray 预分配的 BooleanArray(33*33*33)，接收每个体素是否被覆盖
     * @return 色彩覆盖率 (0.0 ~ 1.0)
     */
    external fun generateLutAndCheckCoverage(
        origPixels: IntArray,
        filtPixels: IntArray,
        numPixels: Int,
        outLutArray: FloatArray,
        outCoveredArray: BooleanArray
    ): Float

    /**
     * 三次多项式回归拟合 33×33×33 3D LUT。
     * 将颜色映射建模为 20 项三次多项式偏移场，比逐体素采样需要更少的样本，
     * 且能自然外推到所有颜色空间。
     *
     * @param origPixels  原图 ARGB 像素数组
     * @param filtPixels  滤镜图 ARGB 像素数组
     * @param numPixels   像素数量
     * @param outLutArray 预分配的 FloatArray(33*33*33*3)，接收 LUT 数据
     * @param outStats    预分配的 FloatArray(7)，接收 [训练平均, 验证平均, 训练最大, 验证最大, 最差输入R, G, B]
     * @return 验证集最大误差 (0~1)
     */
    external fun fitPolynomialLut(
        origPixels: IntArray,
        filtPixels: IntArray,
        numPixels: Int,
        outLutArray: FloatArray,
        outStats: FloatArray
    ): Float
}
