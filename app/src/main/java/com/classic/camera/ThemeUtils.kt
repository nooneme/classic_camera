package com.classic.camera

import android.content.Context
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity

fun Context.getAttrColor(@AttrRes attr: Int): Int {
    val ta = obtainStyledAttributes(intArrayOf(attr))
    val color = ta.getColor(0, 0)
    ta.recycle()
    return color
}

fun AppCompatActivity.applySelectedTheme() {
    val themePref = getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
        .getString("theme", "classic") ?: "classic"
    val styleRes = when (themePref) {
        "macaron" -> R.style.Theme_古法相机_马卡龙
        "berry" -> R.style.Theme_古法相机_脏脏莓咖
        "mint" -> R.style.Theme_古法相机_蜜桃薄荷
        "sakura" -> R.style.Theme_古法相机_夜樱
        "blossom" -> R.style.Theme_古法相机_浅樱
        "graphite" -> R.style.Theme_古法相机_石墨
        "wine" -> R.style.Theme_古法相机_醉莓
        else -> R.style.Theme_古法相机
    }
    setTheme(styleRes)
}
