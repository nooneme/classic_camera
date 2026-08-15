package com.classic.camera

import android.content.SharedPreferences

data class ControlPoint(val x: Float, val y: Float)

class ToneCurve {
    val points = mutableListOf<ControlPoint>()

    init {
        points.add(ControlPoint(0f, 0f))
        points.add(ControlPoint(1f, 1f))
    }

    /** 按 x 升序插入控制点，并返回该点在列表中的索引。 */
    fun addPoint(x: Float, y: Float): Int {
        val idx = points.indexOfFirst { it.x >= x }
        if (idx == -1) {
            points.add(ControlPoint(x, y))
            return points.size - 1
        }
        points.add(idx, ControlPoint(x, y))
        return idx
    }

    fun removePoint(index: Int) {
        if (index > 0 && index < points.size - 1) {
            points.removeAt(index)
        }
    }

    fun toNativeArray(): FloatArray {
        val arr = FloatArray(points.size * 2 + 1)
        arr[0] = 4f
        var i = 1
        for (p in points) {
            arr[i++] = p.x
            arr[i++] = p.y
        }
        return arr
    }

    fun copy(): ToneCurve {
        val c = ToneCurve()
        c.points.clear()
        c.points.addAll(points.map { ControlPoint(it.x, it.y) })
        return c
    }

    fun reset() {
        points.clear()
        points.add(ControlPoint(0f, 0f))
        points.add(ControlPoint(1f, 1f))
    }

    companion object {
        private const val PREFS_KEY = "tone_curve_points"

        fun save(prefs: SharedPreferences, curve: ToneCurve) {
            val sb = StringBuilder()
            for (p in curve.points) {
                if (sb.isNotEmpty()) sb.append(",")
                sb.append(p.x).append(",").append(p.y)
            }
            prefs.edit().putString(PREFS_KEY, sb.toString()).apply()
        }

        fun load(prefs: SharedPreferences): ToneCurve {
            val curve = ToneCurve()
            val s = prefs.getString(PREFS_KEY, null) ?: return curve
            if (s.isEmpty()) return curve
            curve.points.clear()
            val parts = s.split(",")
            for (i in parts.indices step 2) {
                if (i + 1 >= parts.size) break
                val x = parts[i].toFloatOrNull() ?: continue
                val y = parts[i + 1].toFloatOrNull() ?: continue
                curve.points.add(ControlPoint(x, y))
            }
            if (curve.points.size < 2) {
                curve.reset()
            }
            return curve
        }
    }
}
