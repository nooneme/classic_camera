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
        "vintage" -> R.style.Theme_古法相机_纸红复古
        "orange" -> R.style.Theme_古法相机_橙光奶油
        "forest" -> R.style.Theme_古法相机_森绿质朴
        "berry" -> R.style.Theme_古法相机_脏脏莓咖
        else -> R.style.Theme_古法相机
    }
    setTheme(styleRes)
}
