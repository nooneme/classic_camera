package com.classic.camera

import android.content.Context
import android.content.SharedPreferences

/** 设置项视图类型，决定使用哪个 item 布局。 */
enum class SettingsViewType {
    SWITCH,      // 开关（保存 DNG / 高光重建 / 自适应对比度）
    MULTIFRAME,  // 多帧降噪：开关 + 帧数滑块
    SLIDER,      // 数值滑块（其余全部）
    THEME,       // 主题选择（横向 RecyclerView）
    CURVE        // 色调曲线编辑器
}

/**
 * 单个设置项的描述。
 *
 * [value] 为该项当前的真实值：
 *  - 开关项用 [valueAsBoolean] 读取；
 *  - 滑块项为当前存储值（如白电平补偿 -20、色温 6500）。
 *
 * 滑块的界面位置与真实值之间通过 [valueToSlider] / [sliderToValue] 双向换算，
 * 显示文本由 [formatValue] 生成。
 */
data class SettingsItem(
    val id: String,
    val type: SettingsViewType,
    val label: String,
    // ---- 滑块配置 ----
    val sliderMin: Float = 0f,
    val sliderMax: Float = 100f,
    val sliderStep: Float = 1f,
    val valueToSlider: (Float) -> Float = { it },
    val sliderToValue: (Float) -> Float = { it },
    val formatValue: (Float) -> String = { it.toInt().toString() },
    val hint: String = "",
    // ---- 开关配置 ----
    val prefsKey: String? = null,
    val switchDefault: Boolean = false,
    // ---- 运行时状态 ----
    var value: Float = 0f,
    var boolValue: Boolean = false,
    var curve: ToneCurve? = null
) {
    val valueAsBoolean: Boolean get() = boolValue

    /** 当前滑块位置对应的真实值。 */
    fun liveValue(sliderPosition: Float): Float = sliderToValue(sliderPosition)

    /** 当前存储值对应的滑块位置。 */
    fun currentSliderPosition(): Float = valueToSlider(value)
}

/** 设置项的稳定默认顺序。 */
val DEFAULT_SETTINGS_ORDER = listOf(
    "theme", "save_dng", "multi_frame", "highlight_reconstruction",
    "white_level_offset", "tone_map_d", "exposure_comp", "hl_comp", "hl_threshold",
    "black_point", "shadow_comp", "contrast", "auto_contrast",
    "curve", "lut_intensity", "color_temp", "color_tint"
)

/** 设置顺序的 SharedPreferences 存取。 */
object SettingsOrderStore {
    private const val PREFS = "camera_settings"
    private const val KEY = "settings_order"

    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return DEFAULT_SETTINGS_ORDER
        val saved = raw.split(",").filter { it.isNotEmpty() }
        // 保序、去重、补漏：新加入的设置项追加到末尾。
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (id in saved) {
            if (id in DEFAULT_SETTINGS_ORDER && seen.add(id)) result.add(id)
        }
        for (id in DEFAULT_SETTINGS_ORDER) {
            if (seen.add(id)) result.add(id)
        }
        return result
    }

    fun save(context: Context, order: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, order.joinToString(",")).apply()
    }
}
