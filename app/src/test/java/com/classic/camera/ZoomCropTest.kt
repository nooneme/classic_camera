package com.classic.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomCropTest {

    @Test
    fun centered_1x_is_full_frame() {
        val crop = ZoomCrop.centered(1f)
        assertEquals(0f, crop.originU, 1e-6f)
        assertEquals(0f, crop.originV, 1e-6f)
        assertEquals(1f, crop.sizeU, 1e-6f)
        assertEquals(1f, crop.sizeV, 1e-6f)
    }

    @Test
    fun centered_2x_takes_middle_half() {
        val crop = ZoomCrop.centered(2f)
        assertEquals(0.25f, crop.originU, 1e-6f)
        assertEquals(0.25f, crop.originV, 1e-6f)
        assertEquals(0.5f, crop.sizeU, 1e-6f)
        assertEquals(0.5f, crop.sizeV, 1e-6f)
    }

    @Test
    fun captureSize_halves_each_dimension() {
        val (w, h) = ZoomCrop.captureSize(4000, 3000, ZoomCrop.centered(2f))
        assertEquals(2000, w)
        assertEquals(1500, h)
    }

    @Test
    fun viewToDisplay_maps_preview_edges_to_crop_region() {
        val crop = ZoomCrop.centered(2f)
        assertEquals(0.25f, ZoomCrop.viewToDisplay(0f, 0f, crop).first, 1e-6f)
        assertEquals(0.5f, ZoomCrop.viewToDisplay(0.5f, 0.5f, crop).first, 1e-6f)
        assertEquals(0.75f, ZoomCrop.viewToDisplay(1f, 1f, crop).second, 1e-6f)
    }

    @Test
    fun viewToDisplay_1x_is_identity() {
        val crop = ZoomCrop.centered(1f)
        assertEquals(0.25f, ZoomCrop.viewToDisplay(0.25f, 0.25f, crop).first, 1e-6f)
        assertEquals(0.75f, ZoomCrop.viewToDisplay(0.75f, 0.75f, crop).second, 1e-6f)
    }
}
