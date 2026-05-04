package com.example.yoloface

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoloFaceDetectorTest {

    // ─── detect() ────────────────────────────────────────────────────────────

    @Test
    fun `detect returns empty list when interpreter is null`() {
        val detector = YoloFaceDetector(null)
        val mockBitmap = mock<Bitmap>()

        val result = detector.detect(mockBitmap)

        assertTrue(result.isEmpty())
        verifyNoInteractions(mockBitmap)
    }

    @Test
    fun `detect runs inference and filters by confidence threshold`() {
        val mockInterpreter = mock<Interpreter>()
        val mockTensor = mock<Tensor>()

        whenever(mockInterpreter.getOutputTensor(0)).thenReturn(mockTensor)
        whenever(mockTensor.shape()).thenReturn(intArrayOf(1, 3, 5))
        doAnswer { invocation ->
            val out = invocation.getArgument<Array<Array<FloatArray>>>(1)
            out[0][0] = floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, 0.9f) // above threshold
            out[0][1] = floatArrayOf(0.2f, 0.2f, 0.6f, 0.6f, 0.3f) // below threshold
            out[0][2] = floatArrayOf(0.3f, 0.3f, 0.7f, 0.7f, 0.7f) // above threshold
            null
        }.whenever(mockInterpreter).run(any(), any())

        val detector = YoloFaceDetector(mockInterpreter)
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

        val result = detector.detect(bitmap)

        assertEquals(2, result.size)
        verify(mockInterpreter).run(any(), any())
    }

    @Test
    fun `detect returns empty list on inference exception`() {
        val mockInterpreter = mock<Interpreter>()
        val mockTensor = mock<Tensor>()

        whenever(mockInterpreter.getOutputTensor(0)).thenReturn(mockTensor)
        whenever(mockTensor.shape()).thenReturn(intArrayOf(1, 1, 5))
        whenever(mockInterpreter.run(any(), any())).thenThrow(RuntimeException("inference failed"))

        val detector = YoloFaceDetector(mockInterpreter)
        val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)

        val result = detector.detect(bitmap)

        assertTrue(result.isEmpty())
    }

    // ─── parseOutputs() ──────────────────────────────────────────────────────

    @Test
    fun `parseOutputs returns empty list for empty input`() {
        val detector = YoloFaceDetector(null)

        val result = detector.parseOutputs(emptyArray(), 640f, 480f)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOutputs excludes boxes at exactly the confidence threshold`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, 0.5f),
        )

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOutputs excludes boxes below confidence threshold`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, 0.3f),
            floatArrayOf(0.2f, 0.2f, 0.6f, 0.6f, 0.0f),
        )

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseOutputs includes boxes strictly above confidence threshold`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.0f, 0.0f, 0.5f, 0.5f, 0.51f),
        )

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertEquals(1, result.size)
        assertEquals(0.51f, result[0].confidence, 1e-6f)
    }

    @Test
    fun `parseOutputs scales bounding box coordinates by image dimensions`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.1f, 0.2f, 0.4f, 0.6f, 0.9f),
        )

        val result = detector.parseOutputs(outputs, 640f, 480f)

        assertEquals(1, result.size)
        val box = result[0].bounds
        assertEquals(64f, box.left, 1e-4f)   // 0.1 * 640
        assertEquals(96f, box.top, 1e-4f)    // 0.2 * 480
        assertEquals(256f, box.right, 1e-4f) // 0.4 * 640
        assertEquals(288f, box.bottom, 1e-4f)// 0.6 * 480
    }

    @Test
    fun `parseOutputs returns only high-confidence boxes from mixed input`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.0f, 0.0f, 0.3f, 0.3f, 0.9f),
            floatArrayOf(0.1f, 0.1f, 0.4f, 0.4f, 0.3f),
            floatArrayOf(0.2f, 0.2f, 0.5f, 0.5f, 0.7f),
            floatArrayOf(0.3f, 0.3f, 0.6f, 0.6f, 0.5f),
        )

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertEquals(2, result.size)
        assertEquals(0.9f, result[0].confidence, 1e-6f)
        assertEquals(0.7f, result[1].confidence, 1e-6f)
    }

    @Test
    fun `parseOutputs returns all boxes when all confidences exceed threshold`() {
        val detector = YoloFaceDetector(null)
        val outputs = Array(5) { floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f, 0.8f) }

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertEquals(5, result.size)
    }

    @Test
    fun `parseOutputs preserves exact confidence value in FaceBox`() {
        val detector = YoloFaceDetector(null)
        val confidence = 0.876f
        val outputs = arrayOf(
            floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f, confidence),
        )

        val result = detector.parseOutputs(outputs, 100f, 100f)

        assertEquals(confidence, result[0].confidence, 1e-6f)
    }

    @Test
    fun `parseOutputs uses x-coordinates with imageWidth and y-coordinates with imageHeight`() {
        val detector = YoloFaceDetector(null)
        val outputs = arrayOf(
            floatArrayOf(0.5f, 0.25f, 0.75f, 0.5f, 0.9f),
        )

        val result = detector.parseOutputs(outputs, 200f, 400f)

        val box = result[0].bounds
        assertEquals(100f, box.left, 1e-4f)  // 0.5 * 200
        assertEquals(100f, box.top, 1e-4f)   // 0.25 * 400
        assertEquals(150f, box.right, 1e-4f) // 0.75 * 200
        assertEquals(200f, box.bottom, 1e-4f)// 0.5 * 400
    }
}
