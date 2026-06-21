package com.example.yoloface

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object TensorBufferCodec {
    fun bitmapToInput(bitmap: Bitmap, shape: ModelInspector.ModelShape): ByteBuffer {
        val resized = if (bitmap.width == shape.inputWidth && bitmap.height == shape.inputHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, shape.inputWidth, shape.inputHeight, true)
        }
        try {
            val pixels = IntArray(shape.inputWidth * shape.inputHeight)
            resized.getPixels(pixels, 0, shape.inputWidth, 0, 0, shape.inputWidth, shape.inputHeight)
            val bytesPerValue = if (shape.inputType == DataType.FLOAT32) 4 else 1
            return ByteBuffer.allocateDirect(pixels.size * 3 * bytesPerValue)
                .order(ByteOrder.nativeOrder())
                .apply {
                    pixels.forEach { pixel ->
                        putInputValue(((pixel shr 16) and 0xff) / 255f, shape)
                        putInputValue(((pixel shr 8) and 0xff) / 255f, shape)
                        putInputValue((pixel and 0xff) / 255f, shape)
                    }
                    rewind()
                }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    fun decodeOutput(buffer: ByteBuffer, shape: ModelInspector.ModelShape, rows: Int, columns: Int): Array<FloatArray> {
        buffer.rewind()
        return Array(rows) {
            FloatArray(columns) {
                when (shape.outputType) {
                    DataType.FLOAT32 -> buffer.float
                    DataType.INT8 -> dequantize(buffer.get().toInt(), requireNotNull(shape.outputQuantization))
                    DataType.UINT8 -> dequantize(buffer.get().toInt() and 0xff, requireNotNull(shape.outputQuantization))
                    else -> error("Unsupported output type ${shape.outputType}")
                }
            }
        }
    }

    fun allocateOutput(type: DataType, elementCount: Int): ByteBuffer {
        val bytesPerValue = if (type == DataType.FLOAT32) 4 else 1
        return ByteBuffer.allocateDirect(elementCount * bytesPerValue).order(ByteOrder.nativeOrder())
    }

    internal fun quantize(realValue: Float, type: DataType, quantization: ModelInspector.Quantization): Int {
        val raw = (realValue / quantization.scale + quantization.zeroPoint).roundToInt()
        return when (type) {
            DataType.INT8 -> raw.coerceIn(-128, 127)
            DataType.UINT8 -> raw.coerceIn(0, 255)
            else -> error("$type is not quantized")
        }
    }

    internal fun dequantize(value: Int, quantization: ModelInspector.Quantization): Float =
        (value - quantization.zeroPoint) * quantization.scale

    private fun ByteBuffer.putInputValue(realValue: Float, shape: ModelInspector.ModelShape) {
        when (shape.inputType) {
            DataType.FLOAT32 -> putFloat(realValue)
            DataType.INT8, DataType.UINT8 -> put(
                quantize(realValue, shape.inputType, requireNotNull(shape.inputQuantization)).toByte()
            )
            else -> error("Unsupported input type ${shape.inputType}")
        }
    }
}
