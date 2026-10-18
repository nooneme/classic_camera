package com.classic.camera

/**
 * .cube (3D LUT) 文本解析器。纯 Kotlin，无 Android 依赖，便于单元测试。
 * 直接扫描字节数组，避免逐行 String 与 Float 装箱开销。
 */
object CubeParser {

    /** 解析 .cube 文本。成功返回 size³×3 的 FloatArray（RGB 顺序），失败返回 null。 */
    fun parse(b: ByteArray): FloatArray? {
        val n = b.size
        var i = 0
        var size = 0
        var data: FloatArray? = null
        var count = 0
        val tmp = FloatArray(1)

        while (i < n) {
            val c = b[i].toInt()
            if (c <= 32) { i++; continue }                 // 空白 / 换行
            if (c == '#'.code) {                            // 注释：跳到行尾
                while (i < n && b[i].toInt() != '\n'.code) i++
                continue
            }
            var j = i
            while (j < n && b[j].toInt() != '\n'.code) j++  // 行 [i, j)
            val first = b[i].toInt()
            when {
                first == 'L'.code -> {                      // LUT_3D_SIZE <n>
                    var k = i
                    while (k < j && b[k].toInt() > 32) k++  // 先跳过关键字本身（含数字 3D）
                    while (k < j && b[k].toInt() <= 32) k++ // 跳过空白
                    var v = 0
                    while (k < j && b[k].toInt() in 48..57) { v = v * 10 + (b[k].toInt() - 48); k++ }
                    size = v
                    if (size > 0) {
                        val total = size.toLong() * size * size * 3
                        if (total <= 0 || total > 64_000_000) return null
                        data = FloatArray(total.toInt())
                    }
                }
                first == 'T'.code || first == 'D'.code -> { // TITLE / DOMAIN_*：跳过整行
                }
                first == '-'.code || first == '+'.code || first == '.'.code || first in 48..57 -> {
                    val arr = data ?: return null
                    var k = i
                    for (t in 0 until 3) {
                        val next = parseAsciiFloat(b, k, j, tmp)
                        if (next <= k) return null
                        if (count < arr.size) arr[count++] = tmp[0]
                        k = next
                    }
                }
                else -> return null                         // 未知行
            }
            i = j + 1
        }
        return if (size > 0 && data != null && count == data.size) data else null
    }

    /** 从字节数组 [start,end) 解析一个 ASCII 浮点数；结果写入 out[0]，返回下一位置。失败返回 start。 */
    private fun parseAsciiFloat(b: ByteArray, start: Int, end: Int, out: FloatArray): Int {
        var i = start
        while (i < end && b[i].toInt() <= 32) i++
        var neg = false
        if (i < end) {
            val c = b[i].toInt()
            if (c == '-'.code) { neg = true; i++ } else if (c == '+'.code) { i++ }
        }
        var value = 0.0
        var anyDigit = false
        while (i < end) {
            val c = b[i].toInt()
            if (c in 48..57) { value = value * 10 + (c - 48); i++; anyDigit = true } else break
        }
        if (i < end && b[i].toInt() == '.'.code) {
            i++
            var scale = 0.1
            while (i < end) {
                val c = b[i].toInt()
                if (c in 48..57) { value += (c - 48) * scale; scale *= 0.1; i++; anyDigit = true } else break
            }
        }
        if (!anyDigit) return start
        if (i < end) {
            val c = b[i].toInt()
            if (c == 'e'.code || c == 'E'.code) {
                i++
                var eneg = false
                if (i < end) {
                    val c2 = b[i].toInt()
                    if (c2 == '-'.code) { eneg = true; i++ } else if (c2 == '+'.code) { i++ }
                }
                var e = 0
                while (i < end && b[i].toInt() in 48..57) { e = e * 10 + (b[i].toInt() - 48); i++ }
                value *= Math.pow(10.0, (if (eneg) -e else e).toDouble())
            }
        }
        out[0] = (if (neg) -value else value).toFloat()
        return i
    }
}
