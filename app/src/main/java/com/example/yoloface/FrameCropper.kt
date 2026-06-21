package com.example.yoloface

import android.graphics.Rect
import kotlin.math.min

object FrameCropper {
    fun centeredSquare(frameWidth: Int, frameHeight: Int, requestedSize: Int): Rect {
        require(frameWidth > 0 && frameHeight > 0 && requestedSize > 0)
        val size = min(requestedSize, min(frameWidth, frameHeight))
        val left = (frameWidth - size) / 2
        val top = (frameHeight - size) / 2
        return Rect(left, top, left + size, top + size)
    }
}
