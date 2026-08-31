package com.classic.camera

import android.graphics.Bitmap

object LutStrideSampler {

    /**
     * 采样前预处理：将两图统一尺寸并对各自做 3×3 轻量模糊，抑制滤镜图上的模拟噪点。
     * 返回两图均须调用 recycle()。
     */
    fun preprocess(origBitmap: Bitmap, filtBitmap: Bitmap): Pair<Bitmap, Bitmap> {
        var orig = origBitmap
        var filt = filtBitmap
        if (orig.width != filt.width || orig.height != filt.height) {
            filt = Bitmap.createScaledBitmap(filt, orig.width, orig.height, true)
        }
        orig = boxBlur(orig)
        filt = boxBlur(filt)
        return Pair(orig, filt)
    }

    private fun boxBlur(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val yy = y + dy
                        val xx = x + dx
                        if (yy < 0 || yy >= h || xx < 0 || xx >= w) continue
                        val c = src[yy * w + xx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                    }
                }
                out[y * w + x] = (0xFF shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
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

        // 原色(24-bit) -> 在并行数组中的索引
        val indexByColor = HashMap<Int, Int>()

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
                val f = filtRow[x]
                val idx = indexByColor[origColor]
                if (idx != null) {
                    cArr[idx]++
                    sR[idx] += (f shr 16) and 0xFF
                    sG[idx] += (f shr 8) and 0xFF
                    sB[idx] += f and 0xFF
                } else {
                    ensure(n + 1)
                    indexByColor[origColor] = n
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
