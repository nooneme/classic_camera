package com.classic.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

object ImageUtils {
    private val TAG = "ClassicCamera"

    fun loadBitmap(data: ByteArray, sampleSize: Int = 1): Bitmap? {
        if (sampleSize <= 1) {
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    fun loadBitmaps(dataList: List<ByteArray>, sampleSize: Int = 1): List<Bitmap> {
        val result = mutableListOf<Bitmap>()
        for (data in dataList) {
            val bmp = loadBitmap(data, sampleSize)
            if (bmp != null) {
                result.add(bmp)
            } else {
                Log.e(TAG, "loadBitmaps: failed to decode bitmap")
            }
        }
        return result
    }
}
