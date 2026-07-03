package com.classic.camera

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.sqrt

class HDRProcessorException(val reason: Int) : Exception() {
    companion object {
        const val UNEQUAL_SIZES = 1
    }
}

class HDRProcessor {

    companion object {
        private val TAG = "HDRProcessor"
        private val ISO_FOR_DARK = 400
        private val N_IMAGES_NR_DARK = 8
        private val N_IMAGES_NR_DARK_LOW_LIGHT = 15

        fun sceneIsLowLight(iso: Int, exposureTime: Long): Boolean {
            return (iso >= ISO_FOR_DARK && iso * exposureTime >= 69L * 1000000000L) ||
                    exposureTime >= (1000000000L / 5 - 10000L)
        }

        fun decideNFrames(iso: Int, exposureTime: Long, nrLowLight: Boolean): Int {
            return when {
                sceneIsLowLight(iso, exposureTime) ->
                    if (nrLowLight) N_IMAGES_NR_DARK_LOW_LIGHT else N_IMAGES_NR_DARK
                exposureTime <= 1000000000L / 60 -> 3
                else -> 4
            }
        }
    }

    class AvgData(
        var pixelsRgbfOut: FloatArray?,
        var bitmapAvgAlign: Bitmap?,
        var bitmapOrig: Bitmap?,
        var pixelsOrig: IntArray? = null
    ) {
        fun destroy() {
            pixelsRgbfOut = null
            bitmapAvgAlign?.recycle()
            bitmapAvgAlign = null
            bitmapOrig?.recycle()
            bitmapOrig = null
            pixelsOrig = null
        }
    }

    private var cachedAvgSampleSize = 1

    fun getAvgSampleSize(iso: Int, exposureTime: Long): Int {
        cachedAvgSampleSize = if (sceneIsLowLight(iso, exposureTime)) 2 else 1
        return cachedAvgSampleSize
    }

    fun getAvgSampleSize(): Int = cachedAvgSampleSize

    fun processAvg(
        bitmapAvg: Bitmap, bitmapNew: Bitmap, avgFactor: Float,
        iso: Int, exposureTime: Long, zoomFactor: Float
    ): AvgData {
        if (bitmapAvg.width != bitmapNew.width || bitmapAvg.height != bitmapNew.height) {
            throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
        }
        val width = bitmapAvg.width
        val height = bitmapAvg.height
        val timeS = System.currentTimeMillis()
        return processAvgCore(null, bitmapAvg, bitmapNew, width, height,
            avgFactor, iso, exposureTime, zoomFactor, timeS)
    }

    fun updateAvg(
        avgData: AvgData, width: Int, height: Int, bitmapNew: Bitmap,
        avgFactor: Float, iso: Int, exposureTime: Long, zoomFactor: Float
    ) {
        if (width != bitmapNew.width || height != bitmapNew.height) {
            throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
        }
        val timeS = System.currentTimeMillis()
        processAvgCore(avgData, null, bitmapNew, width, height,
            avgFactor, iso, exposureTime, zoomFactor, timeS)
    }

    private fun processAvgCore(
        avgData: AvgData?, bitmapAvg: Bitmap?, bitmapNew: Bitmap,
        width: Int, height: Int, avgFactor: Float,
        iso: Int, exposureTime: Long, zoomFactor: Float, timeS: Long
    ): AvgData {
        var pixelsRgbfOut: FloatArray? = null
        var bitmapAvgAlign: Bitmap? = null
        var bitmapOrig: Bitmap? = null
        if (avgData != null) {
            pixelsRgbfOut = avgData.pixelsRgbfOut
            bitmapAvgAlign = avgData.bitmapAvgAlign
            bitmapOrig = avgData.bitmapOrig
        }

        val offsetsX = IntArray(2)
        val offsetsY = IntArray(2)
        val newBitmapIsFirst = (bitmapAvg != null && pixelsRgbfOut == null)

        val scaleAlignSize = if (zoomFactor > 3.9f) 1
        else (2 / getAvgSampleSize(iso, exposureTime)).coerceAtLeast(1)

        if (bitmapAvgAlign == null) {
            val cropW = width / 2
            val cropH = height / 2
            val cropX = (width - cropW) / 2
            val cropY = (height - cropH) / 2
            val scaleMatrix = android.graphics.Matrix().apply {
                postScale(1f / scaleAlignSize, 1f / scaleAlignSize)
            }
            bitmapAvgAlign = Bitmap.createBitmap(bitmapAvg!!, cropX, cropY, cropW, cropH, scaleMatrix, false)
        }

        val cropW = width / 2
        val cropH = height / 2
        val cropX = (width - cropW) / 2
        val cropY = (height - cropH) / 2
        val scaleMatrix = android.graphics.Matrix().apply {
            postScale(1f / scaleAlignSize, 1f / scaleAlignSize)
        }
        val bitmapNewAlign = Bitmap.createBitmap(bitmapNew, cropX, cropY, cropW, cropH, scaleMatrix, false)

        val alignmentWidth = bitmapNewAlign.width
        val alignmentHeight = bitmapNewAlign.height
        val alignBitmaps = mutableListOf(bitmapAvgAlign!!, bitmapNewAlign)
        val wider = sceneIsLowLight(iso, exposureTime)
        autoAlignment(offsetsX, offsetsY, alignmentWidth, alignmentHeight,
            alignBitmaps, 0, true, 1, false,
            if (wider) 2 else 1,
            alignmentWidth, alignmentHeight)

        offsetsX[1] *= scaleAlignSize
        offsetsY[1] *= scaleAlignSize

        // 亚像素对齐精修 (E)
        val fracX = refineSubpixelOffset(bitmapAvgAlign, bitmapNewAlign, offsetsX[1], offsetsY[1], alignmentWidth, alignmentHeight)
        val fracY = refineSubpixelOffset(bitmapNewAlign, bitmapAvgAlign, offsetsY[1], offsetsX[1], alignmentWidth, alignmentHeight)

        bitmapNewAlign.recycle()

        val floatOffX = offsetsX[1].toFloat() + fracX * scaleAlignSize
        val floatOffY = offsetsY[1].toFloat() + fracY * scaleAlignSize

        var limitedIso = Math.min(iso, 400).coerceAtLeast(100).toFloat()
        var wienerC = 10.0f * limitedIso
        val taperedWienerScale = 1.0f - Math.pow(0.5, avgFactor.toDouble()).toFloat()
        wienerC /= taperedWienerScale
        val wienerCCutoff = wienerC

        if (bitmapOrig == null) {
            bitmapOrig = bitmapAvg
        }

        // 缓存第一帧的像素数组，跨帧复用 (C)
        val cachedPixelsOrig = if (avgData?.pixelsOrig != null) {
            avgData.pixelsOrig!!
        } else {
            val orig = IntArray(width * height)
            bitmapOrig!!.getPixels(orig, 0, width, 0, 0, width, height)
            avgData?.let { it.pixelsOrig = orig }
            orig
        }

        if (pixelsRgbfOut == null) {
            pixelsRgbfOut = FloatArray(3 * width * height)
            if (newBitmapIsFirst) {
                var i = 0
                for (p in cachedPixelsOrig) {
                    pixelsRgbfOut[i++] = ((p shr 16) and 0xFF) / 255f
                    pixelsRgbfOut[i++] = ((p shr 8) and 0xFF) / 255f
                    pixelsRgbfOut[i++] = (p and 0xFF) / 255f
                }
            }
        }

        val function = AvgApplyFunction(pixelsRgbfOut, bitmapNew, bitmapOrig!!,
            width, height, floatOffX, floatOffY, avgFactor, wienerC, wienerCCutoff, cachedPixelsOrig)
        function.apply()

        bitmapNew.recycle()
        return AvgData(pixelsRgbfOut, bitmapAvgAlign, bitmapOrig, cachedPixelsOrig)
    }

    // ── MTB alignment ──

    private fun computeLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.3f * r + 0.6f * g + 0.1f * b).toInt().coerceIn(0, 255)
    }

    private data class LuminanceInfo(val medianValue: Int, val noisy: Boolean)

    private fun computeMedianLuminance(bitmap: Bitmap, left: Int, top: Int,
                                       width: Int, height: Int): LuminanceInfo {
        val nPixels = width * height
        val nSample = 100
        val step = Math.max(1, Math.sqrt((nPixels / nSample).toDouble()).toInt())
        val histo = IntArray(256)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)

        var total = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val lum = computeLuminance(pixels[y * width + x])
                histo[lum]++
                total++
                x += step
            }
            y += step
        }
        if (total == 0) return LuminanceInfo(128, false)

        val halfTotal = total / 2
        var cumulative = 0
        var median = 0
        for (i in 0 until 256) {
            cumulative += histo[i]
            if (cumulative >= halfTotal) { median = i; break }
        }

        val loCount = histo.take(8).sum()
        val hiCount = histo.takeLast(8).sum()
        val noisy = (loCount + hiCount) > total / 2
        return LuminanceInfo(median, noisy)
    }

    /** Create MTB arrays as integer arrays: 0=uncertain, 1=dark, 255=bright */
    private fun createMtb(pixels: IntArray, width: Int, height: Int,
                          medianValue: Int): IntArray {
        val mtb = IntArray(width * height)
        for (i in pixels.indices) {
            val lum = computeLuminance(pixels[i])
            val diff = Math.abs(lum - medianValue)
            if (diff <= 4) {
                mtb[i] = 0  // uncertain
            } else if (lum > medianValue) {
                mtb[i] = 255 // bright
            } else {
                mtb[i] = 1   // dark (certain, but different from bright and uncertain)
            }
        }
        return mtb
    }

    private fun autoAlignment(
        offsetsX: IntArray, offsetsY: IntArray,
        width: Int, height: Int,
        bitmaps: MutableList<Bitmap>, baseBitmap: Int,
        useMtb: Boolean, minStepSize: Int,
        cropToCentre: Boolean, maxAlignScale: Int,
        fullWidth: Int, fullHeight: Int
    ) {
        val nImages = bitmaps.size
        for (i in 0 until nImages) { offsetsX[i] = 0; offsetsY[i] = 0 }

        val mtbWidth = if (cropToCentre) width / 2 else width
        val mtbHeight = if (cropToCentre) height / 2 else height
        val mtbX = if (cropToCentre) mtbWidth / 2 else 0
        val mtbY = if (cropToCentre) mtbHeight / 2 else 0

        // compute median luminance
        val luminanceInfos = arrayOfNulls<LuminanceInfo>(nImages)
        if (useMtb) {
            for (i in 0 until nImages) {
                luminanceInfos[i] = computeMedianLuminance(bitmaps[i], mtbX, mtbY, mtbWidth, mtbHeight)
            }
        }

        // create MTB arrays (not bitmaps — avoid ALPHA_8 getPixels issues)
        val mtbArrays = arrayOfNulls<IntArray>(nImages)
        for (i in 0 until nImages) {
            if (!useMtb) continue
            val info = luminanceInfos[i] ?: continue
            var mv = info.medianValue
            mv = Math.max(mv, 5); mv = Math.min(mv, 250)

            val pixels = IntArray(mtbWidth * mtbHeight)
            bitmaps[i].getPixels(pixels, 0, mtbWidth, mtbX, mtbY, mtbWidth, mtbHeight)
            mtbArrays[i] = createMtb(pixels, mtbWidth, mtbHeight, mv)
        }

        val baseMtb = mtbArrays[baseBitmap] ?: return

        // hierarchical search
        val maxDim = Math.max(mtbWidth, mtbHeight)
        var stepSize = 1
        while (stepSize * 150 < maxDim * maxAlignScale) stepSize *= 2
        if (stepSize < minStepSize) stepSize = minStepSize

        val localOffsetsX = IntArray(nImages)
        val localOffsetsY = IntArray(nImages)

        while (stepSize >= minStepSize) {
            for (i in 0 until nImages) {
                if (i == baseBitmap) continue
                val otherMtb = mtbArrays[i] ?: continue

                var bestDx = localOffsetsX[i]
                var bestDy = localOffsetsY[i]
                var bestError = -1

                for (dy in -stepSize..stepSize step stepSize) {
                    for (dx in -stepSize..stepSize step stepSize) {
                        val offDx = localOffsetsX[i] + dx
                        val offDy = localOffsetsY[i] + dy
                        var error = 0
                        var count = 0
                        var y = 0
                        while (y < mtbHeight) {
                            var x = 0
                            while (x < mtbWidth) {
                                val bv = baseMtb[y * mtbWidth + x]
                                if (bv == 0) { x += stepSize; continue }
                                val ox = x + offDx
                                val oy = y + offDy
                                if (ox in 0 until mtbWidth && oy in 0 until mtbHeight) {
                                    val ov = otherMtb[oy * mtbWidth + ox]
                                    if (ov != 0 && bv != ov) error++
                                    count++
                                }
                                x += stepSize
                            }
                            y += stepSize
                        }
                        if (bestError < 0 || error < bestError ||
                            (error == bestError &&
                                Math.abs(dx) + Math.abs(dy) <
                                Math.abs(bestDx - localOffsetsX[i]) + Math.abs(bestDy - localOffsetsY[i]))
                        ) {
                            bestError = error; bestDx = offDx; bestDy = offDy
                        }
                    }
                }
                localOffsetsX[i] = bestDx; localOffsetsY[i] = bestDy
            }
            stepSize /= 2
        }

        for (i in 0 until nImages) {
            offsetsX[i] = localOffsetsX[i]
            offsetsY[i] = localOffsetsY[i]
        }
    }

    // ── 亚像素精修 (E) ──

    /** 在整数位移附近用抛物线拟合亚像素偏移。返回 [-0.5, 0.5] 的修正值。 */
    private fun refineSubpixelOffset(fixed: Bitmap, moving: Bitmap, intDx: Int, intDy: Int, w: Int, h: Int): Float {
        val fixedPixels = IntArray(w * h)
        val movingPixels = IntArray(w * h)
        fixed.getPixels(fixedPixels, 0, w, 0, 0, w, h)
        moving.getPixels(movingPixels, 0, w, 0, 0, w, h)

        fun sad(dx: Int): Float {
            var s = 0f; var cnt = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val mx = x + dx
                    val my = y + intDy
                    if (mx in 0 until w && my in 0 until h) {
                        val fp = fixedPixels[y * w + x]
                        val mp = movingPixels[my * w + mx]
                        val fLum = ((fp shr 16) and 0xFF) * 0.3f + ((fp shr 8) and 0xFF) * 0.6f + (fp and 0xFF) * 0.1f
                        val mLum = ((mp shr 16) and 0xFF) * 0.3f + ((mp shr 8) and 0xFF) * 0.6f + (mp and 0xFF) * 0.1f
                        s += Math.abs(fLum - mLum); cnt++
                    }
                    x += 2
                }
                y += 2
            }
            return if (cnt > 0) s / cnt else 0f
        }

        val sadM1 = sad(intDx - 1)
        val sad0 = sad(intDx)
        val sadP1 = sad(intDx + 1)

        if (sadM1 < sad0 && sadM1 < sadP1) return -0.5f
        if (sadP1 < sad0) return 0.5f
        val denom = sadM1 - 2f * sad0 + sadP1
        if (Math.abs(denom) < 1e-6f) return 0f
        val frac = 0.5f * (sadM1 - sadP1) / denom
        return frac.coerceIn(-0.5f, 0.5f)
    }

    // ── Brighten (median filter + color denoise only) ──

    fun avgBrighten(avgData: AvgData, width: Int, height: Int,
                    medianFilterStrength: Float, wienerC: Float): Bitmap {
        val pixels = avgData.pixelsRgbfOut
        if (pixels == null) return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return avgBrightenRGBf(pixels, width, height, medianFilterStrength, wienerC)
    }

    private fun avgBrightenRGBf(
        pixelsInRgbf: FloatArray, width: Int, height: Int,
        medianFilterStrength: Float, wienerC: Float
    ): Bitmap {
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val function = AvgBrightenApplyFunction(pixelsInRgbf, width, height, medianFilterStrength, wienerC)
        function.apply(outputBitmap)
        return outputBitmap
    }
}
