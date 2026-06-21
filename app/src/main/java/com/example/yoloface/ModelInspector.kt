package com.example.yoloface

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File

object ModelInspector {
    data class Quantization(val scale: Float, val zeroPoint: Int)

    data class ModelShape(
        val inputWidth: Int,
        val inputHeight: Int,
        val attributeFirst: Boolean,
        val inputType: DataType,
        val outputType: DataType,
        val inputQuantization: Quantization?,
        val outputQuantization: Quantization?,
    )

    fun validate(file: File, labelCount: Int): ModelShape {
        require(file.isFile && file.length() > 0) { "The selected model file is empty" }
        return Interpreter(file).use { interpreter -> inspect(interpreter, labelCount) }
    }

    fun inspect(interpreter: Interpreter, labelCount: Int): ModelShape {
        require(interpreter.inputTensorCount == 1) { "Only single-input YOLO11 models are supported" }
        require(interpreter.outputTensorCount == 1) { "Only single-output YOLO11 detection models are supported" }
        val input = interpreter.getInputTensor(0)
        val inputShape = input.shape()
        require(input.dataType() in SUPPORTED_TYPES) {
            "Input type ${input.dataType()} is unsupported. Use FLOAT32, INT8, or UINT8"
        }
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "Expected input tensor [1, height, width, 3], found ${inputShape.contentToString()}"
        }
        val output = interpreter.getOutputTensor(0)
        val outputShape = output.shape()
        require(output.dataType() in SUPPORTED_TYPES) {
            "Output type ${output.dataType()} is unsupported. Use FLOAT32, INT8, or UINT8"
        }
        require(outputShape.size == 3 && outputShape[0] == 1) {
            "Expected a rank-3 YOLO output, found ${outputShape.contentToString()}"
        }
        val attributes = labelCount + 4
        val attributeFirst = when {
            outputShape[1] == attributes -> true
            outputShape[2] == attributes -> false
            else -> error("Labels contain $labelCount classes, but output shape ${outputShape.contentToString()} does not contain $attributes attributes")
        }
        return ModelShape(
            inputWidth = inputShape[2],
            inputHeight = inputShape[1],
            attributeFirst = attributeFirst,
            inputType = input.dataType(),
            outputType = output.dataType(),
            inputQuantization = quantizationFor(input.dataType(), input.quantizationParams().scale, input.quantizationParams().zeroPoint),
            outputQuantization = quantizationFor(output.dataType(), output.quantizationParams().scale, output.quantizationParams().zeroPoint),
        )
    }

    private fun quantizationFor(type: DataType, scale: Float, zeroPoint: Int): Quantization? {
        if (type == DataType.FLOAT32) return null
        require(scale > 0f) { "Quantized $type tensor has an invalid scale: $scale" }
        return Quantization(scale, zeroPoint)
    }

    private val SUPPORTED_TYPES = setOf(DataType.FLOAT32, DataType.INT8, DataType.UINT8)
}
