package com.example.yoloface

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yoloface.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var pillDetector: YoloDetector? = null
    private var textDetector: YoloDetector? = null
    private lateinit var pillModel: ModelConfig
    private lateinit var textModel: ModelConfig
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = ModelRepository(this)
        pillModel = intent.getStringExtra(EXTRA_PILL_MODEL_ID)?.let(repository::getModel)
            ?: repository.getPillModel()
            ?: run {
                Toast.makeText(this, R.string.select_two_models, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        textModel = intent.getStringExtra(EXTRA_TEXT_MODEL_ID)?.let(repository::getModel)
            ?: repository.getTextModel()
            ?: run {
                Toast.makeText(this, R.string.select_two_models, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        lensFacing = pillModel.lensFacing
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.modelStatus.text = getString(R.string.model_loading)
        initializeDetector()

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else CameraSelector.LENS_FACING_FRONT
            pillModel = pillModel.copy(lensFacing = lensFacing)
            repository.updateModel(pillModel)
            startCamera()
        }

        if (allPermissionsGranted()) startCamera() else ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }

    private fun initializeDetector() {
        cameraExecutor.execute {
            var failedModel = pillModel
            var loadedPill: YoloDetector? = null
            var loadedText: YoloDetector? = null
            try {
                loadedPill = YoloDetector(this, pillModel)
                failedModel = textModel
                loadedText = YoloDetector(this, textModel)
                pillDetector = loadedPill
                textDetector = loadedText
                runOnUiThread {
                    binding.modelStatus.text = getString(
                        R.string.pipeline_active,
                        "${pillModel.displayName} (${pillModel.backend.name})",
                        "${textModel.displayName} (${textModel.backend.name})",
                    )
                }
            } catch (error: Throwable) {
                loadedText?.close()
                loadedPill?.close()
                Log.e(TAG, "Could not initialize ${failedModel.displayName}", error)
                runOnUiThread { showModelError(error, failedModel) }
            }
        }
    }

    private fun showModelError(error: Throwable, failedModel: ModelConfig) {
        binding.modelStatus.text = getString(R.string.model_load_failed)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.model_load_failed)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        if (failedModel.backend != ExecutionBackend.CPU) {
            builder.setPositiveButton(R.string.use_cpu) { _, _ ->
                val updated = failedModel.copy(backend = ExecutionBackend.CPU)
                ModelRepository(this).updateModel(updated)
                recreate()
            }
        } else {
            builder.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
        }
        builder.setCancelable(false).show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.previewView.surfaceProvider }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis -> analysis.setAnalyzer(cameraExecutor, ::processImage) }
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalyzer)
            } catch (error: Exception) {
                Log.e(TAG, "Camera binding failed", error)
                Toast.makeText(this, error.message ?: "Camera unavailable", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val currentPillDetector = pillDetector ?: return
            val currentTextDetector = textDetector ?: return
            val bitmap = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val pillBoxes = currentPillDetector.detect(transformed, DetectionStage.PILL)
            val textBoxes = pillBoxes
                .sortedByDescending { it.confidence }
                .take(MAX_PILL_CROPS_PER_FRAME)
                .flatMap { pill -> detectTextInPill(transformed, pill, currentTextDetector) }
            runOnUiThread {
                binding.overlayView.setResults(pillBoxes + textBoxes, transformed.width, transformed.height)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Inference failed", error)
        } finally {
            imageProxy.close()
        }
    }

    private fun detectTextInPill(
        frame: Bitmap,
        pill: DetectionBox,
        detector: YoloDetector,
    ): List<DetectionBox> {
        val paddingX = pill.bounds.width() * CROP_PADDING_RATIO
        val paddingY = pill.bounds.height() * CROP_PADDING_RATIO
        val left = (pill.bounds.left - paddingX).toInt().coerceIn(0, frame.width - 1)
        val top = (pill.bounds.top - paddingY).toInt().coerceIn(0, frame.height - 1)
        val right = (pill.bounds.right + paddingX).toInt().coerceIn(left + 1, frame.width)
        val bottom = (pill.bounds.bottom + paddingY).toInt().coerceIn(top + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        return try {
            detector.detect(crop, DetectionStage.TEXT).map { detection ->
                detection.copy(bounds = RectF(
                    detection.bounds.left + left,
                    detection.bounds.top + top,
                    detection.bounds.right + left,
                    detection.bounds.bottom + top,
                ))
            }
        } finally {
            crop.recycle()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera() else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        cameraExecutor.execute {
            textDetector?.close()
            pillDetector?.close()
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PILL_MODEL_ID = "pill_model_id"
        const val EXTRA_TEXT_MODEL_ID = "text_model_id"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "YoloDetection"
        private const val MAX_PILL_CROPS_PER_FRAME = 5
        private const val CROP_PADDING_RATIO = 0.05f
    }
}
