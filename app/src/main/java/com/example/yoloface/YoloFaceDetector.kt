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

    fun detect(bitmap: Bitmap): List<RectF> {
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
        val results = mutableListOf<RectF>()

        try {
            // Standard YOLOv8 TFLite export is often [1, num_boxes, num_attributes] e.g., [1, 8400, 5]
            if (outputShape.size == 3 && outputShape[1] > outputShape[2]) {
                val numBoxes = outputShape[1]
                val numAttributes = outputShape[2]
                val outputBuffer = Array(1) { Array(numBoxes) { FloatArray(numAttributes) } }

                currentInterpreter.run(tensorImage.buffer, outputBuffer)
                
                val outputs = outputBuffer[0]
                for (i in 0 until numBoxes) {
                    val conf = outputs[i][4] // Assuming index 4 is confidence
                    if (conf > 0.5f) {
                        val xmin = outputs[i][0]
                        val ymin = outputs[i][1]
                        val xmax = outputs[i][2]
                        val ymax = outputs[i][3]
                        
                        val left = xmin
                        val top = ymin
                        val right = xmax
                        val bottom = ymax
                        
                        val scaleX = bitmap.width.toFloat()
                        val scaleY = bitmap.height.toFloat()
                        
                        results.add(RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY))
                    }
                }
            } else if (outputShape.size == 3) {
                // Original PyTorch shape: [1, num_attributes, num_boxes] e.g., [1, 5, 8400]
                val numAttributes = outputShape[1]
                val numBoxes = outputShape[2]
                val outputBuffer = Array(1) { Array(numAttributes) { FloatArray(numBoxes) } }

                currentInterpreter.run(tensorImage.buffer, outputBuffer)
                
                val outputs = outputBuffer[0]
                for (i in 0 until numBoxes) {
                    val conf = outputs[4][i] // Assuming index 4 is confidence
                    if (conf > 0.5f) {
                        val xmin = outputs[0][i]
                        val ymin = outputs[1][i]
                        val xmax = outputs[2][i]
                        val ymax = outputs[3][i]
                        
                        val left = xmin
                        val top = ymin
                        val right = xmax
                        val bottom = ymax
                        
                        val scaleX = bitmap.width.toFloat()
                        val scaleY = bitmap.height.toFloat()
                        
                        results.add(RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("YoloFaceDetector", "Inference error", e)
        }
        
        return results
    }
}
