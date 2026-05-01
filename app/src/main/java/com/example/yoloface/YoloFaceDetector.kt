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

class YoloFaceDetector(context: Context, modelName: String) {
    private var interpreter: Interpreter? = null

    init {
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
        val results = mutableListOf<FaceBox>()

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

            val outputs = outputBuffer[0]
            for (i in 0 until numBoxes) {
                val conf = outputs[i][4] // Assuming index 4 is confidence
                if (conf > 0.5f) {
                    val scaleX = bitmap.width.toFloat()
                    val scaleY = bitmap.height.toFloat()
                    val rect = RectF(
                        outputs[i][0] * scaleX,
                        outputs[i][1] * scaleY,
                        outputs[i][2] * scaleX,
                        outputs[i][3] * scaleY,
                    )
                    results.add(FaceBox(rect, conf))
                }
            }
        } catch (e: Exception) {
            Log.e("YoloFaceDetector", "Inference error", e)
        }
        
        return results
    }
}
