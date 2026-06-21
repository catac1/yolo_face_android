package com.example.yoloface

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.yoloface.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var pillDetector: YoloDetector? = null
    private var textDetector: YoloDetector? = null
    private var pillModel: ModelConfig? = null
    private var textModel: ModelConfig? = null
    private lateinit var detectionMode: DetectionMode
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var activeCamera: Camera? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = ModelRepository(this)
        detectionMode = intent.getStringExtra(EXTRA_DETECTION_MODE)?.let {
            runCatching { DetectionMode.valueOf(it) }.getOrNull()
        } ?: repository.getDetectionMode()
        pillModel = intent.getStringExtra(EXTRA_PILL_MODEL_ID)?.let(repository::getModel)
            ?: repository.getPillModel()
        textModel = intent.getStringExtra(EXTRA_TEXT_MODEL_ID)?.let(repository::getModel)
            ?: repository.getTextModel()
        if (!hasRequiredModels()) {
            Toast.makeText(this, R.string.select_required_models, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lensFacing = primaryModel().lensFacing
        cameraExecutor = Executors.newSingleThreadExecutor()
        binding.modelStatus.text = getString(R.string.model_loading)
        initializeDetector()

        binding.previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
                focusAt(event.x, event.y)
            }
            true
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else CameraSelector.LENS_FACING_FRONT
            val updated = primaryModel().copy(lensFacing = lensFacing)
            repository.updateModel(updated)
            if (detectionMode == DetectionMode.IMPRINT_ONLY) textModel = updated else pillModel = updated
            startCamera()
        }

        if (allPermissionsGranted()) startCamera() else ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )
    }

    private fun initializeDetector() {
        cameraExecutor.execute {
            var failedModel = primaryModel()
            var loadedPill: YoloDetector? = null
            var loadedText: YoloDetector? = null
            try {
                if (detectionMode != DetectionMode.IMPRINT_ONLY) {
                    failedModel = requireNotNull(pillModel)
                    loadedPill = YoloDetector(this, failedModel)
                }
                if (detectionMode != DetectionMode.PILL_ONLY) {
                    failedModel = requireNotNull(textModel)
                    loadedText = YoloDetector(this, failedModel)
                }
                pillDetector = loadedPill
                textDetector = loadedText
                runOnUiThread {
                    binding.modelStatus.text = when (detectionMode) {
                        DetectionMode.PILL_ONLY -> getString(R.string.single_pipeline_active, modelStatus(requireNotNull(pillModel)))
                        DetectionMode.IMPRINT_ONLY -> getString(R.string.single_pipeline_active, modelStatus(requireNotNull(textModel)))
                        DetectionMode.TWO_STAGE -> getString(
                            R.string.pipeline_active,
                            modelStatus(requireNotNull(pillModel)),
                            modelStatus(requireNotNull(textModel)),
                        )
                    }
                }
            } catch (error: Throwable) {
                loadedText?.close()
                loadedPill?.close()
                Log.e(TAG, "Could not initialize ${failedModel.displayName}", error)
                runOnUiThread { showModelError(error, failedModel) }
            }
        }
    }

    private fun modelStatus(model: ModelConfig) = "${model.displayName} (${model.backend.name})"

    private fun hasRequiredModels() = when (detectionMode) {
        DetectionMode.PILL_ONLY -> pillModel != null
        DetectionMode.IMPRINT_ONLY -> textModel != null
        DetectionMode.TWO_STAGE -> pillModel != null && textModel != null
    }

    private fun primaryModel() = when (detectionMode) {
        DetectionMode.IMPRINT_ONLY -> requireNotNull(textModel)
        DetectionMode.PILL_ONLY, DetectionMode.TWO_STAGE -> requireNotNull(pillModel)
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
                val camera = cameraProvider.bindToLifecycle(this, selector, preview, imageAnalyzer)
                activeCamera = camera
                val exposureRange = camera.cameraInfo.exposureState.exposureCompensationRange
                if (exposureRange.lower <= exposureRange.upper) {
                    val appliedExposure = primaryModel().exposureCompensation.coerceIn(
                        exposureRange.lower,
                        exposureRange.upper,
                    )
                    camera.cameraControl.setExposureCompensationIndex(appliedExposure)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Camera binding failed", error)
                Toast.makeText(this, error.message ?: "Camera unavailable", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun focusAt(x: Float, y: Float) {
        val camera = activeCamera ?: return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val results = when (detectionMode) {
                DetectionMode.PILL_ONLY -> pillDetector?.detect(transformed, DetectionStage.PILL) ?: return
                DetectionMode.IMPRINT_ONLY -> textDetector?.detect(transformed, DetectionStage.TEXT) ?: return
                DetectionMode.TWO_STAGE -> {
                    val currentPillDetector = pillDetector ?: return
                    val currentTextDetector = textDetector ?: return
                    val pillBoxes = currentPillDetector.detect(transformed, DetectionStage.PILL)
                    val textBoxes = pillBoxes
                        .sortedByDescending { it.confidence }
                        .take(MAX_PILL_CROPS_PER_FRAME)
                        .flatMap { pill -> detectTextInPill(transformed, pill, currentTextDetector) }
                    pillBoxes + textBoxes
                }
            }
            runOnUiThread {
                binding.overlayView.setResults(results, transformed.width, transformed.height)
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
        const val EXTRA_DETECTION_MODE = "detection_mode"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "YoloDetection"
        private const val MAX_PILL_CROPS_PER_FRAME = 5
        private const val CROP_PADDING_RATIO = 0.05f
    }
}
