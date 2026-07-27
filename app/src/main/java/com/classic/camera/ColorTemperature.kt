package com.classic.camera

import kotlin.math.ln
import kotlin.math.pow

/**
 * Tanner Helland 算法：将色温(Kelvin)和色调(tint)转换为 RGB 白平衡增益。
 *
 * 返回 FloatArray[3] = [rGain, gGain, bGain]，以 G 通道为基准归一化。
 * kelvin 范围 2000-8000，tint 范围 -100..100（0=中性，+品红，-绿）。
 */
fun kelvinToWbGains(kelvin: Int, tint: Int): FloatArray {
    val k = kelvin / 100.0
    var r: Double
    var g: Double
    var b: Double

    if (k <= 66.0) {
        r = 255.0
        g = 99.4708025861 * ln(k) - 161.1195681661
        b = if (k <= 19.0) 0.0 else 138.5177312231 * ln(k - 10.0) - 305.0447927307
    } else {
        r = 329.698727446 * (k - 60.0).pow(-0.1332047592)
        g = 288.1221695283 * (k - 60.0).pow(-0.0755148492)
        b = 255.0
    }

    r = r.coerceIn(0.0, 255.0)
    g = g.coerceIn(0.0, 255.0)
    b = b.coerceIn(0.0, 255.0)

    if (g < 0.001) g = 1.0
    r /= g
    b /= g
    g = 1.0

    val t = tint / 100.0f
    val rbMult = (1f + t * 0.3f).coerceIn(0.1f, 3f)

    return floatArrayOf(
        (r * rbMult).toFloat(),
        1.0f,
        (b * rbMult).toFloat()
    )
}
