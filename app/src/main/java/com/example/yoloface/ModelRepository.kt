package com.example.yoloface

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

class ModelRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelRoot = File(context.filesDir, "models")

    fun getModels(): List<ModelConfig> {
        val imported = runCatching {
            val array = JSONArray(preferences.getString(KEY_IMPORTED_MODELS, "[]"))
            List(array.length()) { ModelConfig.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return imported
    }

    fun getModel(id: String): ModelConfig? = getModels().firstOrNull { it.id == id }

    fun getSelectedModel(): ModelConfig? {
        val models = getModels()
        val selectedId = preferences.getString(KEY_TEXT_MODEL, preferences.getString(KEY_SELECTED_MODEL, null))
        return models.firstOrNull { it.id == selectedId } ?: models.firstOrNull()
    }

    fun getPillModel(): ModelConfig? = preferences.getString(KEY_PILL_MODEL, null)?.let(::getModel)

    fun getTextModel(): ModelConfig? = preferences.getString(
        KEY_TEXT_MODEL,
        preferences.getString(KEY_SELECTED_MODEL, null),
    )?.let(::getModel)

    fun getDetectionMode(): DetectionMode = runCatching {
        DetectionMode.valueOf(preferences.getString(KEY_DETECTION_MODE, DetectionMode.IMPRINT_ONLY.name)!!)
    }.getOrDefault(DetectionMode.IMPRINT_ONLY)

    fun setDetectionMode(mode: DetectionMode) {
        preferences.edit { putString(KEY_DETECTION_MODE, mode.name) }
    }

    fun selectModel(id: String) {
        selectTextModel(id)
    }

    fun selectPillModel(id: String) {
        require(getModel(id) != null) { "Unknown model" }
        preferences.edit { putString(KEY_PILL_MODEL, id) }
    }

    fun selectTextModel(id: String) {
        require(getModel(id) != null) { "Unknown model" }
        preferences.edit {
            putString(KEY_TEXT_MODEL, id)
            putString(KEY_SELECTED_MODEL, id)
        }
    }

    fun importModel(modelUri: Uri, labelsUri: Uri, displayName: String): ModelConfig {
        val labels = readLabels(labelsUri)
        require(labels.isNotEmpty()) { "Labels file is empty" }

        val id = UUID.randomUUID().toString()
        val finalDirectory = File(modelRoot, id)
        val temporaryDirectory = File(modelRoot, ".$id.tmp")
        temporaryDirectory.mkdirs()
        try {
            val modelFile = File(temporaryDirectory, "model.tflite")
            val labelsFile = File(temporaryDirectory, "labels.txt")
            context.contentResolver.openInputStream(modelUri)?.use { input ->
                modelFile.outputStream().use(input::copyTo)
            } ?: error("Could not read model file")
            labelsFile.writeText(labels.joinToString("\n"))

            ModelInspector.validate(modelFile, labels.size)
            require(temporaryDirectory.renameTo(finalDirectory)) { "Could not save imported model" }

            val config = ModelConfig(
                id = id,
                displayName = displayName.trim().ifEmpty { "Imported model" },
                source = ModelSource.FILE,
                modelLocation = File(finalDirectory, "model.tflite").absolutePath,
                labelsLocation = File(finalDirectory, "labels.txt").absolutePath,
                labels = labels,
            )
            saveImported(getModels().filterNot { it.isBundled } + config)
            return config
        } catch (exception: Exception) {
            temporaryDirectory.deleteRecursively()
            finalDirectory.deleteRecursively()
            throw exception
        }
    }

    private fun readLabels(uri: Uri): List<String> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_LABEL_FILE_BYTES) {
                    "Labels must be a small .txt file with one class name per line"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Could not read labels file")
        require(bytes.none { it == 0.toByte() }) {
            "The selected labels file is binary. Select a .txt file with one class name per line"
        }
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            error("Labels must be a UTF-8 .txt file with one class name per line")
        }
        val labels = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(labels.size <= MAX_LABEL_COUNT) {
            "Labels file has too many lines. Use one class name per line"
        }
        return labels
    }

    fun updateModel(updated: ModelConfig) {
        getModel(updated.id) ?: error("Model no longer exists")
        saveImported(getModels().map { if (it.id == updated.id) updated else it })
    }

    fun deleteModel(id: String) {
        val model = getModel(id) ?: return
        File(model.modelLocation).parentFile?.deleteRecursively()
        val remaining = getModels().filterNot { it.id == id }
        saveImported(remaining)
        preferences.edit {
            if (preferences.getString(KEY_PILL_MODEL, null) == id) remove(KEY_PILL_MODEL)
            if (preferences.getString(KEY_TEXT_MODEL, null) == id) remove(KEY_TEXT_MODEL)
            if (preferences.getString(KEY_SELECTED_MODEL, null) == id) remove(KEY_SELECTED_MODEL)
        }
    }

    private fun saveImported(models: List<ModelConfig>) {
        preferences.edit { putString(KEY_IMPORTED_MODELS, encode(models)) }
    }

    private fun encode(models: List<ModelConfig>) = JSONArray().apply { models.forEach { put(it.toJson()) } }.toString()

    companion object {
        private const val PREFS_NAME = "model_catalog"
        private const val KEY_IMPORTED_MODELS = "imported_models"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_PILL_MODEL = "pill_model"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_DETECTION_MODE = "detection_mode"
        private const val MAX_LABEL_FILE_BYTES = 256 * 1024
        private const val MAX_LABEL_COUNT = 10_000
    }
}
