package com.classic.camera

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import java.io.BufferedReader
import java.io.File

object LutUtils {
    const val LUT_SIZE = 33

    fun loadCubeFile(file: File): FloatArray? {
        val total = LUT_SIZE * LUT_SIZE * LUT_SIZE
        val lut = FloatArray(total * 3)
        var idx = 0
        val reader: BufferedReader = file.bufferedReader()
        var line = reader.readLine()
        while (line != null && idx < total) {
            var p = 0
            val len = line.length
            // 跳过前导空白
            while (p < len && line[p] <= ' ') p++
            if (p >= len || line[p] == '#') {
                line = reader.readLine()
                continue
            }
            // 跳过标题行（非纯数字开头）
            val c = line[p]
            if (c != '-' && c != '+' && c != '.' && (c < '0' || c > '9')) {
                line = reader.readLine()
                continue
            }
            // parse r
            val rStart = p
            while (p < len && line[p] > ' ') p++
            val r = line.substring(rStart, p).toFloat()
            // parse g
            while (p < len && line[p] <= ' ') p++
            val gStart = p
            while (p < len && line[p] > ' ') p++
            val g = line.substring(gStart, p).toFloat()
            // parse b
            while (p < len && line[p] <= ' ') p++
            val bStart = p
            while (p < len && line[p] > ' ') p++
            val b = line.substring(bStart, p).toFloat()
            val base = idx * 3
            lut[base] = r
            lut[base + 1] = g
            lut[base + 2] = b
            idx++
            line = reader.readLine()
        }
        reader.close()
        return if (idx == total) lut else null
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

    private const val PREFS_NAME = "preset_filters"
    private const val KEY_SEEDED = "seeded"

    fun seedPresetFilters(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return

        val destDir = context.getExternalFilesDir("filters") ?: context.filesDir
        destDir.mkdirs()

        try {
            val list = context.assets.list("filters") ?: return
            for (name in list) {
                if (!name.endsWith(".cube")) continue
                val destFile = File(destDir, name)
                if (destFile.exists()) continue
                context.assets.open("filters/$name").use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        } catch (e: Exception) {
            android.util.Log.e("LutUtils", "seed preset filters failed", e)
        }
    }
}
