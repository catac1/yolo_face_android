package com.example.yoloface

import org.json.JSONArray
import org.json.JSONObject

enum class ModelSource { ASSET, FILE }
enum class ExecutionBackend { CPU, GPU, NNAPI }
enum class DetectionMode { PILL_ONLY, IMPRINT_ONLY, TWO_STAGE }
enum class CaptureResolution(val width: Int, val height: Int) {
    HD_1280_720(1280, 720),
    VGA_640_480(640, 480),
}
enum class InputRegionMode { FULL_FRAME, CENTER_CROP_720 }

data class ModelConfig(
    val id: String,
    val displayName: String,
    val source: ModelSource,
    val modelLocation: String,
    val labelsLocation: String? = null,
    val labels: List<String>,
    val confidenceThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f,
    val threadCount: Int = 4,
    val lensFacing: Int = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
    val exposureCompensation: Int = 0,
    val blurThreshold: Float = 0f,
    val captureResolution: CaptureResolution = CaptureResolution.HD_1280_720,
    val inputRegionMode: InputRegionMode = InputRegionMode.FULL_FRAME,
    val backend: ExecutionBackend = ExecutionBackend.NNAPI,
) {
    val isBundled: Boolean get() = source == ModelSource.ASSET

    fun toJson() = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("source", source.name)
        put("modelLocation", modelLocation)
        put("labelsLocation", labelsLocation)
        put("labels", JSONArray(labels))
        put("confidenceThreshold", confidenceThreshold.toDouble())
        put("iouThreshold", iouThreshold.toDouble())
        put("threadCount", threadCount)
        put("lensFacing", lensFacing)
        put("exposureCompensation", exposureCompensation)
        put("blurThreshold", blurThreshold.toDouble())
        put("captureResolution", captureResolution.name)
        put("inputRegionMode", inputRegionMode.name)
        put("backend", backend.name)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelConfig {
            val labelArray = json.getJSONArray("labels")
            return ModelConfig(
                id = json.getString("id"),
                displayName = json.getString("displayName"),
                source = ModelSource.valueOf(json.getString("source")),
                modelLocation = json.getString("modelLocation"),
                labelsLocation = json.optString("labelsLocation").takeIf { it.isNotBlank() && it != "null" },
                labels = List(labelArray.length()) { labelArray.getString(it) },
                confidenceThreshold = json.optDouble("confidenceThreshold", 0.25).toFloat(),
                iouThreshold = json.optDouble("iouThreshold", 0.45).toFloat(),
                threadCount = json.optInt("threadCount", 4).coerceIn(1, 8),
                lensFacing = json.optInt("lensFacing", androidx.camera.core.CameraSelector.LENS_FACING_BACK),
                exposureCompensation = json.optInt("exposureCompensation", 0),
                blurThreshold = json.optDouble("blurThreshold", 0.0).toFloat(),
                captureResolution = runCatching {
                    CaptureResolution.valueOf(
                        json.optString("captureResolution", CaptureResolution.HD_1280_720.name)
                    )
                }.getOrDefault(CaptureResolution.HD_1280_720),
                inputRegionMode = runCatching {
                    InputRegionMode.valueOf(json.optString("inputRegionMode", InputRegionMode.FULL_FRAME.name))
                }.getOrDefault(InputRegionMode.FULL_FRAME),
                backend = runCatching {
                    ExecutionBackend.valueOf(json.optString("backend", ExecutionBackend.NNAPI.name))
                }.getOrDefault(ExecutionBackend.NNAPI),
            )
        }
    }
}
