package com.example.yoloface

import org.junit.Assert.assertEquals
import org.junit.Test
import org.tensorflow.lite.DataType

class TensorBufferCodecTest {
    @Test
    fun `quantizes normalized zero and one to uint8`() {
        val params = ModelInspector.Quantization(scale = 1f / 255f, zeroPoint = 0)

        assertEquals(0, TensorBufferCodec.quantize(0f, DataType.UINT8, params))
        assertEquals(255, TensorBufferCodec.quantize(1f, DataType.UINT8, params))
    }

    @Test
    fun `quantizes normalized zero and one to int8`() {
        val params = ModelInspector.Quantization(scale = 1f / 255f, zeroPoint = -128)

        assertEquals(-128, TensorBufferCodec.quantize(0f, DataType.INT8, params))
        assertEquals(127, TensorBufferCodec.quantize(1f, DataType.INT8, params))
    }

    @Test
    fun `quantization clamps values to tensor range`() {
        val params = ModelInspector.Quantization(scale = 0.01f, zeroPoint = 0)

        assertEquals(-128, TensorBufferCodec.quantize(-10f, DataType.INT8, params))
        assertEquals(255, TensorBufferCodec.quantize(10f, DataType.UINT8, params))
    }

    @Test
    fun `dequantizes with scale and zero point`() {
        val params = ModelInspector.Quantization(scale = 0.1f, zeroPoint = -5)

        assertEquals(1f, TensorBufferCodec.dequantize(5, params), 0.0001f)
    }
}
