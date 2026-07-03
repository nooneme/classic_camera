package com.classic.camera

import android.graphics.Bitmap
import android.util.Log

interface ApplyFunction {
    fun apply(x: Int, y: Int, inBitmap: Bitmap?, outBitmap: Bitmap,
              inPixels: IntArray?, outPixels: IntArray)
}

object JavaImageProcessing {
    private val TAG = "JavaImageProcessing"

    fun applyFunction(
        function: ApplyFunction,
        inBitmap: Bitmap?,
        outBitmap: Bitmap,
        left: Int, top: Int, width: Int, height: Int
    ) {
        val outPixels = IntArray(width * height)
        var inPixels: IntArray? = null
        if (inBitmap != null) {
            inPixels = IntArray(width * height)
            inBitmap.getPixels(inPixels, 0, width, left, top, width, height)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                function.apply(x, y, inBitmap, outBitmap, inPixels, outPixels)
            }
        }

        outBitmap.setPixels(outPixels, 0, width, left, top, width, height)
    }

    fun applyFunction(
        function: ApplyFunction,
        inBitmap: Bitmap?,
        outBitmap: Bitmap,
        left: Int, top: Int, width: Int, height: Int,
        offsetX: Int, offsetY: Int
    ) {
        val outPixels = IntArray(width * height)
        var inPixels: IntArray? = null
        if (inBitmap != null) {
            inPixels = IntArray(width * height)
            inBitmap.getPixels(inPixels, 0, width, left, top, width, height)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                function.apply(x, y, inBitmap, outBitmap, inPixels, outPixels)
            }
        }

        outBitmap.setPixels(outPixels, 0, width, left, top, width, height)
    }
}
