package com.example.yoloface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

class YoloFaceDetector(context: Context, modelName: String) {
    private var module: Module? = null

    init {
        val assetPath = assetFilePath(context, modelName)
        if (File(assetPath).exists()) {
            try {
                module = Module.load(assetPath)
            } catch (e: Exception) {
                Log.e("YoloFaceDetector", "Error loading model", e)
            }
        } else {
            Log.e("YoloFaceDetector", "Model file not found in assets")
        }
    }

    fun detect(bitmap: Bitmap): List<RectF> {
        val currentModule = module ?: return emptyList()

        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        
        // Prepare input tensor (YOLO expects RGB float32 0-1)
        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
            resizedBitmap,
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(1.0f, 1.0f, 1.0f)
        )

        val results = mutableListOf<RectF>()
        try {
            // Run inference
            val outputIValue = currentModule.forward(IValue.from(inputTensor))
            
            // Output might be a tuple or a single tensor
            val outputTensor = if (outputIValue.isTuple) {
                outputIValue.toTuple()[0].toTensor()
            } else {
                outputIValue.toTensor()
            }
            
            val outputs = outputTensor.dataAsFloatArray
            val shape = outputTensor.shape()

            // Standard YOLO output: [1, num_classes + 4, num_anchors]
            if (shape.size >= 3) {
                val numAttributes = shape[1].toInt()
                val numBoxes = shape[2].toInt()
                
                for (i in 0 until numBoxes) {
                    val conf = outputs[4 * numBoxes + i] // index 4 is objectness/confidence
                    
                    if (conf > 0.5f) {
                        val cx = outputs[0 * numBoxes + i]
                        val cy = outputs[1 * numBoxes + i]
                        val w = outputs[2 * numBoxes + i]
                        val h = outputs[3 * numBoxes + i]
                        
                        val left = cx - w / 2
                        val top = cy - h / 2
                        val right = cx + w / 2
                        val bottom = cy + h / 2
                        
                        // Scale back to original bitmap size
                        val scaleX = bitmap.width / 640f
                        val scaleY = bitmap.height / 640f
                        
                        results.add(RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY))
                    }
                }
            } else if (shape.size == 2) {
                 // Alternate parsing if output is [num_boxes, 6]
                 val numBoxes = shape[0].toInt()
                 val numAttributes = shape[1].toInt()
                 for (i in 0 until numBoxes) {
                     val baseIdx = i * numAttributes
                     val conf = outputs[baseIdx + 4]
                     if (conf > 0.5f) {
                         val cx = outputs[baseIdx + 0]
                         val cy = outputs[baseIdx + 1]
                         val w = outputs[baseIdx + 2]
                         val h = outputs[baseIdx + 3]
                         
                         val left = cx - w / 2
                         val top = cy - h / 2
                         val right = cx + w / 2
                         val bottom = cy + h / 2
                         
                         val scaleX = bitmap.width / 640f
                         val scaleY = bitmap.height / 640f
                         
                         results.add(RectF(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY))
                     }
                 }
            }
        } catch (e: Exception) {
            Log.e("YoloFaceDetector", "Inference error", e)
        }
        
        return results
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        try {
            context.assets.open(assetName).use { `is` ->
                FileOutputStream(file).use { os ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (`is`.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                    os.flush()
                }
                return file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return file.absolutePath
        }
    }
}
