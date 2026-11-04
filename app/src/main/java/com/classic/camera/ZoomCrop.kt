package com.classic.camera

import kotlin.math.roundToInt

/**
 * 归一化裁切矩形（相对「显示方向」的整幅画面）。
 *
 * 裁切在最终 Post Pass 的 UV 上完成：`sourceUV = origin + viewUV * size`。
 * 2× 时 origin=(0.25, 0.25)、size=(0.5, 0.5)，即画面中心 1/2 宽 × 1/2 高。
 */
data class CropRect(
    val originU: Float,
    val originV: Float,
    val sizeU: Float,
    val sizeV: Float
)

object ZoomCrop {
    /** zoom >= 1；当前 UI 只用 1× / 2×。 */
    fun centered(zoom: Float): CropRect {
        val z = zoom.coerceAtLeast(1f)
        val size = 1f / z
        val origin = (1f - size) * 0.5f
        return CropRect(origin, origin, size, size)
    }

    /**
     * 预览/屏幕归一化 UV → 全画幅显示 UV（裁切前的坐标）。
     *
     * 预览显示的是裁切区域，因此屏幕边缘 0/1 对应全画幅的 cropOrigin / cropOrigin+size。
     * 之后再把该 UV 交给 mirror/orientation 的逆变换映射到传感器坐标。
     */
    fun viewToDisplay(u: Float, v: Float, crop: CropRect): Pair<Float, Float> {
        val du = (crop.originU + u * crop.sizeU).coerceIn(0f, 1f)
        val dv = (crop.originV + v * crop.sizeV).coerceIn(0f, 1f)
        return du to dv
    }

    /** 显示方向整幅尺寸 → 裁切后的输出像素尺寸（按原分辨率裁切，不放大）。 */
    fun captureSize(width: Int, height: Int, crop: CropRect): Pair<Int, Int> {
        val w = (width * crop.sizeU).roundToInt().coerceAtLeast(1)
        val h = (height * crop.sizeV).roundToInt().coerceAtLeast(1)
        return w to h
    }
}
