package com.classic.camera

import android.graphics.Bitmap

object LutStrideSampler {

    /**
     * 颜色量化位移：每通道右移 Q 位后再聚合，把唯色数硬顶在 2^(3*(8-Q))。
     * Q=2 → 6bit/通道（64 级）→ 最多 262144 个桶，比 33³ LUT 更细，
     * 内存恒定有界，避免全色图（16.7M 唯色）把 HashMap 打爆。
     */
    private const val QUANT_SHIFT = 2

    /** 把 24-bit 原色量化成一个桶 key（用于 HashMap 分组，不改变代表色本身）。 */
    private fun quantKey(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val s = 8 - QUANT_SHIFT
        return ((r shr QUANT_SHIFT) shl (s * 2)) or
               ((g shr QUANT_SHIFT) shl s) or
               (b shr QUANT_SHIFT)
    }

    /**
     * 采样前预处理：仅将两图统一尺寸，不做模糊。
     *
     * 色卡等大图无需去噪，去掉 3×3 box blur 可省下每图两倍宽高 IntArray 的峰值内存。
     *
     * 约定：返回的两图由调用方负责 recycle()；被替换掉的入参已在此处回收，
     * 因此调用方不要再单独 recycle 入参。
     */
    fun preprocess(origBitmap: Bitmap, filtBitmap: Bitmap): Pair<Bitmap, Bitmap> {
        if (origBitmap.width == filtBitmap.width && origBitmap.height == filtBitmap.height) {
            return Pair(origBitmap, filtBitmap)
        }
        val scaledFilt = Bitmap.createScaledBitmap(filtBitmap, origBitmap.width, origBitmap.height, true)
        filtBitmap.recycle()
        return Pair(origBitmap, scaledFilt)
    }

    /**
     * 采样结果：按「唯一原色」聚合，给出该色的平均滤镜输出与出现频次。
     * 三份并行数组长度 = 唯一原色数（远小于像素总数），供 native 频率加权散播。
     */
    data class SampledData(
        val origColors: IntArray,
        val avgFiltColors: IntArray,
        val counts: IntArray
    )

    fun sample(origBitmap: Bitmap, filtBitmap: Bitmap): SampledData {
        val width = origBitmap.width
        val height = origBitmap.height

        val origRow = IntArray(width)
        val filtRow = IntArray(width)

        // 量化桶 key -> 在并行数组中的索引
        val indexByKey = HashMap<Int, Int>()

        // 用原始 int 数组（可增长）代替装箱 List，避免海量 Integer 装箱把堆打爆
        var oArr = IntArray(256)
        var cArr = IntArray(256)
        var sR = IntArray(256)
        var sG = IntArray(256)
        var sB = IntArray(256)
        var n = 0

        fun ensure(cap: Int) {
            if (cap > oArr.size) {
                val ns = oArr.size * 2
                oArr = oArr.copyOf(ns)
                cArr = cArr.copyOf(ns)
                sR = sR.copyOf(ns)
                sG = sG.copyOf(ns)
                sB = sB.copyOf(ns)
            }
        }

        for (y in 0 until height) {
            origBitmap.getPixels(origRow, 0, width, 0, y, width, 1)
            filtBitmap.getPixels(filtRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val origColor = origRow[x] and 0x00FFFFFF
                val key = quantKey(origColor)
                val f = filtRow[x]
                val idx = indexByKey[key]
                if (idx != null) {
                    cArr[idx]++
                    sR[idx] += (f shr 16) and 0xFF
                    sG[idx] += (f shr 8) and 0xFF
                    sB[idx] += f and 0xFF
                } else {
                    ensure(n + 1)
                    indexByKey[key] = n
                    // 代表色用真实首见值，保证落在 0..255 且贴近桶内实际色
                    oArr[n] = origColor
                    cArr[n] = 1
                    sR[n] = (f shr 16) and 0xFF
                    sG[n] = (f shr 8) and 0xFF
                    sB[n] = f and 0xFF
                    n++
                }
            }
        }

        val origArr = IntArray(n)
        val filtArr = IntArray(n)
        val cntArr = IntArray(n)
        for (i in 0 until n) {
            origArr[i] = oArr[i]
            val c = cArr[i]
            cntArr[i] = c
            filtArr[i] = ((sR[i] / c) shl 16) or ((sG[i] / c) shl 8) or (sB[i] / c)
        }
        return SampledData(origArr, filtArr, cntArr)
    }
}
