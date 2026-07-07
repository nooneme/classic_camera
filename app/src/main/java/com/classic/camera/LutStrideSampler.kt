package com.classic.camera

import android.graphics.Bitmap
import kotlin.math.sqrt

object LutStrideSampler {

    fun sample(
        origBitmap: Bitmap,
        filtBitmap: Bitmap,
        targetSamples: Int = 100000
    ): Triple<IntArray, IntArray, Int> {
        val width = origBitmap.width
        val height = origBitmap.height
        val totalPixels = width.toLong() * height.toLong()

        val step = if (totalPixels <= targetSamples) {
            1
        } else {
            sqrt((totalPixels / targetSamples).toDouble()).toInt().coerceAtLeast(1)
        }

        val sampleW = (width + step - 1) / step
        val sampleH = (height + step - 1) / step
        val estimatedCount = sampleW * sampleH

        val origSamples = IntArray(estimatedCount)
        val filtSamples = IntArray(estimatedCount)

        val origRowBuffer = IntArray(width)
        val filtRowBuffer = IntArray(width)

        var sampleIndex = 0

        var y = 0
        while (y < height) {
            origBitmap.getPixels(origRowBuffer, 0, width, 0, y, width, 1)
            filtBitmap.getPixels(filtRowBuffer, 0, width, 0, y, width, 1)

            var x = 0
            while (x < width) {
                origSamples[sampleIndex] = origRowBuffer[x]
                filtSamples[sampleIndex] = filtRowBuffer[x]
                sampleIndex++
                x += step
            }
            y += step
        }

        return Triple(
            origSamples.copyOf(sampleIndex),
            filtSamples.copyOf(sampleIndex),
            sampleIndex
        )
    }
}
