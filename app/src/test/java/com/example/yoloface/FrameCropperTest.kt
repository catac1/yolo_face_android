package com.example.yoloface

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FrameCropperTest {
    @Test
    fun `centers 720 square in 1280 by 720 frame`() {
        val crop = FrameCropper.centeredSquare(1280, 720, 720)

        assertEquals(280, crop.left)
        assertEquals(0, crop.top)
        assertEquals(720, crop.width())
        assertEquals(720, crop.height())
    }

    @Test
    fun `uses largest square when frame is smaller than request`() {
        val crop = FrameCropper.centeredSquare(640, 480, 720)

        assertEquals(80, crop.left)
        assertEquals(0, crop.top)
        assertEquals(480, crop.width())
        assertEquals(480, crop.height())
    }
}
