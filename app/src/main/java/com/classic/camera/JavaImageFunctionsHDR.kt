package com.classic.camera

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class RGBfLuminance {
    var fr = 0f
    var fg = 0f
    var fb = 0f
    var lum = 0f

    fun setRGB(fr: Float, fg: Float, fb: Float) {
        this.fr = fr
        this.fg = fg
        this.fb = fb
        this.lum = 0.3f * fr + 0.6f * fg + 0.1f * fb
    }

    fun setRGB(pixels: FloatArray, x: Int, y: Int, width: Int) {
        val idx = (y * width + x) * 3
        fr = pixels[idx]
        fg = pixels[idx + 1]
        fb = pixels[idx + 2]
        lum = 0.3f * fr + 0.6f * fg + 0.1f * fb
    }
}

class AvgApplyFunction(
    private val pixelsRgbfOut: FloatArray,
    private val bitmapNew: Bitmap,
    private val bitmapOrig: Bitmap,
    private val width: Int,
    private val height: Int,
    private val offsetX: Float,
    private val offsetY: Float,
    private val avgFactor: Float,
    private val wienerC: Float,
    private val wienerCCutoff: Float,
    private val pixelsOrigCached: IntArray? = null
) {
    companion object {
        private val executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
        private val DX = intArrayOf(0, -2, 2, -2, 2)
        private val DY = intArrayOf(0, -2, -2, 2, 2)
    }

    private val pixelsNew = IntArray(width * height)
    private val pixelsOrig: IntArray

    init {
        bitmapNew.getPixels(pixelsNew, 0, width, 0, 0, width, height)
        if (pixelsOrigCached != null) {
            pixelsOrig = pixelsOrigCached
        } else {
            val p = IntArray(width * height)
            bitmapOrig.getPixels(p, 0, width, 0, 0, width, height)
            pixelsOrig = p
        }
    }

    fun apply() {
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val chunkHeight = (height + numThreads - 1) / numThreads
        val tasks = mutableListOf<Callable<Unit>>()
        for (t in 0 until numThreads) {
            val yStart = t * chunkHeight
            val yEnd = minOf(yStart + chunkHeight, height)
            if (yStart >= height) break
            tasks.add(Callable { processRows(yStart, yEnd) })
        }
        executor.invokeAll(tasks)
    }

    private fun processRows(yStart: Int, yEnd: Int) {
        val inv255 = 1f / 255f
        for (y in yStart until yEnd) {
            for (x in 0 until width) {
                val indx = (y * width + x) * 3

                // (B+E) bilinear sample new frame at float offset, no allocation
                val xf = x - offsetX
                val yf = y - offsetY
                var frN = 0f; var fgN = 0f; var fbN = 0f
                if (xf >= 0f && xf < (width - 1).toFloat() && yf >= 0f && yf < (height - 1).toFloat()) {
                    val x0 = xf.toInt()
                    val y0 = yf.toInt()
                    val fx = xf - x0
                    val fy = yf - y0
                    val x1 = x0 + 1; val y1 = y0 + 1
                    val p00 = pixelsNew[y0 * width + x0]
                    val p10 = pixelsNew[y0 * width + x1]
                    val p01 = pixelsNew[y1 * width + x0]
                    val p11 = pixelsNew[y1 * width + x1]
                    val r00 = ((p00 shr 16) and 0xFF) * inv255; val g00 = ((p00 shr 8) and 0xFF) * inv255; val b00 = (p00 and 0xFF) * inv255
                    val r10 = ((p10 shr 16) and 0xFF) * inv255; val g10 = ((p10 shr 8) and 0xFF) * inv255; val b10 = (p10 and 0xFF) * inv255
                    val r01 = ((p01 shr 16) and 0xFF) * inv255; val g01 = ((p01 shr 8) and 0xFF) * inv255; val b01 = (p01 and 0xFF) * inv255
                    val r11 = ((p11 shr 16) and 0xFF) * inv255; val g11 = ((p11 shr 8) and 0xFF) * inv255; val b11 = (p11 and 0xFF) * inv255
                    val r0 = r00 + (r10 - r00) * fx; val g0 = g00 + (g10 - g00) * fx; val b0 = b00 + (b10 - b00) * fx
                    val r1 = r01 + (r11 - r01) * fx; val g1 = g01 + (g11 - g01) * fx; val b1 = b01 + (b11 - b01) * fx
                    frN = r0 + (r1 - r0) * fy; fgN = g0 + (g1 - g0) * fy; fbN = b0 + (b1 - b0) * fy
                }

                val po = pixelsOrig[y * width + x]
                val frO = ((po shr 16) and 0xFF) * inv255
                val fgO = ((po shr 8) and 0xFF) * inv255
                val fbO = (po and 0xFF) * inv255

                val frA = pixelsRgbfOut[indx]
                val fgA = pixelsRgbfOut[indx + 1]
                val fbA = pixelsRgbfOut[indx + 2]

                // (B) 5-point Wiener ghost detection — no per-pixel allocations
                var sumL = 0f; var nPixels = 0
                for (k in 0 until 5) {
                    val dpx = DX[k]; val dpy = DY[k]
                    val pxf = x + dpx - offsetX
                    val pyf = y + dpy - offsetY
                    if (pxf >= 0f && pxf < (width - 1).toFloat() && pyf >= 0f && pyf < (height - 1).toFloat()) {
                        val px0 = pxf.toInt()
                        val py0 = pyf.toInt()
                        val fxx = pxf - px0
                        val fyy = pyf - py0
                        val px1 = px0 + 1; val py1 = py0 + 1
                        val n00 = pixelsNew[py0 * width + px0]
                        val n10 = pixelsNew[py0 * width + px1]
                        val n01 = pixelsNew[py1 * width + px0]
                        val n11 = pixelsNew[py1 * width + px1]
                        val rn00 = ((n00 shr 16) and 0xFF) * inv255; val gn00 = ((n00 shr 8) and 0xFF) * inv255; val bn00 = (n00 and 0xFF) * inv255
                        val rn10 = ((n10 shr 16) and 0xFF) * inv255; val gn10 = ((n10 shr 8) and 0xFF) * inv255; val bn10 = (n10 and 0xFF) * inv255
                        val rn01 = ((n01 shr 16) and 0xFF) * inv255; val gn01 = ((n01 shr 8) and 0xFF) * inv255; val bn01 = (n01 and 0xFF) * inv255
                        val rn11 = ((n11 shr 16) and 0xFF) * inv255; val gn11 = ((n11 shr 8) and 0xFF) * inv255; val bn11 = (n11 and 0xFF) * inv255
                        val rn0 = rn00 + (rn10 - rn00) * fxx; val gn0 = gn00 + (gn10 - gn00) * fxx; val bn0 = bn00 + (bn10 - bn00) * fxx
                        val rn1 = rn01 + (rn11 - rn01) * fxx; val gn1 = gn01 + (gn11 - gn01) * fxx; val bn1 = bn01 + (bn11 - bn01) * fxx
                        val rn = rn0 + (rn1 - rn0) * fyy; val gn = gn0 + (gn1 - gn0) * fyy; val bn = bn0 + (bn1 - bn0) * fyy

                        val orig = pixelsOrig[(y + dpy) * width + (x + dpx)]
                        val ro = ((orig shr 16) and 0xFF) * inv255
                        val go = ((orig shr 8) and 0xFF) * inv255
                        val bo = (orig and 0xFF) * inv255

                        val dr = rn - ro; val dg = gn - go; val db = bn - bo
                        sumL += dr * dr + dg * dg + db * db
                        nPixels++
                    }
                }
                val L = sumL / nPixels
                val weight = if (L > wienerCCutoff) 1f else L / (L + wienerC)
                val weight1 = 1f - weight

                val newFr = weight * frO + weight1 * frN
                val newFg = weight * fgO + weight1 * fgN
                val newFb = weight * fbO + weight1 * fbN

                pixelsRgbfOut[indx] = (avgFactor * frA + newFr) / (avgFactor + 1f)
                pixelsRgbfOut[indx + 1] = (avgFactor * fgA + newFg) / (avgFactor + 1f)
                pixelsRgbfOut[indx + 2] = (avgFactor * fbA + newFb) / (avgFactor + 1f)
            }
        }
    }
}

class CreateMTBApplyFunction(
    private val useMtb: Boolean,
    private val medianValue: Int
) {
    fun apply(inBitmap: Bitmap, outBitmap: Bitmap, left: Int, top: Int, width: Int, height: Int) {
        val inPixels = IntArray(width * height)
        inBitmap.getPixels(inPixels, 0, width, left, top, width, height)
        val outPixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = inPixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.3f * r + 0.6f * g + 0.1f * b).toInt()
                val alpha = if (Math.abs(luminance - medianValue) <= 4) {
                    if (useMtb) 0 else 128
                } else {
                    0
                }
                val mtb = if (luminance > medianValue) 255 else 0
                outPixels[y * width + x] = (alpha shl 24) or (mtb shl 16) or (mtb shl 8) or mtb
            }
        }

        outBitmap.setPixels(outPixels, 0, width, left, top, width, height)
    }
}

class AvgBrightenApplyFunction(
    private val pixelsInRgbf: FloatArray,
    private val width: Int,
    private val height: Int,
    private val medianFilterStrength: Float,
    private val wienerC: Float
) {
    companion object {
        private val executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        )
    }

    fun apply(outBitmap: Bitmap) {
        val outPixels = IntArray(width * height)
        // (D) multi-threaded row processing
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val chunkHeight = (height + numThreads - 1) / numThreads
        val tasks = mutableListOf<Callable<Unit>>()
        for (t in 0 until numThreads) {
            val yStart = t * chunkHeight
            val yEnd = minOf(yStart + chunkHeight, height)
            if (yStart >= height) break
            val ys = yStart; val ye = yEnd
            tasks.add(Callable { processRows(ys, ye, outPixels) })
        }
        executor.invokeAll(tasks)
        outBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
    }

    private fun processRows(yStart: Int, yEnd: Int, outPixels: IntArray) {
        val rgbfLuminances = Array(5) { RGBfLuminance() }
        for (y in yStart until yEnd) {
            for (x in 0 until width) {
                val indx = (y * width + x) * 3
                var fr = pixelsInRgbf[indx]
                var fg = pixelsInRgbf[indx + 1]
                var fb = pixelsInRgbf[indx + 2]

                // 5-point cross median filter
                if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                    rgbfLuminances[0].setRGB(pixelsInRgbf, x, y - 1, width)
                    rgbfLuminances[1].setRGB(pixelsInRgbf, x - 1, y, width)
                    rgbfLuminances[2].setRGB(fr, fg, fb)
                    rgbfLuminances[3].setRGB(pixelsInRgbf, x + 1, y, width)
                    rgbfLuminances[4].setRGB(pixelsInRgbf, x, y + 1, width)

                    if (rgbfLuminances[0].lum > rgbfLuminances[1].lum) {
                        val t = rgbfLuminances[0]; rgbfLuminances[0] = rgbfLuminances[1]; rgbfLuminances[1] = t
                    }
                    if (rgbfLuminances[3].lum > rgbfLuminances[4].lum) {
                        val t = rgbfLuminances[3]; rgbfLuminances[3] = rgbfLuminances[4]; rgbfLuminances[4] = t
                    }
                    if (rgbfLuminances[0].lum > rgbfLuminances[3].lum) {
                        val t = rgbfLuminances[0]; rgbfLuminances[0] = rgbfLuminances[3]; rgbfLuminances[3] = t
                        val t2 = rgbfLuminances[1]; rgbfLuminances[1] = rgbfLuminances[4]; rgbfLuminances[4] = t2
                    }
                    if (rgbfLuminances[1].lum > rgbfLuminances[2].lum) {
                        if (rgbfLuminances[2].lum > rgbfLuminances[3].lum) {
                            if (rgbfLuminances[2].lum > rgbfLuminances[4].lum) {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[4]; rgbfLuminances[4] = t
                            }
                        } else {
                            if (rgbfLuminances[1].lum > rgbfLuminances[3].lum) {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[3]; rgbfLuminances[3] = t
                            } else {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[1]; rgbfLuminances[1] = t
                            }
                        }
                    } else {
                        if (rgbfLuminances[1].lum > rgbfLuminances[3].lum) {
                            if (rgbfLuminances[1].lum > rgbfLuminances[4].lum) {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[4]; rgbfLuminances[4] = t
                            } else {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[1]; rgbfLuminances[1] = t
                            }
                        } else {
                            if (rgbfLuminances[2].lum > rgbfLuminances[3].lum) {
                                val t = rgbfLuminances[2]; rgbfLuminances[2] = rgbfLuminances[3]; rgbfLuminances[3] = t
                            }
                        }
                    }

                    val blend = medianFilterStrength
                    fr = (1f - blend) * fr + blend * rgbfLuminances[2].fr
                    fg = (1f - blend) * fg + blend * rgbfLuminances[2].fg
                    fb = (1f - blend) * fb + blend * rgbfLuminances[2].fb
                }

                // (G) 5x5 spatial Wiener color denoise with ISO-adaptive C
                val radius = 2
                val sx = (x - radius).coerceAtLeast(0)
                val ex = (x + radius).coerceAtMost(width - 1)
                val sy = (y - radius).coerceAtLeast(0)
                val ey = (y + radius).coerceAtMost(height - 1)
                var sumFr = 0f; var sumFg = 0f; var sumFb = 0f
                var count = 0
                for (cy in sy..ey) {
                    var ci = (cy * width + sx) * 3
                    for (cx in sx..ex) {
                        var tfr = pixelsInRgbf[ci++]
                        var tfg = pixelsInRgbf[ci++]
                        var tfb = pixelsInRgbf[ci++]

                        val oldVal = fg
                        val thisVal = tfg
                        if (thisVal > 0.5f) {
                            val scale = oldVal / thisVal
                            tfr *= scale; tfg *= scale; tfb *= scale
                        }

                        val dr = fr - tfr; val dg = fg - tfg; val db = fb - tfb
                        val L = dr * dr + dg * dg + db * db
                        val weight = L / (L + wienerC)

                        tfr += weight * dr; tfg += weight * dg; tfb += weight * db
                        sumFr += tfr; sumFg += tfg; sumFb += tfb
                        count++
                    }
                }
                fr = sumFr / count; fg = sumFg / count; fb = sumFb / count

                val r = (fr * 255f).coerceIn(0f, 255f).toInt()
                val g = (fg * 255f).coerceIn(0f, 255f).toInt()
                val b = (fb * 255f).coerceIn(0f, 255f).toInt()
                outPixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
