package com.example.yoloface

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var faceDetector: YoloFaceDetector? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val KEY_LENS_FACING = "key_lens_facing"
        private const val PREFS_NAME = "yolo_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lensFacing = prefs.getInt(KEY_LENS_FACING, CameraSelector.LENS_FACING_FRONT)

        savedInstanceState?.let {
            lensFacing = it.getInt(KEY_LENS_FACING, lensFacing)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize YOLO detector
        Thread {
            faceDetector = YoloFaceDetector(this, "yolo26n-face_float16_from-macos.tflite")
        }.start()

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit {
                    putInt(KEY_LENS_FACING, lensFacing)
                }
            startCamera()
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Using selected camera lens
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("YoloFace", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val detector = faceDetector ?: run {
            imageProxy.close()
            return
        }

        val bitmap = imageProxy.toBitmap()
        
        // Adjust for rotation and front camera mirroring
        val matrix = Matrix()
        matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            // Front camera is mirrored
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        val boxes = detector.detect(rotatedBitmap)
        Log.e("box count", "${boxes.size}")
        for ( box in boxes) {
            Log.e("box shape", "((${box.bounds.left}, ${box.bounds.top}, ${box.bounds.width()}, ${box.bounds.height()}) conf: ${box.confidence})")
        }
        
        val imgWidth = rotatedBitmap.width
        val imgHeight = rotatedBitmap.height
        
        runOnUiThread {
            binding.overlayView.setResults(boxes, imgWidth, imgHeight)
        }
        
        imageProxy.close()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_LENS_FACING, lensFacing)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
