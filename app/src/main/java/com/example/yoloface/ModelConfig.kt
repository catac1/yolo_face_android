package com.example.yoloface

import org.json.JSONArray
import org.json.JSONObject

enum class ModelSource { ASSET, FILE }
enum class ExecutionBackend { CPU, GPU, NNAPI }

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
    val backend: ExecutionBackend = ExecutionBackend.CPU,
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
                backend = runCatching {
                    ExecutionBackend.valueOf(json.optString("backend", ExecutionBackend.CPU.name))
                }.getOrDefault(ExecutionBackend.CPU),
            )
        }
    }
}
