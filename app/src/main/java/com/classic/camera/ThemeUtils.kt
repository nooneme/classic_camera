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

/** 全部可选主题（与设置弹窗展示一致）。 */
val ALL_THEMES = listOf(
    ThemeEntry("classic", "棉花糖", intArrayOf(
        0xFFF8F4EC.toInt(), 0xFF90CAF9.toInt(), 0xFFE57373.toInt(), 0xFF2C2C2C.toInt()
    )),
    ThemeEntry("macaron", "马卡龙", intArrayOf(
        0xFFA3CEC5.toInt(), 0xFFE2A3B4.toInt(), 0xFFA2BEE3.toInt(), 0xFF3D3A3A.toInt()
    )),
    ThemeEntry("berry", "脏脏莓咖", intArrayOf(
        0xFF6D6975.toInt(), 0xFFE59A9B.toInt(), 0xFFB6828D.toInt(), 0xFFF0E8EC.toInt()
    )),
    ThemeEntry("mint", "蜜桃薄荷", intArrayOf(
        0xFF96C7B3.toInt(), 0xFFF9B95C.toInt(), 0xFF6398A9.toInt(), 0xFFD7897F.toInt()
    )),
    ThemeEntry("sakura", "夜樱", intArrayOf(
        0xFF2B1F21.toInt(), 0xFFD09DB8.toInt(), 0xFF553758.toInt(), 0xFFFCEFFA.toInt()
    )),
    ThemeEntry("blossom", "浅樱", intArrayOf(
        0xFFFCEFFA.toInt(), 0xFFD09DB8.toInt(), 0xFFE8D3E0.toInt(), 0xFF423048.toInt()
    )),
    ThemeEntry("graphite", "石墨", intArrayOf(
        0xFFF5F4F2.toInt(), 0xFF7F1D1A.toInt(), 0xFFD8D5D1.toInt(), 0xFF141616.toInt()
    )),
    ThemeEntry("wine", "醉莓", intArrayOf(
        0xFF230E0F.toInt(), 0xFFBC788D.toInt(), 0xFF541625.toInt(), 0xFF9D4060.toInt()
    )),
)

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
