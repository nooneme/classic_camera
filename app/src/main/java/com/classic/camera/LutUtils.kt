package com.classic.camera

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File

object LutUtils {
    const val LUT_SIZE = 33

    fun loadCubeFile(file: File): FloatArray? {
        val lut = FloatArray(LUT_SIZE * LUT_SIZE * LUT_SIZE * 3)
        var idx = 0
        file.forEachLine { line ->
            if (idx >= lut.size) return@forEachLine
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") ||
                trimmed.startsWith("TITLE") || trimmed.startsWith("LUT_3D_SIZE") ||
                trimmed.startsWith("DOMAIN")) return@forEachLine
            val parts = trimmed.split("\\s+".toRegex())
            if (parts.size < 3) return@forEachLine
            lut[idx * 3] = parts[0].toFloatOrNull() ?: return@forEachLine
            lut[idx * 3 + 1] = parts[1].toFloatOrNull() ?: return@forEachLine
            lut[idx * 3 + 2] = parts[2].toFloatOrNull() ?: return@forEachLine
            idx++
        }
        return if (idx == LUT_SIZE * LUT_SIZE * LUT_SIZE) lut else null
    }

    fun createLutBitmap(lutFloatArray: FloatArray): Bitmap {
        val width = LUT_SIZE * LUT_SIZE
        val height = LUT_SIZE
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val bSlice = x / LUT_SIZE
                val rIndex = x % LUT_SIZE
                val gIndex = y
                val index = (bSlice * LUT_SIZE * LUT_SIZE + gIndex * LUT_SIZE + rIndex) * 3
                val r = (lutFloatArray[index] * 255f).toInt().coerceIn(0, 255)
                val g = (lutFloatArray[index + 1] * 255f).toInt().coerceIn(0, 255)
                val b = (lutFloatArray[index + 2] * 255f).toInt().coerceIn(0, 255)
                pixels[y * width + x] = Color.argb(255, r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
