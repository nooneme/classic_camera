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
     * @param origPixels  原图 ARGB 像素数组
     * @param filtPixels  滤镜图 ARGB 像素数组
     * @param numPixels   像素数量
     * @param outLutArray 预分配的 FloatArray(33*33*33*3)，接收 LUT 数据
     * @return 色彩覆盖率 (0.0 ~ 1.0)
     */
    external fun generateLutAndCheckCoverage(
        origPixels: IntArray,
        filtPixels: IntArray,
        numPixels: Int,
        outLutArray: FloatArray
    ): Float
}
