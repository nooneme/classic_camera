package com.classic.camera

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 镜头探测结果的本地存储。
 *
 * 用 SharedPreferences 存一段 JSON。首次启动探测完写入；之后启动直接读。
 * 没存过（卸载重装后）返回 null，触发重新探测。
 *
 * 用 org.json（Android 内置），不引入 Gson 等额外依赖。
 */
object LensStore {

    private const val PREFS_NAME = "lens_store"
    private const val KEY_LENS_LIST = "lens_list_json"
    private const val KEY_LAST_LENS = "last_lens_id"

    /** 返回唯一标识一个镜头的 ID 字符串。 */
    fun lensId(lens: LensInfo): String =
        lens.logicalCameraId + ":" + (lens.physicalCameraId ?: "")

    /** 读取已存储的镜头列表；未存储过返回 null。 */
    fun load(context: Context): List<LensInfo>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LENS_LIST, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                LensInfo(
                    logicalCameraId = o.getString("logicalCameraId"),
                    physicalCameraId = if (o.has("physicalCameraId") && !o.isNull("physicalCameraId"))
                        o.getString("physicalCameraId") else null,
                    label = o.getString("label"),
                    focalLength = o.getDouble("focalLength").toFloat(),
                    aperture = o.getDouble("aperture").toFloat(),
                    lensFacing = if (o.has("lensFacing")) o.getInt("lensFacing") else -1,
                    pixelArraySize = o.getString("pixelArraySize"),
                    activeArraySize = o.getString("activeArraySize"),
                    colorFilterArrangement = o.getInt("colorFilterArrangement"),
                    rawMaxSize = o.getString("rawMaxSize"),
                    whiteLevel = o.getInt("whiteLevel"),
                    referenceIlluminant1 = o.getInt("referenceIlluminant1"),
                    referenceIlluminant2 = o.getInt("referenceIlluminant2"),
                    blackLevelPattern = o.getString("blackLevelPattern"),
                    calibrationTransform1 = o.getString("calibrationTransform1"),
                    calibrationTransform2 = o.getString("calibrationTransform2"),
                    colorTransform1 = o.getString("colorTransform1"),
                    colorTransform2 = o.getString("colorTransform2"),
                    forwardMatrix1 = o.getString("forwardMatrix1"),
                    forwardMatrix2 = o.getString("forwardMatrix2"),
                    sensorOrientation = if (o.has("sensorOrientation")) o.getInt("sensorOrientation") else 90,
                    exposureTimeMinNs = if (o.has("exposureTimeMinNs")) o.getLong("exposureTimeMinNs") else 100_000L,
                    exposureTimeMaxNs = if (o.has("exposureTimeMaxNs")) o.getLong("exposureTimeMaxNs") else 1_000_000_000L,
                    sensitivityMin = if (o.has("sensitivityMin")) o.getInt("sensitivityMin") else 100,
                    sensitivityMax = if (o.has("sensitivityMax")) o.getInt("sensitivityMax") else 12_800,
                    sensorWidthMm = if (o.has("sensorWidthMm")) o.getDouble("sensorWidthMm").toFloat() else 0f,
                    sensorHeightMm = if (o.has("sensorHeightMm")) o.getDouble("sensorHeightMm").toFloat() else 0f
                )
            }
        } catch (e: Exception) {
            // JSON 损坏则当作没存过，触发重新探测
            null
        }
    }

    /** 写入镜头列表。 */
    fun save(context: Context, lenses: List<LensInfo>) {
        val arr = JSONArray()
        for (l in lenses) {
            val o = JSONObject()
            o.put("logicalCameraId", l.logicalCameraId)
            o.put("physicalCameraId", l.physicalCameraId) // null 会被存为 JSONObject.NULL
            o.put("label", l.label)
            o.put("focalLength", l.focalLength.toDouble())
            o.put("aperture", l.aperture.toDouble())
            o.put("lensFacing", l.lensFacing)
            o.put("pixelArraySize", l.pixelArraySize)
            o.put("activeArraySize", l.activeArraySize)
            o.put("colorFilterArrangement", l.colorFilterArrangement)
            o.put("rawMaxSize", l.rawMaxSize)
            o.put("whiteLevel", l.whiteLevel)
            o.put("referenceIlluminant1", l.referenceIlluminant1)
            o.put("referenceIlluminant2", l.referenceIlluminant2)
            o.put("blackLevelPattern", l.blackLevelPattern)
            o.put("calibrationTransform1", l.calibrationTransform1)
            o.put("calibrationTransform2", l.calibrationTransform2)
            o.put("colorTransform1", l.colorTransform1)
            o.put("colorTransform2", l.colorTransform2)
            o.put("forwardMatrix1", l.forwardMatrix1)
            o.put("forwardMatrix2", l.forwardMatrix2)
            o.put("sensorOrientation", l.sensorOrientation)
            o.put("exposureTimeMinNs", l.exposureTimeMinNs)
            o.put("exposureTimeMaxNs", l.exposureTimeMaxNs)
            o.put("sensitivityMin", l.sensitivityMin)
            o.put("sensitivityMax", l.sensitivityMax)
            o.put("sensorWidthMm", l.sensorWidthMm.toDouble())
            o.put("sensorHeightMm", l.sensorHeightMm.toDouble())
            arr.put(o)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LENS_LIST, arr.toString())
            .apply()
    }

    /** 清除存储（重装时自然失效，备用）。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LENS_LIST)
            .remove(KEY_LAST_LENS)
            .apply()
    }

    /** 保存最后选中的镜头 ID。 */
    fun saveLastLensId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_LENS, id)
            .apply()
    }

    /** 读取最后选中的镜头 ID；未存过返回 null。 */
    fun loadLastLensId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_LENS, null)
    }

    /** 滑块状态数据（含半自动 AE 收敛值）。 */
    data class SliderState(
        val shutterProgress: Int,
        val isoProgress: Int,
        val autoIso: Int,
        val autoExposureNs: Long
    )

    /** 保存全局快门/ISO 滑块位置及半自动 AE 收敛值（所有镜头共用，progress=0 表示 Auto）。 */
    fun saveSliderProgress(context: Context, shutterProgress: Int, isoProgress: Int,
                           autoIso: Int, autoExposureNs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("shutter_global", shutterProgress)
            .putInt("iso_global", isoProgress)
            .putInt("auto_iso", autoIso)
            .putLong("auto_exp_ns", autoExposureNs)
            .apply()
    }

    /** 读取全局快门/ISO 滑块位置及半自动 AE 收敛值，未存过返回 null。 */
    fun loadSliderProgress(context: Context): SliderState? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shutter = prefs.getInt("shutter_global", -1)
        val iso = prefs.getInt("iso_global", -1)
        if (shutter < 0 || iso < 0) return null
        val autoIso = prefs.getInt("auto_iso", -1)
        val autoExpNs = prefs.getLong("auto_exp_ns", -1L)
        return SliderState(shutter, iso, autoIso, autoExpNs)
    }
}
