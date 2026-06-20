package com.example.yoloface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoloOutputParserTest {
    private val labels = listOf("cat", "dog")

    @Test
    fun `parses attribute-first YOLO11 output and selects best class`() {
        val raw = arrayOf(
            floatArrayOf(0.5f), floatArrayOf(0.5f), floatArrayOf(0.4f), floatArrayOf(0.2f),
            floatArrayOf(0.2f), floatArrayOf(0.9f),
        )

        val result = parse(raw, attributeFirst = true)

        assertEquals(1, result.size)
        assertEquals("dog", result[0].label)
        assertEquals(0.9f, result[0].confidence, 0.0001f)
        assertEquals(192f, result[0].bounds.left, 0.01f)
        assertEquals(256f, result[0].bounds.top, 0.01f)
    }

    @Test
    fun `parses box-first output and filters low confidence`() {
        val raw = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.2f, 0.2f, 0.1f, 0.2f),
            floatArrayOf(0.4f, 0.4f, 0.2f, 0.2f, 0.8f, 0.1f),
        )

        val result = parse(raw, attributeFirst = false)

        assertEquals(1, result.size)
        assertEquals("cat", result[0].label)
    }

    @Test
    fun `converts model-pixel xywh coordinates to image bounds`() {
        val raw = arrayOf(floatArrayOf(320f, 160f, 128f, 64f, 0.8f, 0.1f))

        val result = parse(raw, attributeFirst = false, imageWidth = 1280f, imageHeight = 640f)

        assertEquals(512f, result[0].bounds.left, 0.01f)
        assertEquals(128f, result[0].bounds.top, 0.01f)
        assertEquals(768f, result[0].bounds.right, 0.01f)
        assertEquals(192f, result[0].bounds.bottom, 0.01f)
    }

    @Test
    fun `suppresses overlapping boxes of same class`() {
        val raw = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.9f, 0.1f),
            floatArrayOf(0.51f, 0.51f, 0.4f, 0.4f, 0.8f, 0.1f),
        )

        val result = parse(raw, attributeFirst = false)

        assertEquals(1, result.size)
        assertEquals(0.9f, result[0].confidence, 0.0001f)
    }

    @Test
    fun `does not suppress overlapping boxes of different classes`() {
        val raw = arrayOf(
            floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.9f, 0.1f),
            floatArrayOf(0.5f, 0.5f, 0.4f, 0.4f, 0.1f, 0.8f),
        )

        assertEquals(2, parse(raw, attributeFirst = false).size)
    }

    @Test
    fun `returns empty result for empty output`() {
        assertTrue(parse(emptyArray(), attributeFirst = false).isEmpty())
    }

    private fun parse(
        raw: Array<FloatArray>,
        attributeFirst: Boolean,
        imageWidth: Float = 640f,
        imageHeight: Float = 640f,
    ) = YoloOutputParser.parse(
        raw = raw,
        attributeFirst = attributeFirst,
        labels = labels,
        confidenceThreshold = 0.25f,
        iouThreshold = 0.45f,
        inputWidth = 640,
        inputHeight = 640,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
    )
}
