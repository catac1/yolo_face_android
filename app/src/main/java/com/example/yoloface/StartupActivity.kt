package com.example.yoloface

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import com.example.yoloface.databinding.ActivityStartupBinding
import java.util.concurrent.Executors

class StartupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStartupBinding
    private lateinit var repository: ModelRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var models: List<ModelConfig> = emptyList()
    private var selectedId: String? = null
    private var pendingModelUri: Uri? = null

    private val labelsPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val modelUri = pendingModelUri
        pendingModelUri = null
        if (modelUri != null && uri != null) importModel(modelUri, uri)
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingModelUri = uri
            AlertDialog.Builder(this)
                .setTitle(R.string.select_labels_title)
                .setMessage(R.string.select_labels_message)
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingModelUri = null }
                .setPositiveButton(R.string.select_labels) { _, _ ->
                    // Providers may report .txt files as application/octet-stream.
                    labelsPicker.launch(arrayOf("*/*"))
                }
                .setOnCancelListener { pendingModelUri = null }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ModelRepository(this)

        val modes = DetectionMode.entries
        binding.detectionModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.pill_only_mode),
                getString(R.string.imprint_only_mode),
                getString(R.string.two_stage_mode),
            ),
        )
        binding.detectionModeSpinner.setSelection(modes.indexOf(repository.getDetectionMode()))
        binding.detectionModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                repository.setDetectionMode(modes[position])
                refreshModels()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.modelList.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
        binding.modelList.setOnItemClickListener { _, _, position, _ ->
            selectedId = models[position].id
            refreshModels()
        }
        binding.setPillModelButton.setOnClickListener {
            selectedModel()?.let { repository.selectPillModel(it.id) }
            refreshModels()
        }
        binding.setTextModelButton.setOnClickListener {
            selectedModel()?.let { repository.selectTextModel(it.id) }
            refreshModels()
        }
        binding.importButton.setOnClickListener {
            // There is no consistently registered Android MIME type for .tflite files.
            // A single */* filter keeps them selectable across Files, Drive, and OEM pickers.
            modelPicker.launch(arrayOf("*/*"))
        }
        binding.settingsButton.setOnClickListener { selectedModel()?.let(::showSettings) }
        binding.deleteButton.setOnClickListener { selectedModel()?.let(::confirmDelete) }
        binding.runButton.setOnClickListener {
            val pillModel = repository.getPillModel()
            val textModel = repository.getTextModel()
            val mode = repository.getDetectionMode()
            if (!isReady(mode, pillModel, textModel)) {
                Toast.makeText(this, R.string.select_required_models, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PILL_MODEL_ID, pillModel?.id)
                    .putExtra(MainActivity.EXTRA_TEXT_MODEL_ID, textModel?.id)
                    .putExtra(MainActivity.EXTRA_DETECTION_MODE, mode.name)
            )
        }
        refreshModels()
    }

    private fun selectedModel() = models.firstOrNull { it.id == selectedId }

    private fun refreshModels() {
        models = repository.getModels()
        if (selectedId == null || models.none { it.id == selectedId }) {
            selectedId = repository.getTextModel()?.id ?: models.firstOrNull()?.id
        }
        val pillModel = repository.getPillModel()
        val textModel = repository.getTextModel()
        val rows = models.map { model ->
            val roles = buildList {
                if (model.id == pillModel?.id) add("PILL")
                if (model.id == textModel?.id) add("IMPRINT")
            }.joinToString(" + ")
            val marker = if (model.id == selectedId) "✓ " else ""
            val source = if (model.isBundled) "Bundled" else "Imported"
            val roleSuffix = if (roles.isEmpty()) "" else " • $roles"
            "$marker${model.displayName}\n$source • ${model.labels.size} classes • ${model.backend.name}$roleSuffix"
        }
        binding.modelList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        val selected = selectedModel()
        binding.pillModelSelection.text = pillModel?.let { getString(R.string.pill_model_selected, it.displayName) }
            ?: getString(R.string.pill_model_not_selected)
        binding.textModelSelection.text = textModel?.let { getString(R.string.text_model_selected, it.displayName) }
            ?: getString(R.string.text_model_not_selected)
        binding.deleteButton.isEnabled = selected?.isBundled == false
        binding.settingsButton.isEnabled = selected != null
        binding.setPillModelButton.isEnabled = selected != null
        binding.setTextModelButton.isEnabled = selected != null
        binding.runButton.isEnabled = isReady(repository.getDetectionMode(), pillModel, textModel)
    }

    private fun importModel(modelUri: Uri, labelsUri: Uri) {
        setBusy(true, getString(R.string.importing_model))
        val suggestedName = queryDisplayName(modelUri).removeSuffix(".tflite")
        ioExecutor.execute {
            runCatching { repository.importModel(modelUri, labelsUri, suggestedName) }
                .onSuccess { model -> runOnUiThread {
                    selectedId = model.id
                    setBusy(false)
                    refreshModels()
                } }
                .onFailure { error -> runOnUiThread {
                    setBusy(false)
                    AlertDialog.Builder(this)
                        .setTitle(R.string.import_failed)
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return "Imported model"
    }

    private fun setBusy(busy: Boolean, message: String = "") {
        binding.progressGroup.visibility = if (busy) View.VISIBLE else View.GONE
        binding.progressText.text = message
        binding.importButton.isEnabled = !busy
        binding.runButton.isEnabled = !busy && isReady(
            repository.getDetectionMode(),
            repository.getPillModel(),
            repository.getTextModel(),
        )
    }

    private fun isReady(mode: DetectionMode, pillModel: ModelConfig?, imprintModel: ModelConfig?) = when (mode) {
        DetectionMode.PILL_ONLY -> pillModel != null
        DetectionMode.IMPRINT_ONLY -> imprintModel != null
        DetectionMode.TWO_STAGE -> pillModel != null && imprintModel != null
    }

    private fun showSettings(model: ModelConfig) {
        val view = layoutInflater.inflate(R.layout.dialog_model_settings, null)
        val name = view.findViewById<EditText>(R.id.modelNameInput).apply {
            setText(model.displayName)
            isEnabled = !model.isBundled
        }
        val confidence = view.findViewById<EditText>(R.id.confidenceInput).apply { setText(model.confidenceThreshold.toString()) }
        val iou = view.findViewById<EditText>(R.id.iouInput).apply { setText(model.iouThreshold.toString()) }
        val threads = view.findViewById<EditText>(R.id.threadsInput).apply { setText(model.threadCount.toString()) }
        val exposure = view.findViewById<EditText>(R.id.exposureInput).apply { setText(model.exposureCompensation.toString()) }
        val blurThreshold = view.findViewById<EditText>(R.id.blurThresholdInput).apply { setText(model.blurThreshold.toString()) }
        val camera = view.findViewById<Spinner>(R.id.cameraSpinner).apply {
            adapter = ArrayAdapter(this@StartupActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Back camera", "Front camera"))
            setSelection(if (model.lensFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0)
        }
        val captureResolution = view.findViewById<Spinner>(R.id.captureResolutionSpinner).apply {
            adapter = ArrayAdapter(
                this@StartupActivity,
                android.R.layout.simple_spinner_dropdown_item,
                CaptureResolution.entries.map { "${it.width} × ${it.height}" },
            )
            setSelection(CaptureResolution.entries.indexOf(model.captureResolution))
        }
        val inputRegion = view.findViewById<Spinner>(R.id.inputRegionSpinner).apply {
            adapter = ArrayAdapter(
                this@StartupActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.full_frame_640), getString(R.string.center_crop_720)),
            )
            setSelection(InputRegionMode.entries.indexOf(model.inputRegionMode))
        }
        val backend = view.findViewById<Spinner>(R.id.backendSpinner).apply {
            adapter = ArrayAdapter(this@StartupActivity, android.R.layout.simple_spinner_dropdown_item, ExecutionBackend.entries.map { it.name })
            setSelection(ExecutionBackend.entries.indexOf(model.backend))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.model_settings)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val confidenceValue = confidence.text.toString().toFloatOrNull()
                val iouValue = iou.text.toString().toFloatOrNull()
                val threadValue = threads.text.toString().toIntOrNull()
                val exposureValue = exposure.text.toString().toIntOrNull()
                val blurThresholdValue = blurThreshold.text.toString().toFloatOrNull()
                if (name.text.isBlank() || confidenceValue == null || confidenceValue !in 0f..1f ||
                    iouValue == null || iouValue !in 0f..1f || threadValue == null || threadValue !in 1..8 ||
                    exposureValue == null || exposureValue !in -20..20 ||
                    blurThresholdValue == null || blurThresholdValue !in 0f..10_000f) {
                    Toast.makeText(this, R.string.invalid_settings, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val selectedInputRegion = InputRegionMode.entries[inputRegion.selectedItemPosition]
                repository.updateModel(model.copy(
                    displayName = name.text.toString().trim(),
                    confidenceThreshold = confidenceValue,
                    iouThreshold = iouValue,
                    threadCount = threadValue,
                    lensFacing = if (camera.selectedItemPosition == 1) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                    exposureCompensation = exposureValue,
                    blurThreshold = blurThresholdValue,
                    captureResolution = if (selectedInputRegion == InputRegionMode.CENTER_CROP_720) {
                        CaptureResolution.HD_1280_720
                    } else {
                        CaptureResolution.entries[captureResolution.selectedItemPosition]
                    },
                    inputRegionMode = selectedInputRegion,
                    backend = ExecutionBackend.entries[backend.selectedItemPosition],
                ))
                dialog.dismiss()
                refreshModels()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(model: ModelConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_model)
            .setMessage(getString(R.string.delete_model_message, model.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.deleteModel(model.id)
                selectedId = repository.getTextModel()?.id ?: models.firstOrNull()?.id
                refreshModels()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }
}
