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

    fun sample(
        origBitmap: Bitmap,
        filtBitmap: Bitmap,
        seenOrigColors: MutableMap<Int, Int>,
        allFiltPixels: MutableList<Int>
    ): Triple<IntArray, IntArray, Int> {
        val width = origBitmap.width
        val height = origBitmap.height

        val origList = mutableListOf<Int>()
        val filtList = mutableListOf<Int>()

        val origRow = IntArray(width)
        val filtRow = IntArray(width)

        for (y in 0 until height) {
            origBitmap.getPixels(origRow, 0, width, 0, y, width, 1)
            filtBitmap.getPixels(filtRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val origColor = origRow[x]
                val origRgb = origColor and 0x00FFFFFF
                val existingIndex = seenOrigColors[origRgb]
                if (existingIndex != null) {
                    if (existingIndex < allFiltPixels.size) {
                        allFiltPixels[existingIndex] = averageColor(allFiltPixels[existingIndex], filtRow[x] and 0x00FFFFFF)
                    } else {
                        val localIdx = existingIndex - allFiltPixels.size
                        filtList[localIdx] = averageColor(filtList[localIdx], filtRow[x] and 0x00FFFFFF)
                    }
                } else {
                    seenOrigColors[origRgb] = allFiltPixels.size + filtList.size
                    origList.add(origRgb)
                    filtList.add(filtRow[x] and 0x00FFFFFF)
                }
            }
        }

        val count = origList.size
        return Triple(origList.toIntArray(), filtList.toIntArray(), count)
    }

    private fun averageColor(a: Int, b: Int): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return ((ar + br) / 2 shl 16) or ((ag + bg) / 2 shl 8) or ((ab + bb) / 2)
    }
}
