package com.example.yoloface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class FaceBox(val bounds: RectF, val confidence: Float)

class YoloFaceDetector {
    internal var interpreter: Interpreter? = null

    constructor(context: Context, modelName: String) {
        try {
            val model = loadModelFile(context, modelName)
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            Log.e("YoloFaceDetector", "Error loading model", e)
        }
    }

    internal constructor(interpreter: Interpreter?) {
        this.interpreter = interpreter
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(bitmap: Bitmap): List<FaceBox> {
        val currentInterpreter = interpreter ?: return emptyList()

        // Setup image processor for YOLO: resize to 640x640, normalize to 0-1
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(640, 640, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0.0f, 255.0f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Acquire output shape dynamically
        val outputShape = currentInterpreter.getOutputTensor(0).shape()

        try {

            /*
             * NOTE:
             *    Standard YOLOv8 TFLite export is often [1, num_boxes, num_attributes] e.g., [1, 8400, 5]
             *    Original PyTorch shape:                [1, num_attributes, num_boxes] e.g., [1, 5, 8400]
             *
             *   This app uses TFLite model.
             *
             */

            val numBoxes = outputShape[1]
            val numAttributes = outputShape[2]
            val outputBuffer = Array(1) { Array(numBoxes) { FloatArray(numAttributes) } }

            currentInterpreter.run(tensorImage.buffer, outputBuffer)

            return parseOutputs(outputBuffer[0], bitmap.width.toFloat(), bitmap.height.toFloat())
        } catch (e: Exception) {
            Log.e("YoloFaceDetector", "Inference error", e)
        }

        return emptyList()
    }

    internal fun parseOutputs(
        outputs: Array<FloatArray>,
        imageWidth: Float,
        imageHeight: Float
    ): List<FaceBox> {
        val results = mutableListOf<FaceBox>()
        for (row in outputs) {
            val conf = row[4]
            if (conf > 0.5f) {
                results.add(
                    FaceBox(
                        RectF(
                            row[0] * imageWidth,
                            row[1] * imageHeight,
                            row[2] * imageWidth,
                            row[3] * imageHeight,
                        ),
                        conf,
                    )
                )
            }
        }
        return results
    }
}
