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
     * 学习会话（分块流式）：内存与像素总数 N 无关，恒定占用体素网格 + 抽样蓄水池。
     *
     * 流程：createSession() → 多次 addChunk() 累积 → finalize() 出 LUT 并释放会话。
     *
     * addChunk 输入为「唯一原色」及其「平均滤镜色」和「出现频次」三份并行数组，
     * 频次用于频率加权（出现越多的颜色影响越大）与误差评估抽样。
     */
    external fun createSession(): Long

    /** 当前会话已覆盖（有样本落入）的 LUT 体素数，用于展示覆盖率；不会改动会话。 */
    external fun coveredNodes(session: Long): Int

    external fun addChunk(
        session: Long,
        origPixels: IntArray,
        filtPixels: IntArray,
        counts: IntArray,
        offset: Int,
        num: Int
    )

    /** @param outLutArray 预分配 FloatArray(33*33*33*3) 接收 LUT；@param outStats 预分配 FloatArray(9)。返回最大误差(0~1)。 */
    external fun finalize(session: Long, outLutArray: FloatArray, outStats: FloatArray): Float
}
