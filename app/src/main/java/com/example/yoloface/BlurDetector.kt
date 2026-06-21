package com.example.yoloface

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

object BlurDetector {
    private const val SAMPLE_MAX_DIMENSION = 160

    fun score(bitmap: Bitmap): Float {
        if (bitmap.width < 3 || bitmap.height < 3) return 0f
        val scale = minOf(1f, SAMPLE_MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height))
        val sampleWidth = max(3, (bitmap.width * scale).roundToInt())
        val sampleHeight = max(3, (bitmap.height * scale).roundToInt())
        val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        }
        try {
            val pixels = IntArray(sampleWidth * sampleHeight)
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
            val grayscale = IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                val red = (pixel shr 16) and 0xff
                val green = (pixel shr 8) and 0xff
                val blue = pixel and 0xff
                (red * 299 + green * 587 + blue * 114) / 1000
            }
            var sum = 0.0
            var sumSquares = 0.0
            var count = 0
            for (y in 1 until sampleHeight - 1) {
                for (x in 1 until sampleWidth - 1) {
                    val index = y * sampleWidth + x
                    val laplacian = 4 * grayscale[index] - grayscale[index - 1] - grayscale[index + 1] -
                        grayscale[index - sampleWidth] - grayscale[index + sampleWidth]
                    sum += laplacian
                    sumSquares += laplacian.toDouble() * laplacian
                    count++
                }
            }
            if (count == 0) return 0f
            val mean = sum / count
            return (sumSquares / count - mean * mean).coerceAtLeast(0.0).toFloat()
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }
}
