package com.classic.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.media.Image
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val XYZ_D50_TO_SRGB = floatArrayOf(
    3.1339f, -1.6170f, -0.4906f,
    -0.9785f, 1.9160f, 0.0334f,
    0.0720f, -0.2290f, 1.4046f
)

fun parseColorMatrix(s: String): FloatArray {
    val out = FloatArray(9)
    val regex = Regex("(-?\\d+)/(\\d+)")
    val matches = regex.findAll(s).toList()
    for (i in 0 until minOf(9, matches.size)) {
        val n = matches[i].groupValues[1].toFloat()
        val d = matches[i].groupValues[2].toFloat()
        out[i] = if (d != 0f) n / d else 0f
    }
    return out
}

fun parseBlackLevel(s: String): FloatArray {
    val nums = Regex("(-?\\d+)").findAll(s).map { it.value.toFloat() }.toList()
    val r = nums.getOrNull(0) ?: 0f
    val g1 = nums.getOrNull(1) ?: r
    val g2 = nums.getOrNull(2) ?: g1
    val b = nums.getOrNull(3) ?: g2
    // +2: 用户要求额外减 2 级黑电平，使暗部更深
    return floatArrayOf(r + 2f, (g1 + g2) * 0.5f + 2f, b + 2f)
}

fun mergeForwardMatrixToSRGB(forward: FloatArray): FloatArray {
    if (forward.size < 9) return floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
    val merged = FloatArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) {
                sum += XYZ_D50_TO_SRGB[row * 3 + k] * forward[k * 3 + col]
            }
            merged[row * 3 + col] = sum
        }
    }
    return floatArrayOf(
        merged[0], merged[3], merged[6],
        merged[1], merged[4], merged[7],
        merged[2], merged[5], merged[8]
    )
}

fun extractRawShorts(image: Image): ShortArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val raw = ShortArray(width * height)
    if (rowStride == width * 2 && pixelStride == 2) {
        buffer.asShortBuffer().get(raw)
        return raw
    }
    val shortBuffer = buffer.asShortBuffer()
    var idx = 0
    for (row in 0 until height) {
        for (col in 0 until width) {
            val pos = row * (rowStride / 2) + col * (pixelStride / 2)
            raw[idx++] = shortBuffer.get(pos)
        }
    }
    return raw
}

fun saveBitmapAsJpeg(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    iso: Int? = null,
    exposureTimeNs: Long? = null,
    aperture: Float? = null,
    focalLength: Float? = null,
    focalLength35mm: Int? = null
): String {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/gufa")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return displayName
    try {
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        // 写入 EXIF（IS_PENDING 仍为 1，相册不可见）
        if (iso != null || exposureTimeNs != null || aperture != null || focalLength != null || focalLength35mm != null) {
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    if (iso != null) exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
                    if (exposureTimeNs != null) {
                        exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME,
                            (exposureTimeNs.toDouble() / 1_000_000_000.0).toString())
                    }
                    if (aperture != null) {
                        exif.setAttribute(ExifInterface.TAG_F_NUMBER, aperture.toString())
                    }
                    if (focalLength != null) {
                        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, focalLength.toString())
                    }
                    if (focalLength35mm != null) {
                        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focalLength35mm.toString())
                    }
                    val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
                    exif.saveAttributes()
                }
            } catch (e: Exception) {
                Log.w("ClassicCamera", "write EXIF err", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } catch (e: Exception) {
        // ignore
    }
    return displayName
}

