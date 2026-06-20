# Model Startup and Selection Implementation Plan

## Goal

Add a startup page where users can import, configure, select, and run YOLO11 object-detection models on the device.

## Supported Models

- TensorFlow Lite (`.tflite`) models only.
- Users must convert PyTorch (`.pt`) models before importing them.
- Initially support YOLO11 object-detection exports only.
- Segmentation, pose, classification, and arbitrary non-YOLO models are out of scope.
- Existing bundled models remain available for selection.

## Model Import

1. Use Android's system file picker to select a `.tflite` model.
2. Require a corresponding `.txt` labels file.
3. Read one class name per line, in model class-index order.
4. Copy both files into app-private storage so they remain available permanently.
5. Collect or generate a display name for the imported model.
6. Validate the model before adding it to the model list:
   - The file can be opened by LiteRT.
   - Input and output tensor types are supported.
   - Tensor shapes are compatible with a YOLO11 detection export.
   - The input dimensions can be determined.
   - The number of labels matches the model's class count.
7. Show actionable validation errors and do not save invalid imports.

## Startup Page

The new launcher activity will:

- List bundled and imported models.
- Indicate the currently selected model.
- Remember and restore the last-selected model.
- Provide actions to import, select, rename, configure, and delete models.
- Prevent deletion of bundled models.
- Provide a **Run Detection** action that opens the detection activity with the selected model.

## Per-Model Persistent Settings

Store the following settings separately for every model:

- Confidence threshold.
- IoU/NMS threshold.
- CPU thread count.
- Front or back camera selection.
- Execution backend:
  - CPU.
  - GPU.
  - NNAPI.

If GPU or NNAPI initialization fails, show the failure and offer to switch that model to CPU. Display the backend actually being used during detection.

## Detection Refactor

Refactor the current face-only detector into a general YOLO11 object detector:

1. Load either a bundled asset or an imported model file.
2. Configure the selected execution backend and thread count.
3. Determine input dimensions from the model tensor instead of assuming `640 x 640`.
4. Support the expected YOLO11 detection output layout.
5. Transpose output data when required by the exported tensor layout.
6. Select each candidate's highest-scoring class.
7. Convert YOLO `center-x, center-y, width, height` values into rectangle bounds.
8. Apply the model's confidence threshold.
9. Apply class-aware non-maximum suppression using the model's IoU threshold.
10. Map detections correctly to the source camera image.
11. Close and replace interpreters/delegates safely when activities or models change.

## Detection UI

- Draw bounding boxes for all supported classes.
- Show the class label and confidence score for each detection.
- Use stable, distinct colors for different classes.
- Keep camera switching available.
- Display model loading and initialization errors instead of silently returning no detections.
- Display the selected model name and active execution backend.

## Storage Design

Persist metadata for each model, including:

- Stable model ID.
- Display name.
- Bundled or imported source type.
- Model file path or asset name.
- Labels file path or bundled labels source.
- Class labels.
- Confidence threshold.
- IoU threshold.
- Thread count.
- Camera selection.
- Requested execution backend.
- Last successful execution backend, if useful for diagnostics.

Imported model and label files will be stored in an app-private directory. Metadata and the last-selected model ID will be stored persistently using an Android storage mechanism appropriate for structured settings.

## Implementation Phases

### Phase 1: Model and Settings Layer

- Define model metadata and per-model settings types.
- Implement persistent metadata storage.
- Represent bundled models in the same model catalog.
- Implement import, copy, rename, update, and delete operations.

### Phase 2: Import and Validation

- Add `.tflite` and `.txt` system file pickers.
- Copy selected files into private storage.
- Inspect input/output tensors.
- Validate YOLO11 compatibility and label count.
- Handle duplicate names and interrupted imports safely.

### Phase 3: Startup and Configuration UI

- Add the launcher activity and model list.
- Add model import and management controls.
- Add the per-model settings screen.
- Add last-selection restoration and navigation to detection.

### Phase 4: General YOLO11 Detector

- Replace the fixed face detector with dynamic model loading.
- Implement YOLO11 multi-class parsing.
- Implement coordinate conversion and class-aware NMS.
- Add CPU, GPU, and NNAPI backend initialization.
- Implement explicit CPU fallback handling.

### Phase 5: Detection Activity Integration

- Pass the selected model ID to the detection activity.
- Load the model and settings before starting analysis.
- Update overlays for labels, confidence scores, and class colors.
- Surface model/backend status and errors.

### Phase 6: Verification

- Unit-test model metadata persistence.
- Unit-test label parsing and label-count validation.
- Unit-test supported and unsupported tensor shapes.
- Unit-test YOLO11 output parsing, coordinate conversion, and NMS.
- Test bundled and imported models on a physical Android device.
- Test CPU, GPU, and NNAPI initialization and fallback behavior.
- Test app restart, model deletion, corrupt files, and denied file access.

## Acceptance Criteria

- The app opens on the model-selection page.
- A user can import a valid YOLO11 `.tflite` model and `.txt` labels file.
- Imported files remain available after restarting the app.
- Bundled and imported models appear in one selectable list.
- Each model retains its own detection, camera, thread, and backend settings.
- Selecting **Run Detection** starts camera detection with the chosen model.
- Multi-class detections show correct labels, confidence scores, and boxes.
- Unsupported or invalid models produce clear errors.
- GPU or NNAPI failures can be recovered by switching the affected model to CPU.
