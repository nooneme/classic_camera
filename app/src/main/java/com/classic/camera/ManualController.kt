package com.classic.camera

import android.hardware.camera2.CaptureRequest
import kotlin.math.pow

/**
 * 手动曝光控制状态与参数。
 *
 * 无极调节（stepless）—— SeekBar progress 以对数曲线连续映射到
 * 曝光时间 / ISO 的实际值，没有离散档位。
 *
 * 支持半自动（快门优先 / ISO 优先）——当某一参数为 Auto 时，
 * CameraController 的软件 AE 反馈环会根据画面亮度自动调节它。
 */
class ManualController {

    /** 是否启用手动曝光（任一滑块离开 Auto 位即为 true）。 */
    var isManualExposure: Boolean = false
        private set

    /** 快门是否为手动。 */
    var isShutterManual: Boolean = false
        private set

    /** ISO 是否为手动。 */
    var isIsoManual: Boolean = false
        private set

    /** 手动曝光时间（纳秒）。 */
    var exposureTimeNs: Long = DEFAULT_EXPOSURE_NS

    /** 手动 ISO 感光度。 */
    var iso: Int = 100

    // ---- 设备的硬件边界（由 initFromLens 填充） ----
    var exposureMinNs: Long = 100_000L
        private set
    var exposureMaxNs: Long = 1_000_000_000L
        private set
    var sensitivityMin: Int = 100
        private set
    var sensitivityMax: Int = 12_800
        private set

    // ---- 初始化 ----

    /**
     * 根据镜头的曝光/ISO 范围初始化。
     * 每次切换镜头时调用。
     */
    fun initFromLens(lens: LensInfo) {
        exposureMinNs = lens.exposureTimeMinNs
        exposureMaxNs = lens.exposureTimeMaxNs
        sensitivityMin = lens.sensitivityMin
        sensitivityMax = lens.sensitivityMax

        // 重置为自动
        isManualExposure = false
        isShutterManual = false
        isIsoManual = false
        exposureTimeNs = DEFAULT_EXPOSURE_NS
        // ISO 用合理的中间值起步（min×4），避免 AE_MODE_OFF 时画面全黑
        iso = (sensitivityMin * 4).coerceIn(sensitivityMin, sensitivityMax / 2)
    }

    // ---- 无极映射（progress → 实际值 / 标签） ----

    /**
     * 将快门滑块进度映射到曝光时间（纳秒）。
     * @param progress SeekBar 进度（0 = Auto, 1~RESOLUTION = 手动范围）
     */
    fun shutterProgressToValue(progress: Int): Long {
        if (progress <= 0) return exposureTimeNs
        val t = (progress - 1).toDouble() / (SHUTTER_RESOLUTION - 1)
        return (exposureMinNs.toDouble() * (exposureMaxNs.toDouble() / exposureMinNs).pow(t) + 0.5).toLong()
    }

    /** 将 ISO 滑块进度映射到感光度。 */
    fun isoProgressToValue(progress: Int): Int {
        if (progress <= 0) return iso
        val t = (progress - 1).toDouble() / (ISO_RESOLUTION - 1)
        return (sensitivityMin.toDouble() * (sensitivityMax.toDouble() / sensitivityMin).pow(t) + 0.5).toInt()
    }

    /** 生成快门速度显示标签。progress=0 → "Auto"。 */
    fun shutterProgressToLabel(progress: Int): String =
        if (progress <= 0) "Auto"
        else formatExposureTime(shutterProgressToValue(progress))

    /** 生成 ISO 显示标签。progress=0 → "Auto"。 */
    fun isoProgressToLabel(progress: Int): String =
        if (progress <= 0) "Auto"
        else isoProgressToValue(progress).toString()

    // ---- CaptureRequest 绑定 ----

    /** 将当前手动/自动参数设置到 CaptureRequest 构建器。 */
    fun applyTo(builder: CaptureRequest.Builder) {
        if (isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    /**
     * 由 SeekBar 回调调用：更新手动/自动状态和参数值。
     * @param shutterProgress SeekBar 进度（0 = Auto, 1+ = 手动区）
     * @param isoProgress     ISO 进度
     */
    fun updateFromSliders(shutterProgress: Int, isoProgress: Int) {
        isShutterManual = shutterProgress > 0
        isIsoManual = isoProgress > 0
        isManualExposure = isShutterManual || isIsoManual

        if (isShutterManual) exposureTimeNs = shutterProgressToValue(shutterProgress)
        if (isIsoManual) iso = isoProgressToValue(isoProgress)
    }

    // ---- 辅助 ----

    private fun formatExposureTime(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) "%.2f\"".format(seconds)
        else "1/%.0f".format(1.0 / seconds)
    }

    companion object {
        const val DEFAULT_EXPOSURE_NS: Long = 1_000_000L

        /** 滑块的"手动区"分辨率：progress 1 ~ RESOLUTION 映射整个硬件范围。 */
        const val SHUTTER_RESOLUTION = 10000
        const val ISO_RESOLUTION = 10000

        fun formatExposureNs(ns: Long): String {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) "%.2f\"".format(seconds)
            else "1/%.0f".format(1.0 / seconds)
        }
    }
}
