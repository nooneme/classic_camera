package com.classic.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.security.MessageDigest

object LutThumbnail {

    private var baseBitmap: Bitmap? = null
    private var cacheDir: File? = null

    private fun getBaseBitmap(context: Context, size: Int): Bitmap {
        baseBitmap?.let {
            if (it.width == size && it.height == size) return it
        }
        val bmp = BitmapFactory.decodeStream(
            context.assets.open("lut_thumb_base.jpg")
        ) ?: throw IllegalStateException("Cannot load lut_thumb_base.jpg from assets")
        val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
        if (bmp != scaled) bmp.recycle()
        baseBitmap = scaled
        return scaled
    }

    private fun getCacheDir(context: Context): File {
        val dir = cacheDir ?: File(context.cacheDir, "lut_thumbs").also { cacheDir = it }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cacheKey(lutFile: File): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(lutFile.absolutePath.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun generate(lut: FloatArray, size: Int, context: Context, lutFile: File? = null): Bitmap {
        if (lutFile != null) {
            val cache = File(getCacheDir(context), "${cacheKey(lutFile)}.png")
            if (cache.exists()) {
                val cached = BitmapFactory.decodeFile(cache.absolutePath)
                if (cached != null) return cached
            }
        }
        val base = getBaseBitmap(context, size)
        val pixels = IntArray(size * size)
        base.getPixels(pixels, 0, size, 0, 0, size, size)

        val lutSize = LutUtils.lutSizeOf(lut).takeIf { it > 0 } ?: 33
        for (i in pixels.indices) {
            val argb = pixels[i]
            val r = Color.red(argb)
            val g = Color.green(argb)
            val b = Color.blue(argb)
            val outR = lookupLutChannel(lut, lutSize, r / 255f, g / 255f, b / 255f, 0)
            val outG = lookupLutChannel(lut, lutSize, r / 255f, g / 255f, b / 255f, 1)
            val outB = lookupLutChannel(lut, lutSize, r / 255f, g / 255f, b / 255f, 2)
            pixels[i] = Color.rgb(
                (outR * 255).toInt().coerceIn(0, 255),
                (outG * 255).toInt().coerceIn(0, 255),
                (outB * 255).toInt().coerceIn(0, 255)
            )
        }

        val result = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)

        if (lutFile != null) {
            val cache = File(getCacheDir(context), "${cacheKey(lutFile)}.png")
            cache.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        return result
    }

    private fun lookupLutChannel(lut: FloatArray, lutSize: Int,
                                 r: Float, g: Float, b: Float, ch: Int): Float {
        val fR = r * (lutSize - 1)
        val fG = g * (lutSize - 1)
        val fB = b * (lutSize - 1)
        val x0 = fR.toInt().coerceIn(0, lutSize - 2)
        val y0 = fG.toInt().coerceIn(0, lutSize - 2)
        val z0 = fB.toInt().coerceIn(0, lutSize - 2)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val z1 = z0 + 1
        val dx = fR - x0
        val dy = fG - y0
        val dz = fB - z0

        fun idx(x: Int, y: Int, z: Int) = (z * lutSize * lutSize + y * lutSize + x) * 3 + ch

        val v000 = lut[idx(x0, y0, z0)]
        val v100 = lut[idx(x1, y0, z0)]
        val v010 = lut[idx(x0, y1, z0)]
        val v110 = lut[idx(x1, y1, z0)]
        val v001 = lut[idx(x0, y0, z1)]
        val v101 = lut[idx(x1, y0, z1)]
        val v011 = lut[idx(x0, y1, z1)]
        val v111 = lut[idx(x1, y1, z1)]

        val c00 = v000 * (1 - dx) + v100 * dx
        val c10 = v010 * (1 - dx) + v110 * dx
        val c01 = v001 * (1 - dx) + v101 * dx
        val c11 = v011 * (1 - dx) + v111 * dx
        val c0 = c00 * (1 - dy) + c10 * dy
        val c1 = c01 * (1 - dy) + c11 * dy
        return c0 * (1 - dz) + c1 * dz
    }

    fun resetSession() {
        // no longer needed — using fixed base image
    }

    /** 同步读取磁盘上已缓存的缩略图（主线程安全，图片极小）。未缓存时返回 null。 */
    fun cachedThumbnail(context: Context, lutFile: File): Bitmap? {
        return try {
            val cache = File(getCacheDir(context), "${cacheKey(lutFile)}.png")
            if (cache.exists()) BitmapFactory.decodeFile(cache.absolutePath) else null
        } catch (e: Exception) { null }
    }
}
