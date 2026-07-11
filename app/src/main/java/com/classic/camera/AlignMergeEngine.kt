package com.classic.camera

import android.util.Log

object AlignMergeEngine {
    private const val TAG = "AlignMergeEngine"

    init {
        try {
            System.loadLibrary("align_merge")
            Log.d(TAG, "native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native library", e)
        }
    }

    /**
     * 对齐多帧 RAW Bayer 数据。
     * @param frames ShortArray 数组，每帧为 w*h 的 16-bit Bayer 数据
     * @param w 图像宽度
     * @param h 图像高度
     * @param numFrames 帧数
     * @return int[] 偏移数组: [frame][ty][tx][dx, dy]
     */
    external fun alignFrames(
        frames: Array<ShortArray>,
        w: Int, h: Int, numFrames: Int
    ): IntArray

    /**
     * 融合多帧 RAW Bayer 数据。
     * @param frames ShortArray 数组
     * @param offsets alignFrames 返回的偏移
     * @param w 图像宽度
     * @param h 图像高度
     * @param numFrames 帧数
     * @param numTx X方向 tile 数
     * @param numTy Y方向 tile 数
     * @return ShortArray 融合后的单帧 Bayer 数据 (w*h)
     */
    external fun mergeFrames(
        frames: Array<ShortArray>,
        offsets: IntArray,
        w: Int, h: Int, numFrames: Int,
        numTx: Int, numTy: Int
    ): ShortArray
}
