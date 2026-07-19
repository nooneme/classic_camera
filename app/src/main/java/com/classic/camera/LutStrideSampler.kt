package com.classic.camera

import android.graphics.Bitmap
import kotlin.random.Random

object LutStrideSampler {

    fun sample(
        origBitmap: Bitmap,
        filtBitmap: Bitmap,
        targetSamples: Int = 3_000_000
    ): Triple<IntArray, IntArray, Int> {
        val width = origBitmap.width
        val height = origBitmap.height
        val totalPixels = width.toLong() * height.toLong()
        val count = minOf(targetSamples, totalPixels.toInt())

        val origSamples = IntArray(count)
        val filtSamples = IntArray(count)

        val origRow = IntArray(width)
        val filtRow = IntArray(width)

        val rng = Random(System.nanoTime())
        var i = 0

        if (count.toLong() == totalPixels) {
            for (y in 0 until height) {
                origBitmap.getPixels(origRow, 0, width, 0, y, width, 1)
                filtBitmap.getPixels(filtRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    origSamples[i] = origRow[x]; filtSamples[i] = filtRow[x]; i++
                }
            }
        } else {
            while (i < count) {
                val y = rng.nextInt(height)
                origBitmap.getPixels(origRow, 0, width, 0, y, width, 1)
                filtBitmap.getPixels(filtRow, 0, width, 0, y, width, 1)
                repeat(count - i) {
                    val x = rng.nextInt(width)
                    origSamples[i] = origRow[x]; filtSamples[i] = filtRow[x]; i++
                }
            }
        }

        return Triple(origSamples, filtSamples, count)
    }
}
