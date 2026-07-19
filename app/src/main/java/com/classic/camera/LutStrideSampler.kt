package com.classic.camera

import android.graphics.Bitmap

object LutStrideSampler {

    fun sample(
        origBitmap: Bitmap,
        filtBitmap: Bitmap,
        seenOrigColors: MutableSet<Int>
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
                if (origColor in seenOrigColors) continue
                seenOrigColors.add(origColor)
                origList.add(origColor)
                filtList.add(filtRow[x])
            }
        }

        val count = origList.size
        return Triple(origList.toIntArray(), filtList.toIntArray(), count)
    }
}
