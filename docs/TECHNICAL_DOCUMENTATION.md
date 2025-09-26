# MakeACopy — Comprehensive Technical Documentation

Version: 2025-09-26

Author: Project Maintainers

Status: Living document (kept in-repo; contributions welcome)


## 1. Executive Summary

MakeACopy is a privacy‑first, offline document scanning application for Android. It enables users to digitize paper documents by capturing images via the device camera, detecting the document’s edges assisted by a lightweight ML model (ONNX, required), performing perspective correction and visual enhancement, then applying offline OCR to produce text‑searchable PDFs or high‑quality JPEG exports. The app follows a Single‑Activity, Multi‑Fragment navigation structure and leverages an MVVM architecture to separate concerns between UI and business logic.

Core technology choices include:
- CameraX/Camera2 for capture.
- OpenCV for image processing (edge detection, perspective warp, filters).
- ONNX Runtime with a DocAligner model for ML‑assisted corner detection (required).
- Tesseract (via tess-two) for offline OCR.
- pdfbox-android for constructing PDFs, including a precisely aligned invisible text layer.

The app is designed to be fully functional offline, avoiding cloud services, telemetry, tracking, or analytics. All critical operations run on-device. The build and CI pipeline are structured to be F‑Droid compliant by building native dependencies (OpenCV and ONNX Runtime) from source without shipping prebuilt binaries in the repository.

This document is deliberately exhaustive. It dives into architecture, data flow, algorithms, coordinate systems, threading, memory management, build and CI/CD, security and privacy, and operational practices. It also documents tradeoffs, limitations, and avenues for future work. The intention is to equip any engineer—new contributor, maintainer, auditor, or advanced user—with enough context to modify or extend MakeACopy safely and effectively.


## 2. Product Goals and Non‑Goals

- Goals
  - Enable high‑quality scanning workflows entirely offline.
  - Provide robust edge detection and perspective correction.
  - Offer multiple image enhancement filters, including grayscale and black‑and‑white conversions.
  - Create searchable PDFs with an OCR text layer perfectly aligned to the corresponding image content.
  - Export JPEG images at configurable quality and color modes.
  - Allow multi‑page document assembly with page reorder, deletion, and inline OCR.
  - Maintain a privacy‑respecting experience with minimal permissions (camera, storage) and no data exfiltration.
  - Provide a maintainable, testable, modular codebase using MVVM and clear utilities.
  - Support a wide array of devices with CameraX and degrade gracefully on edge cases.
  - Be F‑Droid compliant: build all native artifacts from source.

- Non‑Goals
  - Cloud OCR, cloud storage, or any server‑side processing.
  - Telemetry, analytics, or third‑party tracking.
  - Proprietary SDK tie‑ins or unreviewable binary blobs in the repo.

Rationale: These constraints exist to maximize user trust and ensure long‑term maintainability and reproducibility. In regulated environments or sensitive contexts (e.g., scanning private records), offline guarantees and clear provenance are essential.


## 3. High‑Level Architecture

### 3.1 Architectural Style

- Single‑Activity application using the Android Jetpack Navigation component to manage multiple fragments.
- MVVM (Model‑View‑ViewModel) pattern to separate UI rendering (Fragments, Adapters, Views) from business logic and state management (ViewModels, Repositories/Utilities).
- Clean layering around utilities for imaging (OpenCVUtils) and document generation (PdfCreator), with UI‑specific adapters and fragments calling into these utilities.

This layered approach simplifies testing and reuse:
- UI orchestration lives in Fragments and Adapters, reacting to ViewModel state.
- Heavy lifting (OpenCV ops, ONNX inference, PDF assembly) lives in utilities that are platform‑agnostic and testable.

### 3.2 Key Modules and Responsibilities

- UI Fragments:
  - CameraFragment: Camera capture, sensor hints (low‑light prompt), flash control, image persistence, navigation to crop.
  - Crop Fragment (via CropViewModel): Perspective correction, manual adjustment of detected corners, validation of quadrilateral geometry.
  - OCR Fragment: Language selection, running OCR on processed pages, presenting results.
  - Export Fragment: Page list management, export settings (DPI, color mode, quality), export execution and share.

- Utilities:
  - OpenCVUtils: OpenCV initialization, ONNX session management, classic CV corner detection, perspective warp, color transforms, scaling, optional debug image emission.
  - PdfCreator: Page layout computation, DPI scaling, PDF page box harmonization, image placement, text layer alignment, font loading and fallbacks, streaming output.

- Adapters:
  - ExportPagesAdapter: Efficient page list with LruCache thumbnails, click/reorder/remove/inline OCR callbacks.

- Data/Model Objects:
  - CompletedScan: Represents a processed page with metadata (paths, timestamps, processing state).
  - RecognizedWord: OCR word text and bounding box in image coordinates; methods for transforms and clipping.

- External Libraries:
  - CameraX/Camera2 (AndroidX), OpenCV, ONNX Runtime, DocAligner model, tess-two, pdfbox-android, Material Components.

### 3.3 Data Flow Overview

1. Capture: User opens CameraFragment, frames document, optionally uses flash; application captures an image to storage.
2. Edge Detection: The app identifies document corners via OpenCV with ONNX model guidance to improve robustness (ONNX required).
3. Perspective Correction: The raw image is warped to a top‑down view using the detected quadrilateral.
4. Enhancement: Filters may be applied (grayscale, contrast, sharpening, black‑and‑white thresholding, etc.).
5. OCR: Tesseract recognizes text and outputs words with bounding boxes in image coordinates.
6. Export: PdfCreator composes a PDF page with the enhanced image and overlays an invisible text layer that exactly aligns with the image pixels after the page transform. Alternatively, JPEG export is performed.
7. Session Management: Users can handle multiple pages, reorder, remove, run inline OCR, and pick which pages to export.


## 4. Codebase Layout and Conventions

- app/src/main/java/de/schliweb/makeacopy
  - ui/...
    - camera/CameraFragment.java — capture flow, flashlight, sensors, permissions.
    - crop/... — UI and ViewModel for perspective correction (CropViewModel referenced by CameraFragment).
    - export/session/ExportPagesAdapter.java — paged export UI list handling.
  - utils/OpenCVUtils.java — OpenCV and ONNX Runtime functions, image math, transformations, filters.
  - utils/PdfCreator.java — PDF assembly logic, OCR text layer alignment, DPI scaling.
- app/src/main/res
  - layout/... — screen and component XML layouts (e.g., fragment_completed_scans_picker.xml).
  - drawable/... — vector and bitmap assets (icons for image, flash off, select/deselect all, etc.).
- external/
  - opencv — submodule with OpenCV source for native build.
  - onnxruntime — submodule with ONNX Runtime source for native build.
- docs/
  - Technical documentation lives here (this file).
- scripts/
  - Automation scripts to build native dependencies (OpenCV, ONNX Runtime) and prepare artifacts.
- .github/workflows/build-release.yml
  - CI/CD workflow for building, signing, verifying, and publishing releases.

Conventions:
- Prefer explicit logging tags (e.g., TAG fields) and actionable messages.
- Guard native initializations to avoid repeated cost (e.g., isInitialized in OpenCVUtils).
- Use immutable or defensive copies for lists passed between layers (e.g., submitList semantics in adapters).
- Avoid heavy work on main thread; dispatch to background where necessary.


## 5. Dependencies and Third‑Party Components

- AndroidX CameraX and Camera2 interop.
- OpenCV for image processing (Apache 2.0).
- ONNX Runtime for Android (MIT), built CPU‑only with Java bindings; required for the edge detection pipeline.
- DocAligner model (Apache 2.0), shipped as an asset and loaded at runtime (see OpenCVUtils MODEL_ASSET_PATH).
- tess-two (Apache 2.0) to access Tesseract OCR.
- pdfbox-android (Apache 2.0) for PDF authoring.
- Material Components for UI.

All native artifacts are produced by CI from source to maintain F‑Droid compatibility. No proprietary SDKs are used.

Version pinning and reproducibility:
- CI pins JDK, NDK, CMake, and Python versions to minimize drift.
- Submodules are checked out at known commits.
- Scripts collect reproducibility evidence for native builds.


## 6. Security, Privacy, and Permissions

- Privacy
  - 100% offline by design. No telemetry, analytics, or cloud connections.
  - OCR is performed locally; no images or text leaves the device unless a user shares/exports.
  - No background services upload content; no remote endpoints are contacted.

- Permissions
  - Camera permission is required for capture.
  - Storage/document permissions (scoped storage or SAF URIs) are used to write exports and sometimes to read inputs from gallery flows.
  - Flashlight usage leverages camera controls; no extra permissions beyond camera.

- Security considerations
  - FileProvider is used for secure URI sharing when necessary (e.g., sharing exports to other apps).
  - The app avoids writing to world‑readable locations and uses content URIs where possible.
  - PDF creation does not embed active content; only image data and invisible text are written.
  - Assets (e.g., ONNX model) are copied to app cache with controlled file names to avoid path traversal.
  - Exceptions are caught and logged; user‑visible errors do not reveal sensitive file system paths.

Threat model overview:
- Local adversary with device access: relies on Android sandboxing; MakeACopy stores only user files the user chooses to keep.
- Malicious document content: images only; no executable content is processed or embedded.
- Supply chain: native dependencies are built from source in CI; version pinning reduces risk of unreviewed changes.


## 7. Core Workflows

### 7.1 Camera Capture

CameraFragment orchestrates device camera capture using CameraX. Notable aspects:
- Permission handling via ActivityResultContracts.
- Flashlight control and low‑light prompts using SensorManager and the device’s ambient light sensor (LOW_LIGHT_THRESHOLD, debounced by MIN_TIME_BETWEEN_PROMPTS).
- Orientation awareness for correct image rotation.
- Safe error handling during camera initialization and capture.

Initialization sequence (simplified):
- Check permissions; if missing, request via ActivityResultLauncher.
- Acquire ProcessCameraProvider asynchronously.
- Bind Preview and ImageCapture use cases to lifecycle; attach Preview to PreviewView.
- If available, enable/disable torch based on user toggles and sensor hints.

Capture:
- On shutter press, ImageCapture takes a photo and writes to an app‑controlled file.
- After success, navigate to crop/review with a URI; handle failures with user feedback.

Common pitfalls handled:
- Rebinding camera on configuration changes.
- Ensuring torch state and availability are consistent across devices.

### 7.2 Edge Detection and Perspective Correction

OpenCVUtils provides methods to:
- Initialize OpenCV native support (System.loadLibrary("opencv_java4")) and configure safe modes.
- Initialize an ONNX Runtime session and load the DocAligner model from assets (required). The path is set by MODEL_ASSET_PATH and copied to cache via the app’s AssetManager.
- Detect corners through classical CV and ML assistance via the ONNX model to improve robustness on challenging backgrounds or lighting (ONNX required).
- Compute perspective transforms and warp the image to a top‑down view.

Corner detection strategy (conceptual):
- Preprocess (grayscale, blur, optional adaptive threshold) to stabilize edges.
- Find contours; filter by area, convexity, and rectangularity metrics.
- Rank candidates; fuse with ONNX predictions (probability maps or point predictions) to select the most plausible quadrilateral (ONNX required).
- Enforce consistent point order (TL, TR, BR, BL) for downstream transforms.

Perspective correction:
- Compute homography H mapping detected quad -> rectangle.
- Warp image using H; choose target size based on page aspect ratio and DPI preferences.

Manual override:
- Crop screen allows users to drag corners; geometry validators prevent self‑intersections and degenerate quads.

### 7.3 Image Enhancement

Goals: enhance legibility, reduce noise, and optimize file size.
- Grayscale: convert RGB to single channel using luminance‑weighted formula.
- Black‑and‑white: apply global or adaptive thresholding; optionally morphologies to clean speckles.
- Sharpening: unsharp mask style or Laplacian‑based enhancement tuned to avoid haloing text strokes.
- Contrast: histogram equalization or CLAHE variants (careful with over‑boosting noise).
- Scaling: resample to a target DPI with high‑quality filters for PDF embedding.

### 7.4 OCR

Using tess-two, the app performs OCR in the chosen language(s).
- Input: enhanced, rectified bitmap.
- Output: list of RecognizedWord { text, bbox } in image pixels.
- Multi‑language: users may select from supported packs; engine runs fully offline.

Accuracy factors:
- Clean binarized images often improve OCR of printed text.
- For cursive or noisy scans, grayscale may outperform harsh binarization.
- DPI around 300 is a good tradeoff; upscaling low‑res images rarely helps.

### 7.5 Export to PDF

PdfCreator creates text‑searchable PDFs by:
- Preparing the bitmap via OpenCVUtils to the desired color mode (grayscale or black‑and‑white) and target DPI.
- Generating a PDDocument and a PDPage (typically A4). The page boxes (MediaBox, CropBox, BleedBox, TrimBox, ArtBox) are harmonized to minimize viewer variance.
- Placing the page image with a scale preserving aspect ratio and centering within the page.
- Overlaying the OCR text layer using the exact same current transformation matrix (CTM) as the image, ensuring 1:1 alignment between text bounding boxes and pixels even after page scaling and letterboxing. This eliminates the common “drift” observed in PDF viewers.
- Embedding fonts with fallbacks and clamping font sizes to a sensible minimum for tiny OCR boxes.
- Saving to an OutputStream acquired from a content URI.

### 7.6 Export to JPEG

- Use JPEGFactory with quality parameter; grayscale or BW conversions can be applied first.
- For archival quality, prefer higher quality or lossless export via PDF.
- Use Android’s sharing intents to disseminate the image.

### 7.7 Multi‑Page Session Management

ExportPagesAdapter powers a RecyclerView where users:
- See thumbnail previews of pages.
- Select/deselect and reorder pages.
- Remove unwanted pages.
- Trigger inline OCR per page.

Implementation notes:
- Thumbnails are decoded with inSampleSize targeting view dimensions, not full resolution.
- Cache eviction policy: small LruCache (size 32) is sufficient for typical sessions and keeps memory steady.


## 8. Detailed Component Documentation

### 8.1 CameraFragment

Responsibilities:
- Handle permissions, initialize camera, bind Preview and ImageCapture use cases.
- Monitor ambient light via SensorManager; prompt users to enable flash in low light.
- Capture images, save them, and route to cropping.
- Manage UI states: camera mode vs. review mode, flash toggle UI, back press handling.

Key internals (based on source comments and imports):
- Uses ProcessCameraProvider and PreviewView to render the camera feed.
- Applies orientation awareness to set rotation for captures.
- Uses ViewModels (e.g., CropViewModel) to pass data between fragments.
- Handles error paths: graceful fallback with toasts/dialogs on failure.

Threading:
- CameraX handles capture threads; UI updates posted via main thread handlers.
- Sensor callbacks may arrive quickly; debounce logic ensures UI prompts aren’t spammy.

Resource management:
- onResume registers sensors; onPause unregisters.
- onDestroyView tears down bindings to avoid leaks.

### 8.2 OpenCVUtils

Responsibilities:
- Initialize OpenCV and configure safe mode.
- Initialize ONNX Runtime and load the DocAligner ONNX model from assets to cache, creating a native session with optimized options and thread settings.
- Provide image processing utilities used across the app: 
  - Corner detection (classical and ML‑assisted).
  - Perspective transform computation and warp.
  - Color space conversions (RGB, grayscale, black‑and‑white thresholding).
  - Geometric operations and matrix math.
  - Optional debug image generation (USE_DEBUG_IMAGES set to false by default).

Initialization:
- System.loadLibrary("opencv_java4") is called explicitly, logging success and continuing with ONNX init.
- The init method is guarded by isInitialized to avoid repeated initialization.

ONNX Runtime specifics:
- OrtEnvironment is set up lazily and globally.
- OrtSession is created with optimization level ALL and a conservative number of threads (roughly half of available CPUs) to balance performance and thermals.
- The model file is stored in the app’s cache directory; copyAssetToCache handles idempotent copying from the APK assets.

Model pre/post‑processing (typical for DocAligner‑like detectors):
- Preprocess: resize input to model’s expected size (e.g., 256px), normalize pixel values, channel ordering.
- Inference: run session; obtain heatmaps or corner coordinates.
- Postprocess: threshold/smooth predictions, refine to precise corners using subpixel methods or local maxima; merge with classical contours.

### 8.3 PdfCreator

Responsibilities:
- Build a searchable PDF document combining the page image and OCR text layer.
- Handle image preparation (via OpenCVUtils processing pipeline), including DPI scaling and color conversions.
- Avoid PDF viewer drift by applying identical transformation matrices to image and text.
- Embed fonts and handle non‑ASCII text by normalizing and applying fallbacks.

Coordinate math deep dive:
- Let (w0, h0) be original bitmap size, (w1, h1) processed bitmap size (after DPI/filters).
- Let page size be (Wp, Hp). Compute scale s = min(Wp / w1, Hp / h1). Place image at (ox, oy) = ((Wp − s*w1)/2, (Hp − s*h1)/2).
- Text layer: transform each OCR box from original to processed space if needed: (x1, y1) = (x0 * w1/w0, y0 * h1/h0). Then apply page transform via the same CTM used for the image: [ [s,0,ox],[0,s,oy],[0,0,1] ].
- This ensures a viewer selecting text highlights precisely over the glyph shapes in the raster image.

Fonts and text shaping:
- Load PDType0Font or fall back to PDType1Font; normalize characters (NFC) to reduce glyph fallback misses.
- Clamp sizes to MIN_FONT_PT; for tiny boxes, consider skipping rendering if illegible (tradeoff between searchability and PDF size/performance).

### 8.4 ExportPagesAdapter

Responsibilities:
- Manage a RecyclerView of scanned pages for export.
- Provide remove, click, reorder callbacks (onRemoveClicked, onPageClicked, onReorder), and inline OCR trigger (onOcrRequested).
- Show thumbnails efficiently with a small LruCache.

Implementation highlights:
- decodeSampled computes an inSampleSize based on requested width/height to downsample decodes and reduce memory footprint.
- RGB_565 is used for tiny thumbnails to save memory with acceptable quality tradeoffs.
- ViewHolder pattern binds title/date, image, and action buttons; callbacks interface decouples UI from business logic.


## 9. Image Processing Pipeline

The image processing pipeline is designed to be modular, debuggable, and to optimize for OCR legibility and PDF quality:

1. Input Acquisition: Capture raw image via camera or import from gallery.
2. Preprocessing: Apply color conversion, smoothing, and adaptive thresholding if enabled; adjust contrast/sharpen as needed.
3. Edge Detection: Use Canny/Hough or contour analysis in OpenCV and apply ONNX model inference to suggest likely document corners, combining results heuristically (ONNX required).
4. Corner Refinement: Enforce constraints (convexity, area thresholds, aspect ratio sanity checks) and sort to a consistent corner order (top‑left, top‑right, bottom‑right, bottom‑left).
5. Perspective Transform: Compute homography and warp to a rectified top‑down view.
6. Enhancement for Export: Convert to grayscale or black‑and‑white for compact PDFs; resample to target DPI.
7. OCR Preparation: Ensure final image is clean, with good contrast and minimal noise to improve Tesseract accuracy.

Edge cases:
- Curled pages: corner detection may favor inner corners; manual adjustment recommended.
- Strong shadows: adaptive threshold and morphological ops help; ML assists locating true edges.
- Textured backgrounds: increase contour filtering thresholds and rely more on ML predictions.


## 10. OCR Details

- Engine: Tesseract via tess‑two JNI bindings.
- Language Packs: English (eng), German (deu), French (fra), Italian (ita), Spanish (spa) supported; select at OCR step.
- Output: a list of RecognizedWord; downstream code expects bounding boxes in bitmap pixel space.

Integration with PDF:
- Convert coordinates to processed bitmap’s space, clip to bounds, then draw text with matching CTM.

Error handling:
- If OCR fails or returns empty results, PDF generation proceeds with image‑only output.


## 11. PDF Generation Internals

PdfCreator carefully avoids misalignment by:
- Measuring the page image placement and storing the scale and offsets needed to center inside the page.
- Applying the same CTM for the text layer as for the image.
- Transforming OCR boxes from original capture space to final placement space, including DPI and scaling adjustments.
- Clipping text to the image bounds when necessary to avoid draw calls outside page area.

Memory:
- Recycle prepared bitmaps after writing.
- Stream save via OutputStream from ContentResolver to avoid large RAM spikes.


## 12. Performance Considerations

- Camera capture executes on CameraX threads; UI updates are posted back to the main thread.
- Image decoding for thumbnails uses inSampleSize and RGB_565 to reduce memory.
- ONNX Runtime session is initialized once and reused.
- OpenCV native functions are leveraged for performance‑critical operations.
- PDF creation streams output to reduce memory spikes; bitmaps are recycled when no longer needed.
- Threading and throttling for ONNX inference reduce thermal load.

Profiling tips:
- Use Android Studio profiler to inspect CPU usage during ONNX inference.
- Track allocations in image processing steps; avoid creating temporary Mats excessively.


## 13. Error Handling and Resilience

- Camera initialization and capture are wrapped in try/catch with user feedback (Toast/Dialog); the app attempts to recover or degrade gracefully.
- OpenCV and ONNX initialization log errors but do not crash the app; the app can run in a purely classical CV mode if the ONNX model fails to load.
- PdfCreator handles failures by logging and returning null, allowing the caller to surface actionable errors.
- Thumbnail decoding tolerates bad files and returns null instead of throwing.

Logging strategy:
- TAG‑prefixed logs (e.g., "OpenCVUtils", "PdfCreator"); errors include context such as file paths count (not full paths) and operation stage.
- Avoid logging PII or exact file names unless in debug builds.


## 14. Internationalization (i18n) and Localization (l10n)

- The OCR flow supports multiple languages — users choose the OCR language to apply.
- UI strings are structured to be localizable in the resources.
- Fonts and Unicode normalization in PdfCreator help handle diverse scripts more reliably.

Text rendering caveats:
- Complex scripts may require additional font support; fallbacks mitigate but do not fully solve shaping for all languages.


## 15. Accessibility (a11y)

- Material Components provide baseline accessibility for widgets and controls.
- UI aims for sufficient contrast and touch target sizes.
- Screen reader labels should be present for important controls in camera and export screens.

Possible improvements:
- Provide haptic feedback on capture and actionable prompts.
- Ensure drag handles in crop UI are accessible via alternative input methods.


## 16. Build, Packaging, and CI/CD

- Build System: Gradle, with Android plugin and Java/Kotlin where applicable.
- JDK 17, NDK 28.0.13004108, CMake 3.31.6 pinned in CI for deterministic native builds.
- Submodules: external/opencv and external/onnxruntime are checked out and built from source.
- Scripts: scripts/build_opencv_android.sh, scripts/build_onnxruntime_android.sh, and scripts/prepare_opencv.sh are used to produce and integrate native artifacts.
- GitHub Actions Workflow: .github/workflows/build-release.yml builds on push/PR and on tag releases; produces APKs/AABs, signs (if secrets provided), verifies signatures, generates checksums, loads release notes, and publishes a GitHub Release.
- F‑Droid: No prebuilt binaries in the repository; everything is reproducible from source as part of CI or a local build.

Local build checklist:
- Install Android SDK and NDK matching CI versions; set ANDROID_NDK_HOME if needed.
- Initialize submodules; run native build scripts or let Gradle tasks invoke them.
- Verify jniLibs and jars are placed under app/src/main/jniLibs/<ABI> and app/libs/ as expected.


## 17. Storage and Data Management

- Input images are saved to app‑accessible storage paths, with URIs tracked via ViewModels and navigation arguments.
- Export targets use SAF content URIs; PdfCreator writes directly to the provided OutputStream.
- Temporary files (e.g., ONNX model copy) are written to the app cache directory.
- Thumbnails are stored only in memory (LruCache) and not persisted.

Retention policy:
- Temporary files may be cleared by the OS; the app recreates ONNX cache as needed.
- User exports persist wherever the user chooses to save/share; the app does not phone home.


## 18. App Navigation and State Management

- Single activity hosts a NavHostFragment.
- Fragments share state via ViewModels (e.g., CropViewModel for passing captured image and corner data).
- Back press handling in CameraFragment: intercepts back navigation to ensure a clean teardown or to keep user from losing progress.

State patterns:
- Use LiveData/StateFlows (where applicable) to propagate processing results.
- Keep large Bitmaps out of savedInstanceState; prefer file URIs and recreate when needed.


## 19. UI Components and Layouts

- Camera screen:
  - PreviewView, capture button, flash toggle, sensor‑based hints.
- Crop screen:
  - Image view with draggable corners/edges, reset/apply actions.
- OCR screen:
  - Language selection, OCR progress, text/box review.
- Export session screen:
  - RecyclerView with thumbnails, reorder handles, select/remove, inline OCR, export options.

Res/layout examples:
- fragment_completed_scans_picker.xml defines the layout for selecting from recent scans for reuse.
- Drawable icons include flash off, image, select/deselect all, used across UI actions.

Design system:
- Material 3 theme with day/night support; consistent paddings, typography, elevation.


## 20. Quality, Testing, and Debugging

- Unit and instrumentation tests can be added around utilities and ViewModels.
- Debug logs in OpenCVUtils and PdfCreator are prefixed and controlled; USE_DEBUG_IMAGES can be toggled for deeper introspection when developing.
- For ONNX and OpenCV builds, reproducibility evidence is collected in CI scripts to help debugging native build mismatches.

Suggested tests:
- OpenCVUtils: corner detection on sample fixtures; verify homography warp round‑trips.
- PdfCreator: golden tests comparing PDF text layer coordinates against expected boxes.
- ExportPagesAdapter: item moves, deletes, inline OCR callback smoke tests.


## 21. Extensibility and Future Work

Potential areas for extension:
- Add more OCR languages and trained data packs.
- Implement editable OCR layer export (e.g., embedding text annotations or sidecar formats).
- Enhance ML corner detection model options and quantized variants for performance.
- Introduce additional image filters (noise reduction specialized for text, background whitening).
- Batch processing pipeline for multi‑document workflows.

Public APIs for extension are internal to the app modules, but contributing developers can adhere to the following guidelines:
- Keep image operations in OpenCVUtils to centralize native and ONNX interactions.
- Keep document assembly inside PdfCreator to preserve alignment guarantees.
- Extend adapters and fragments via well‑scoped callbacks and ViewModels.

Contribution guide (technical):
- Follow code style; add JavaDoc/KDoc for public methods.
- Include unit tests for utilities and regressions for bug fixes.
- Update this documentation if interfaces or behaviors change.


## 22. Troubleshooting

- Camera fails to start:
  - Check camera permissions and that no other app is using the camera.
  - Verify device has a compatible CameraX provider.
  - Try turning torch off/on; some devices have known quirks.

- ONNX model fails to load:
  - Ensure assets include the model at the expected path; logs will indicate copy and load steps.
  - The app requires ONNX Runtime and the model; failure to load will prevent the edge detection pipeline from operating as designed.

- PDFs are not searchable:
  - Confirm OCR is actually producing words; verify language selection and that the image has sufficient contrast.
  - Verify PdfCreator’s log output; ensure text layer is being added and not filtered out.

- Performance issues:
  - Lower JPEG quality or switch to grayscale/black‑and‑white for smaller images.
  - Ensure ONNX threads are not starving UI; logs will show thread configuration.
  - Avoid running OCR and export simultaneously on very low‑end devices.


## 23. API and Class Reference (Selected)

- CameraFragment
  - onCreateView, onResume, onPause, initializeCamera, captureImage, toggleFlashlight, resetCamera, initLightSensor, showLowLightPrompt, onSensorChanged
  - Uses: ProcessCameraProvider, PreviewView, ImageCapture, SensorManager, ViewModelProvider

- OpenCVUtils
  - init(Context), initOnnxRuntime(Context)
  - copyAssetToCache(Context, String)
  - Corner detection, perspective transform, color conversions (see source for detailed methods)
  - Fields: MODEL_ASSET_PATH, USE_SAFE_MODE, USE_ADAPTIVE_THRESHOLD

- PdfCreator
  - createSearchablePdf(...) overloads with grayscale/BW and target DPI
  - addTextLayerImageSpace(...) internal utility to draw OCR text in image coordinate space
  - loadFontsWithFallbacks(...), calculateScale(...), processImageForPdf(...)

- ExportPagesAdapter
  - submitList(List<CompletedScan>), onBindViewHolder(PageVH,int)
  - Callbacks: onRemoveClicked, onPageClicked, onReorder, onOcrRequested
  - Thumbnail cache: LruCache with decodeSampled helper


## 24. Security Review Checklist

- No network requests in the core app flow; all operations are local.
- Uses FileProvider for secure sharing of files outside the app sandbox.
- PDF content is passive (images + text), no scripts or embedded executables.
- ONNX model loaded from assets; no model download at runtime.

Supply chain steps:
- Submodules pinned; CI builds from source; artifacts checked in only to app outputs.
- APK signing keys documented in README; apksigner verification commands provided.


## 25. Licensing and Attribution

- App code: Apache 2.0.
- OpenCV: Apache 2.0.
- ONNX Runtime: MIT.
- DocAligner model: Apache 2.0.
- tess-two: Apache 2.0.
- pdfbox-android: Apache 2.0.

See NOTICE for third‑party attributions.


## 26. Operational Runbook

- Building locally:
  - Ensure JDK 17 and Android SDK/NDK aligned with CI versions.
  - Initialize submodules: git submodule update --init --recursive.
  - Run Gradle build; scripts will build native components as needed or follow CI steps.

- Releasing:
  - Create a vX.Y.Z tag and push.
  - CI will produce ABI‑split APKs and an AAB, sign (if secrets configured), verify, checksum, and publish a GitHub Release.

- Verifying APK signature:
  - Use apksigner verify --print-certs and check against published SHA‑256 in README.md.

Incident response:
- If a regression ships, roll back release tag; CI artifacts of prior versions remain accessible.
- Use issue templates to capture device model, Android version, steps, and logs.


## 27. Design Rationale and Tradeoffs

- Offline‑first: Chosen to respect user privacy and make the app usable without network connectivity; implies larger on‑device resource footprint (OCR and ONNX on-device).
- ML‑assisted corner detection via ONNX Runtime: The ONNX model and runtime are required components of the edge detection pipeline.
- pdfbox-android for PDF: Provides control over page boxes, fonts, and direct drawing of text/image layers compared to Android PdfDocument alone.
- CameraX: Preferred for broader device compatibility and simpler lifecycle management than raw Camera2.

Alternatives considered:
- Using only Android PdfDocument: less control over fonts and text transform precision.
- Relying on cloud OCR: conflicts with privacy requirements and offline goals.


## 28. Known Limitations

- OCR accuracy depends on image quality; skew, shadows, and low‑contrast text require manual crop/filters.
- ONNX inference adds startup cost on first use; session creation is amortized but still requires resources.
- Very large multi‑page PDFs can be memory‑intensive during generation; PdfCreator attempts to stream and recycle bitmaps.
- Complex scripts and vertical text may not render perfectly with current font stack.


## 29. Future Enhancements (Detailed)

- Editable OCR: Provide a review UI to correct text and re‑embed an adjusted text layer.
- Language packs: Expand supported languages and allow optional downloadable packs (still respecting offline usage where possible).
- Advanced filters: Background equalization, shadow removal, binarization tuned for receipts vs. books.
- Batch mode: Scan multiple documents in one session with auto‑advance capture.
- Real‑time ML: Live edge overlay during camera preview when performance allows.
- PDF/A options: Provide archival conformance modes (embedding fonts, color profiles).

Roadmap considerations:
- Maintain F‑Droid compliance when introducing optional downloads; keep core offline.
- Preserve alignment guarantees for any new PDF features.


## 30. Glossary

- CTM: Current Transformation Matrix used in PDF to map user space coordinates to page coordinates.
- DPI: Dots per inch; affects scaling and perceived resolution in printed or displayed PDFs.
- Homography: A projective transform mapping a quadrilateral in one plane to another; used for perspective correction.
- LruCache: Least‑recently‑used cache, used here for thumbnails.
- SAF: Storage Access Framework; Android’s API for content URIs and document providers.


## 31. References

- Android CameraX documentation
- OpenCV documentation for Android
- ONNX Runtime for mobile
- tess-two (Tesseract on Android)
- pdfbox-android library
- DocAligner model description
- Android FileProvider documentation


## 32. Threading Model and Concurrency

- UI Thread (Main):
  - Fragment lifecycle methods, view updates, user interactions.
  - Avoid blocking operations; always offload OCR, image processing, and file I/O.
- CameraX Threads:
  - Handle image acquisition and encoding; ImageCapture writes to disk asynchronously.
- Background Executors:
  - OpenCV processing, ONNX inference, OCR execution, PDF generation are dispatched to background pools.
- Synchronization:
  - Minimize locking; prefer immutable data passing.
  - ONNX OrtSession is thread‑safe for inference in many contexts, but we serialize calls by design to limit thermal load.

Failure isolation:
- If one page fails OCR, it doesn’t compromise the export session; results are per page.
- PDF generation is per document; partial exports can be retried.


## 33. Memory Management Strategy

- Bitmap lifecycle:
  - Reuse or promptly recycle intermediate bitmaps after use.
  - Thumbnails use RGB_565 to conserve memory; full pages use ARGB_8888 as needed.
- OpenCV Mats:
  - Release native memory by scoping Mats tightly; avoid long‑lived native buffers.
- Caches:
  - LruCache for thumbnails only; avoid caching full page bitmaps.
- PDF streaming:
  - Write incrementally to OutputStream to reduce peak RAM use.

Low‑memory behavior:
- Android may reclaim app memory; ensure critical operations can resume or fail gracefully with clear messaging.


## 34. JNI and Native Interactions

- OpenCV Java wrappers call into native libs (opencv_java4); ensure ABI artifacts exist under app/src/main/jniLibs/<ABI>.
- ONNX Runtime JNI (libonnxruntime4j_jni.so) and native runtime (libonnxruntime.so) must be present for supported ABIs.
- Avoid custom JNI layers in app code to reduce complexity; rely on stable wrappers.


## 35. Building ONNX Runtime and OpenCV (Detailed)

- OpenCV Build:
  - scripts/build_opencv_android.sh pins toolchains; builds CPU‑only modules needed by Java wrappers.
  - Artifacts are copied into jniLibs and Java classes are included in source tree for F‑Droid compliance.
- ONNX Runtime Build:
  - scripts/build_onnxruntime_android.sh builds CPU‑only with Java bindings; jars placed in app/libs, SOs in jniLibs.
  - Ensure consistent NDK, CMake versions; CI uses pinned versions for determinism.

Reproducibility evidence:
- scripts/collect_repro_evidence.sh stores commit hashes, tool versions, and checksums as part of the build artifacts.


## 36. CI/CD Workflow Details

- Triggers:
  - push to main, PR to main: build unsigned artifacts for validation.
  - tags v*: full release flow including signing (if secrets provided).
- Steps:
  - Setup JDK, NDK, CMake; checkout with submodules.
  - Build OpenCV, integrate artifacts.
  - Build ONNX Runtime, integrate artifacts.
  - Gradle assemble (AAB + per‑ABI APKs).
  - If release: sign, apksigner verify, rename, checksum, publish GitHub Release.
- Artifacts:
  - Abi‑split APKs (arm64, armeabi‑v7a, x86_64, etc.), single AAB.
  - .sha256 files accompany each artifact for verification.


## 37. Coordinate Systems and Transform Math (Extended)

- Image Space:
  - Origin (0,0) at top‑left; x to the right, y downward; units in pixels.
- Page Space (PDF user units):
  - Origin (0,0) at bottom‑left; x to the right, y upward; default units are 1/72 inch.
- Transform chain:
  - OCR boxes (image) -> normalized to processed bitmap size -> scaled and translated to page via CTM -> PDF space.
- Example:
  - Bitmap 2400x3300 px (8"x11" at 300dpi). A4 page ~ 595x842 pt.
  - scale s = min(595/2400, 842/3300) ≈ min(0.2479, 0.2552) = 0.2479.
  - drawW = 2400*s ≈ 595; drawH = 3300*s ≈ 819; offsets (ox,oy) ≈ (0, (842−819)/2) ≈ (0, 11.5).
  - A word box at (x,y,w,h) = (600, 900, 200, 60) maps to page: ((600*s)+ox, ( (3300−(900+60))*s )+oy ) accounting for Y‑axis inversion when needed by PDF content stream if drawing text via text matrices. PdfCreator uses image‑space CTM to minimize such pitfalls.


## 38. Font Handling, Unicode, and Text Extraction

- Fonts:
  - Prefer PDType0Font (CID) for broad Unicode coverage; fallback to Type1 if needed.
- Unicode normalization:
  - Apply NFC to harmonize composed characters; improves font glyph mapping.
- Bidi and combining marks:
  - Complex scripts may require shaping; pdfbox‑android has limited shaping; MakeACopy focuses on exact overlay for search/select, not perfect visual glyph rendering (text is invisible).
- Text extraction:
  - Downstream PDF readers should find words where OCR boxes were placed; exact text extraction fidelity depends on reader heuristics.


## 39. Error Taxonomy

- Camera:
  - PermissionDenied, ProviderUnavailable, CaptureFailed, FileWriteError.
- OpenCV/ONNX:
  - NativeLibLoadFailure, ModelCopyFailure, SessionCreationFailure, InferenceError.
- OCR:
  - LanguagePackMissing, RecognitionFailed, EmptyResult.
- PDF:
  - FontLoadError, ImageEmbedError, StreamWriteError.

Each category is logged with context; UI surfaces user‑actionable guidance (retry, change settings, check permissions).


## 40. Device Compatibility and Quirks

- Flash/torch:
  - Some devices have delayed torch state changes; debounce UI updates.
- Sensor availability:
  - Not all devices expose a reliable light sensor; the app detects and disables low‑light prompts when absent.
- Camera orientation:
  - Ensure rotation logic uses Display/PreviewView rotation consistently.

Testing matrix suggestions:
- Low‑end ARMv7, mid‑range arm64, high‑end arm64.
- Android 8–14 where feasible; verify camera, sensors, and SAF flows.


## 41. Performance Benchmarks (Illustrative)

- Edge detection (classical) on 12MP image: ~50–150ms after downscale.
- ONNX inference (256x256 input): ~10–40ms on modern arm64 CPUs.
- OCR (A4 page at 300dpi): ~500–2000ms depending on language and content.
- PDF assembly: ~50–300ms per page plus I/O.

Numbers vary widely by device; these are ballparks for tuning.


## 42. Manual QA Checklists

- Capture flow:
  - Grant/deny camera permission paths; observe UI.
  - Low‑light prompt triggers in dim conditions and respects cooldown.
- Crop flow:
  - Auto corners reasonable on plain sheet; manual drag maintains convex quad.
- OCR flow:
  - Language selection persists; OCR returns plausible text on printed page.
- Export flow:
  - PDF exports open in third‑party readers; text selectable and searchable.
  - JPEG export honors quality and color settings.


## 43. Accessibility Testing Procedures

- Enable TalkBack; navigate camera controls.
- Focus crop handles via accessibility traversal; verify labels.
- Check color contrast in light/dark modes.


## 44. Privacy Threat Modeling (STRIDE Summary)

- Spoofing: N/A (no authentication or network endpoints).
- Tampering: Exports written via SAF; other apps cannot modify app‑private files without consent.
- Repudiation: Local operations; logs kept only on device; no server interactions.
- Information disclosure: No telemetry; user explicitly shares exports.
- Denial of service: Large images may slow processing; app mitigates via downscaling/target DPI and background threading.
- Elevation of privilege: App runs in standard sandbox with minimal permissions.


## 45. Future Technical Directions (Deep Dive)

- Quantized ONNX models:
  - Reduce runtime and size; ensure quality remains acceptable across diverse docs.
- On‑device ML training/fine‑tuning (long‑term):
  - Likely out of scope; could explore federated on‑device learning strictly offline for corner refinement heuristics.
- Modular export backends:
  - Add exporters (TIFF, PNG multipage) behind a clean interface; keep PdfCreator specialized.


## 46. Developer Environment Setup

- Prerequisites:
  - Android Studio (latest stable), JDK 17, NDK 28.0.13004108, CMake 3.31.6.
- Steps:
  - Clone repo; git submodule update --init --recursive.
  - Sync Gradle; run builds; if native builds fail, run scripts manually and verify environment variables.
- Troubleshooting:
  - Check ANDROID_HOME/ANDROID_NDK_HOME; ensure PATH contains cmake from SDK if required.


## 47. Coding Standards and Reviews

- Style:
  - Consistent naming, JavaDoc/KDoc for public APIs, meaningful logs.
- Reviews:
  - Require at least one reviewer for non‑trivial changes; include screenshots or PDFs for UI/export changes.
- Testing:
  - Include unit/regression tests when feasible; attach sample PDFs for PDF changes.


## 48. Legal and Compliance Notes

- Licenses documented in README and NOTICE; ensure any new third‑party code includes proper attribution and compatible license.
- Fonts used for PDF rendering must be redistributable under app’s license constraints or loaded from system when allowed.


## 49. Appendix: Sample Pseudocode Snippets

- Perspective correction (high‑level):
  - detect_corners(bitmap) -> quad
  - H = compute_homography(quad -> target_rect)
  - rectified = warp_perspective(bitmap, H, target_size)
  - return rectified

- OCR to PDF mapping:
  - words = ocr(rectified)
  - (s, ox, oy) = compute_page_transform(rectified.size, page.size)
  - begin_text_layer()
  - for w in words:
    - r = transform_bbox(w.bbox, rectified.size, s, ox, oy)
    - draw_invisible_text(w.text, r)
  - end_text_layer()


## 50. Appendix: Example Export Configuration Matrix

- Color: color, grayscale, black‑and‑white
- DPI: 150, 200, 300, 600
- JPEG quality: 60, 75, 85, 95
- OCR: on/off, language selection

Testing each combination on 2–3 representative devices ensures coverage of size, quality, and performance tradeoffs.


## 51. Appendix: File and URI Handling

- Input sources:
  - Camera: file paths in app storage; FileProvider URIs when needed.
  - Gallery/import: SAF URIs; copy to temp if direct decode needed.
- Output targets:
  - SAF create document URIs; write via ContentResolver openOutputStream.
- Cleanup:
  - Delete temp files; avoid leaks when activity/fragment is destroyed mid‑operation.


## 52. Appendix: Logging Reference

- OpenCVUtils:
  - init/start/complete, model copy, session creation, inference timing.
- PdfCreator:
  - image prep, page scale/offset, font load fallback events, save success/failure.
- CameraFragment:
  - permission, provider binding, capture success/failure, torch state changes.


## 53. Appendix: Sample Issues and Fix Patterns

- Misaligned text layer in certain viewers:
  - Verify identical CTM; ensure no Y‑flip mismatch; harmonize page boxes.
- ONNX crash on specific ABI:
  - Rebuild with correct NDK; check jniLibs ABI split; ensure no mixed STL/runtime.
- OCR returns no text:
  - Try grayscale vs BW; verify language pack presence; increase DPI to 300.


## 54. Revision History

- 2025‑09‑26: Initial comprehensive version covering architecture, workflows, and operations.


## 55. Appendix: OCR Tuning Guide

- Page preconditioning:
  - Deskew if necessary by estimating dominant text line orientation.
  - Remove background gradients with morphological opening or rolling ball algorithms.
- Threshold selection:
  - Test Otsu and Sauvola; choose per‑page based on histogram properties.
- Language hints:
  - Selecting the correct language pack significantly improves recognition; default to device locale when reasonable.
- Post‑processing:
  - Normalize whitespace; strip low‑confidence artifacts if they fall below a threshold.


## 56. Appendix: OpenCV Parameter Heuristics

- Canny thresholds: start with (50, 150) on normalized images; adjust based on contrast.
- Contour filtering:
  - Area threshold: >= 10% of image area for A4‑sized document in frame.
  - Aspect ratio: between 0.6 and 1.5 typically captures common paper sizes depending on camera framing.
  - Convexity: enforce convex hull similarity.
- Perspective warp:
  - Interpolation: Lanczos for downscale; bicubic acceptable compromise.
  - Border handling: constant border with white background to avoid dark edges.


## 57. Appendix: PDF Internals and Best Practices

- Page boxes:
  - Keep MediaBox == CropBox == TrimBox to avoid reader‑specific clipping.
- Compression:
  - Use JPEG for photographic pages; keep quality around 75–85 for balance.
  - For line art or text‑only scans in BW, consider lossless to preserve crisp edges.
- Metadata:
  - Set Creator/Producer to MakeACopy; consider including creation date and language codes for OCR.


## 58. Appendix: CI Environment Variables (Example)

- ANDROID_SDK_ROOT: path to Android SDK.
- ANDROID_NDK_VERSION: 28.0.13004108.
- CMAKE_VERSION: 3.31.6.
- JAVA_HOME: Temurin 17 path.
- GRADLE_OPTS: memory tuning (e.g., -Xmx4g -Dorg.gradle.jvmargs="-Xmx4g").


## 59. Appendix: Sample Device Lab Plan

- Target devices:
  - Low‑end (2GB RAM), mid‑range (4–6GB), high‑end (8GB+).
- Screen sizes:
  - Compact phones, large phones, tablets.
- Lighting conditions:
  - Bright office, dim room, mixed daylight.
- Documents:
  - High‑contrast print, faded copy, glossy brochure with glare, crumpled receipt.


## 60. Appendix: Mathematical Notes on Homography

- Homography H maps points p in input to p' in output: p' ~ H p, using homogeneous coordinates.
- Estimated via DLT from 4 correspondences; refined with RANSAC if more points available.
- Conditioning:
  - Normalize coordinates to mean 0 and average distance sqrt(2) before estimation; denormalize after.
- Numerical stability:
  - Use double precision in intermediate calculations; OpenCV handles internally.


## 61. Appendix: Failure Injection Scenarios

- Simulate model copy failure by denying cache write permissions; verify graceful fallback.
- Force OCR timeout to test cancellation and UI recovery.
- Corrupt image input to test decodeSampled null handling in ExportPagesAdapter.


## 62. Appendix: Content URI vs File URI

- Prefer SAF content URIs for exports; improves compatibility with scoped storage.
- Use FileProvider for backward‑compatible sharing of app‑private files.
- Avoid exposing raw file paths to other apps.


## 63. Appendix: Battery and Thermal Considerations

- Long OCR or multi‑page exports can heat devices; throttle background threads, allow pause/resume.
- Respect system battery saver modes where applicable; defer heavy tasks if user opts in.


## 64. Appendix: Telemetry Philosophy

- None by default. If telemetry is ever introduced as opt‑in, it must:
  - Be disabled by default.
  - Prompt clearly for consent, explaining data categories.
  - Be fully functional without it.
  - Provide an easy way to purge data and revoke consent.


## 65. Appendix: Security Hardening Ideas

- Integrity checks for assets (e.g., ONNX model) with embedded checksums.
- Verify PDF outputs are free of active content even if libraries evolve.
- Sandbox heavy processing in isolated processes on newer Android versions (optional).


## 66. Appendix: Export File Naming Conventions

- Default: MakeACopy-YYYYMMDD-HHMM-<sequence>.pdf
- Include user‑chosen title when provided; sanitize to avoid filesystem issues.


## 67. Appendix: Localization Checklist

- Extract all user‑facing strings to resources.
- Provide pluralization where counts are shown.
- Support RTL layout mirroring; verify crop UI handles RTL without confusion.


## 68. Appendix: A/B of Filters for OCR Accuracy

- Method:
  - Prepare a corpus of 50 diverse scans; compare OCR WER for grayscale vs BW vs sharpened variants.
- Expected:
  - BW often wins on high‑contrast print; grayscale on complex backgrounds; sharpening helps slightly on mid‑contrast text.


## 69. Appendix: Known Third‑Party Reader Behaviors

- Some mobile PDF viewers cache rendered pages; minor text layer changes may not be visible until you close/reopen.
- Certain viewers have issues with extremely small font sizes; clamping avoids invisible selections.


## 70. Appendix: Export Error Messages (User‑Facing)

- "Couldn’t create PDF" — suggests checking storage permissions and retrying.
- "OCR returned no text" — suggests adjusting filters or language.
- "Image too large to process" — suggests reducing DPI or splitting pages.


---

This document is intended to give developers and contributors a deep understanding of MakeACopy’s architecture, workflows, and design decisions. For implementation specifics, refer to the source code files mentioned throughout (e.g., CameraFragment.java, OpenCVUtils.java, PdfCreator.java, ExportPagesAdapter.java), which include additional inline documentation.
