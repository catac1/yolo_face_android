package com.example.yoloface

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class DetectionBox(
    val bounds: RectF,
    val confidence: Float,
    val classId: Int,
    val label: String,
    val stage: DetectionStage,
)

enum class DetectionStage { PILL, TEXT }

class YoloDetector(private val context: Context, val config: ModelConfig) : AutoCloseable {
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private val interpreter: Interpreter
    private val shape: ModelInspector.ModelShape

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(config.threadCount)
            when (config.backend) {
                ExecutionBackend.CPU -> Unit
                ExecutionBackend.GPU -> {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                }
                ExecutionBackend.NNAPI -> {
                    nnApiDelegate = NnApiDelegate()
                    addDelegate(nnApiDelegate)
                }
            }
        }
        try {
            interpreter = when (config.source) {
                ModelSource.ASSET -> Interpreter(loadAsset(config.modelLocation), options)
                ModelSource.FILE -> Interpreter(File(config.modelLocation), options)
            }
            shape = ModelInspector.inspect(interpreter, config.labels.size)
        } catch (error: Throwable) {
            gpuDelegate?.close()
            nnApiDelegate?.close()
            throw error
        }
    }

    fun detect(bitmap: Bitmap, stage: DetectionStage): List<DetectionBox> {
        val outputShape = interpreter.getOutputTensor(0).shape()
        val input = TensorBufferCodec.bitmapToInput(bitmap, shape)
        val outputBuffer = TensorBufferCodec.allocateOutput(shape.outputType, outputShape.drop(1).reduce(Int::times))
        interpreter.run(input, outputBuffer)
        val output = TensorBufferCodec.decodeOutput(outputBuffer, shape, outputShape[1], outputShape[2])
        return YoloOutputParser.parse(
            raw = output,
            attributeFirst = shape.attributeFirst,
            labels = config.labels,
            confidenceThreshold = config.confidenceThreshold,
            iouThreshold = config.iouThreshold,
            inputWidth = shape.inputWidth,
            inputHeight = shape.inputHeight,
            imageWidth = bitmap.width.toFloat(),
            imageHeight = bitmap.height.toFloat(),
            stage = stage,
        )
    }

    private fun loadAsset(assetName: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(assetName)
        return FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }.also { descriptor.close() }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
    }
}
