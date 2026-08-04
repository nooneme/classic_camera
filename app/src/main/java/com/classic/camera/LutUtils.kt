package com.classic.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.File

object LutUtils {
    const val LUT_SIZE = 33

    /** 从 3D LUT 数据反推网格边长（data.size == size³ * 3）。不是完美立方体时返回 0。 */
    fun lutSizeOf(data: FloatArray): Int {
        if (data.isEmpty()) return 0
        val n = data.size / 3
        var s = 1
        while (s * s * s < n) s++
        return if (s * s * s == n) s else 0
    }

    /** 滤镜公共目录：Documents/ClassicCamera/filters（手机文件管理器可直接访问） */
    fun filtersDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ClassicCamera/filters")

    /** 是否已获得读写公共目录所需的存储权限。 */
    fun isStorageAuthorized(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    /** 请求存储权限：API 30+ 跳转系统「所有文件访问」设置页；API 26-29 走运行时 WRITE 权限。 */
    fun requestStorageAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:" + activity.packageName))
                )
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 103)
        }
    }

    private const val PREFS = "filter_storage"
    private const val KEY_ASKED = "asked"

    /** 首次进入且未授权时提示请求；已提示过则不再反复弹系统设置，避免骚扰。 */
    fun shouldRequestStorage(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        return true
    }

    fun loadCubeFile(file: File): FloatArray? {
        var size = 0
        val floats = ArrayList<Float>(35937 * 3)
        file.bufferedReader().use { reader ->
            var inData = false
            while (true) {
                val line = reader.readLine() ?: break
                val t = line.trim()
                if (!inData) {
                    if (t.startsWith("LUT_3D_SIZE", ignoreCase = true)) {
                        val tok = t.split(Regex("\\s+"))
                        size = tok.getOrNull(1)?.toIntOrNull() ?: 0
                    } else if (size > 0 &&
                        t.isNotEmpty() && !t.startsWith("#") &&
                        !t.startsWith("TITLE", ignoreCase = true) &&
                        !t.startsWith("DOMAIN", ignoreCase = true)) {
                        inData = true
                    } else {
                        continue
                    }
                }
                if (t.isEmpty() || t.startsWith("#")) continue
                if (floats.size >= size * size * size * 3) break
                val tok = t.split(Regex("\\s+"))
                if (tok.size < 3) continue
                for (k in 0..2) floats.add(tok[k].toFloatOrNull() ?: return null)
            }
        }
        return if (size > 0 && floats.size == size * size * size * 3) floats.toFloatArray() else null
    }

    fun createLutBitmap(lutFloatArray: FloatArray): Bitmap {
        val lutSize = lutSizeOf(lutFloatArray).takeIf { it > 0 } ?: LUT_SIZE
        val width = lutSize * lutSize
        val height = lutSize
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val bSlice = x / lutSize
                val rIndex = x % lutSize
                val gIndex = y
                val index = (bSlice * lutSize * lutSize + gIndex * lutSize + rIndex) * 3
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
    private const val KEY_SEEDED = "seeded_v2"

    fun seedPresetFilters(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return

        if (!isStorageAuthorized(context)) return
        val destDir = filtersDir()
        if (!destDir.exists() && !destDir.mkdirs()) return

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
