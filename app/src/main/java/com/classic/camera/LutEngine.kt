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
     * 移动最小二乘（MLS）拟合 33×33×33 3D LUT。
     * 样本映射到已覆盖体素，空洞通过多轮迭代逐层填充：
     * 每轮用已填充体素做数据，对该层空洞做距离加权的局部二次多项式拟合。
     *
     * @param origPixels  原图 ARGB 像素数组
     * @param filtPixels  滤镜图 ARGB 像素数组
     * @param numPixels   像素数量
     * @param outLutArray 预分配的 FloatArray(33*33*33*3)，接收 LUT 数据
     * @param outStats    预分配的 FloatArray(9)，接收 [平均误差, 平均误差, 最大误差, 最大误差, 最差输入R, G, B, 二次拟合数, 线性拟合数]
     * @return 最大误差 (0~1)
     */
    external fun fitMlsLut(
        origPixels: IntArray,
        filtPixels: IntArray,
        numPixels: Int,
        outLutArray: FloatArray,
        outStats: FloatArray
    ): Float
}
