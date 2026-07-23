package com.classic.camera

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.random.Random

object LutThumbnail {

    private var sessionR = Random.nextFloat()
    private var sessionG = Random.nextFloat()
    private var sessionB = Random.nextFloat()

    fun resetSession() {
        sessionR = Random.nextFloat()
        sessionG = Random.nextFloat()
        sessionB = Random.nextFloat()
    }

    fun generate(lut: FloatArray, size: Int): Bitmap {
        val outR = lookupLutChannel(lut, 33, sessionR, sessionG, sessionB, 0)
        val outG = lookupLutChannel(lut, 33, sessionR, sessionG, sessionB, 1)
        val outB = lookupLutChannel(lut, 33, sessionR, sessionG, sessionB, 2)
        val color = Color.rgb(
            (outR * 255).toInt().coerceIn(0, 255),
            (outG * 255).toInt().coerceIn(0, 255),
            (outB * 255).toInt().coerceIn(0, 255)
        )
        val px = IntArray(size * size) { color }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
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
}
