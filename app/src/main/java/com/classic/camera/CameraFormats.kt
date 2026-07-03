package com.classic.camera

import android.util.Size
import java.util.Arrays

/**
 * 跨类共享的相机字段常量与格式化工具。
 *
 * STATIC_KEYS：探测阶段（LensInfo 填充）的静态白名单，仅作参考/校验用。
 * DYNAMIC_KEYS：拍照阶段随 TotalCaptureResult 取的动态字段白名单。
 * formatValue：Camera2 各类参数对象的统一文本化（数组/Size/ColorSpaceTransform 等）。
 */

// 静态字段白名单（探测阶段填充 LensInfo 时按此取值）
val STATIC_KEYS = listOf(
    "android.lens.info.availableApertures",
    "android.lens.info.availableFocalLengths",
    "android.request.availableCapabilities",
    "android.scaler.streamConfigurationMap",
    "android.sensor.blackLevelPattern",
    "android.sensor.calibrationTransform1",
    "android.sensor.calibrationTransform2",
    "android.sensor.colorTransform1",
    "android.sensor.colorTransform2",
    "android.sensor.forwardMatrix1",
    "android.sensor.forwardMatrix2",
    "android.sensor.info.activeArraySize",
    "android.sensor.info.colorFilterArrangement",
    "android.sensor.info.pixelArraySize",
    "android.sensor.info.whiteLevel",
    "android.sensor.referenceIlluminant1",
    "android.sensor.referenceIlluminant2"
)

// 动态字段白名单（拍照后随 CaptureResult 取）
val DYNAMIC_KEYS = listOf(
    "android.colorCorrection.gains",
    "android.colorCorrection.mode",
    "android.colorCorrection.transform",
    "android.logicalMultiCamera.activePhysicalId",
    "android.sensor.dynamicBlackLevel",
    "android.sensor.dynamicWhiteLevel",
    "android.sensor.exposureTime",
    "android.sensor.frameDuration",
    "android.sensor.greenSplit",
    "android.sensor.neutralColorPoint",
    "android.sensor.noiseProfile",
    "android.sensor.sensitivity"
)

/** Camera2 参数对象的统一文本化。 */
fun formatValue(value: Any?): String {
    if (value == null) return "null"
    return when (value) {
        is IntArray -> Arrays.toString(value)
        is FloatArray -> Arrays.toString(value)
        is ByteArray -> Arrays.toString(value)
        is LongArray -> Arrays.toString(value)
        is ShortArray -> Arrays.toString(value)
        is DoubleArray -> Arrays.toString(value)
        is BooleanArray -> Arrays.toString(value)
        is Array<*> -> Arrays.toString(value)

        is Size -> "${value.width}x${value.height}"
        is android.util.Range<*> -> "[${value.lower},${value.upper}]"
        is android.util.Rational -> "${value.numerator}/${value.denominator}"

        else -> {
            // 有自定义 toString（如 ColorSpaceTransform/BlackLevelPattern）直接用
            val s = value.toString()
            if (!s.startsWith("${value.javaClass.name}@")) s
            else {
                // 兜底：反射打印前若干字段
                val sb = StringBuilder("${value.javaClass.simpleName} [")
                val fields = value.javaClass.declaredFields
                val max = Math.min(fields.size, 10)
                for (i in 0 until max) {
                    if (i > 0) sb.append(", ")
                    try {
                        fields[i].isAccessible = true
                        sb.append("${fields[i].name}=${fields[i].get(value)}")
                    } catch (e: Exception) {
                        sb.append("${fields[i].name}=<access error>")
                    }
                }
                if (fields.size > max) sb.append("...")
                sb.append("]").toString()
            }
        }
    }
}
