# MakeACopy — Technical Documentation

Last updated: 2025-08-17

This document provides a technical overview of the MakeACopy Android application, covering architecture, major modules, important classes, build and packaging specifics, and the testing strategy.

## 1. Architecture Overview

- Platform: Android (minSdk 29, targetSdk 36), Java 17, Gradle Android Application plugin.
- Pattern: MVVM with Fragments + ViewModels; utilities and services for cross‑cutting concerns.
- Key layers:
  - UI: Fragments (Camera, Crop, OCR, Export) hosted by MainActivity.
  - ViewModels: UI state holders and event sources (e.g., CameraViewModel, CropViewModel, OCRViewModel, ExportViewModel).
  - Services: Background and periodic tasks (CacheCleanupService).
  - Utils: Image processing (OpenCV), OCR (Tesseract), PDF generation (PdfBox-Android), file helpers, coordinate transforms, and UI helpers.
  - Application: App-wide initialization (OpenCV, cache cleanup configuration).

Navigation between screens is handled via AndroidX Navigation; ViewBinding is enabled for layout inflation and type-safe access.

## 2. Application and Activity

### 2.1 MakeACopyApplication
File: `app/src/main/java/de/schliweb/makeacopy/MakeACopyApplication.java`
- Initializes OpenCV via `OpenCVUtils.init(Context)` and logs status.
- Starts and configures `CacheCleanupService` with defaults (cleanup interval, debug/temp limits, memory threshold).
- Responds to system memory pressure (`onLowMemory`, `onTrimMemory`) by triggering immediate cache cleanup and GC.

### 2.2 MainActivity
File: `app/src/main/java/de/schliweb/makeacopy/MainActivity.java`
- Entry point activity; enables edge-to-edge UI via `WindowCompat.setDecorFitsSystemWindows(...)`.
- Inflates layout using ViewBinding (`ActivityMainBinding`).
- Forwards memory pressure events to `CacheCleanupService` to keep cache usage low.

### 2.3 AndroidManifest
File: `app/src/main/AndroidManifest.xml`
- Declares camera permissions and required/optional features (camera flash optional).
- Declares `FileProvider` for secure file sharing with authority `${applicationId}.fileprovider` and paths from `@xml/provider_paths`.
- Registers `CacheCleanupService`.

## 3. UI Layer (Fragments)

### 3.1 CameraFragment
File: `ui/camera/CameraFragment.java`
- Manages runtime camera permission, CameraX setup (Preview, ImageCapture), and capturing images.
- Handles device orientation, previews, and image saving to files via `FileProvider`.
- Coordinates with `CameraViewModel` and passes captured image URIs forward (e.g., to Crop).

### 3.2 CropFragment
File: `ui/crop/CropFragment.java`
- Displays the captured image, allows perspective correction and cropping.
- Uses `TrapezoidSelectionView` for interactive polygon selection.
- Leverages `OpenCVUtils` for edge detection and transformation; communicates with `CropViewModel`.

### 3.3 OCRFragment
File: `ui/ocr/OCRFragment.java`
- Performs OCR using Tesseract via `OCRHelper`.
- Manages language selection, OCR processing flow, and result preview.
- Exposes detailed state via `OCRViewModel` including recognized words (bounding boxes, confidences) and timers.

### 3.4 ExportFragment
File: `ui/export/ExportFragment.java`
- Exports final image and overlays searchable text into a PDF using `PdfCreator`.
- Supports naming, sharing via `FileProvider`, and saving to user-selected URIs.
- Consumes OCR results (`RecognizedWord`) to place a precise text layer.

## 4. ViewModels

### BaseViewModel
File: `ui/BaseViewModel.java`
- Provides common LiveData for fragment title text and an image URI holder.

### CameraViewModel
File: `ui/camera/CameraViewModel.java`
- Extends `BaseViewModel`; manages camera permission granted state.

### CropViewModel
File: `ui/crop/CropViewModel.java`
- Extends `BaseViewModel`; manages image bitmaps (original and working), load/cropped state.

### OCRViewModel
File: `ui/ocr/OCRViewModel.java`
- Encapsulates OCR UI state via immutable record `OcrUiState` and `OcrTransform`.
- Emits `Event<T>` wrappers for errors and navigation to export.
- Methods: start/finish processing, set language, set transform, set words, request navigation.

### ExportViewModel
File: `ui/export/ExportViewModel.java`
- Holds export options (file name, format, grayscale toggle, quality) and image/URI references.

## 5. Services

### CacheCleanupService
File: `services/CacheCleanupService.java`
- Long-running/background service handling cache directory hygiene and memory-aware cleanup.
- Exposes static configuration APIs: `startService`, `updateConfiguration`, `forceCleanup`.
- Periodically removes old temp files, limits debug artifacts, and responds to memory pressure (triggered by Activity/Application).

## 6. Utilities

### 6.1 OpenCVUtils
File: `utils/OpenCVUtils.java`
- Initializes OpenCV and provides high-level routines for:
  - Edge detection, contour processing, perspective correction.
  - ML support (ONNX Runtime integration points) for enhanced edge detection (DocAligner model).
  - Bitmap conversions, scaling, sharpening, grayscale.
- Heavily used by Crop and preprocessing before OCR.

### 6.2 ImageUtils
File: `utils/ImageUtils.java`
- Loads images from `Uri` via `ContentResolver` (sync/async), applies EXIF rotation.
- Executor-based async loading with main-thread callbacks.

### 6.3 ImageScaler
File: `utils/ImageScaler.java`
- Provides A4-at-300DPI scaling helpers to prepare images for high-quality OCR/PDF placement.

### 6.4 CoordinateTransformUtils
File: `utils/CoordinateTransformUtils.java`
- Converts points between view space and bitmap space using FIT_CENTER math (handles letterboxing/pillarboxing).

### 6.5 OCRHelper
File: `utils/OCRHelper.java`
- Wraps Tesseract (`TessBaseAPI`) for running OCR over bitmaps/crops.
- Manages language data, whitelisting via `OCRWhitelist`, and outputs `RecognizedWord` list (text, RectF, confidence).

### 6.6 OCRWhitelist
File: `utils/OCRWhitelist.java`
- Static whitelists for several languages and a method to merge via a language spec (e.g., `deu+eng`).

### 6.7 RecognizedWord
File: `utils/RecognizedWord.java`
- Immutable data holder for recognized text with bounding box and confidence; provides transform/clip helpers.

### 6.8 PdfCreator
File: `utils/PdfCreator.java`
- Uses PdfBox-Android to create a PDF page from a bitmap (optionally grayscale/JPEG quality) and overlays a searchable text layer.
- Maps OCR word bounding boxes to PDF coordinates and writes text with appropriate font, size, and baseline adjustments.
- Verified by instrumented tests to ensure correct proximity/grouping (e.g., words appear as a continuous line in extractors).

### 6.9 FileUtils
File: `utils/FileUtils.java`
- Human-readable display names for Uris by consulting `OpenableColumns` or path fallbacks.

### 6.10 UIUtils
File: `utils/UIUtils.java`
- Window inset-aware margin utilities and safe Toast helpers using application context.

### 6.11 TrapezoidSelectionView
File: `ui/crop/TrapezoidSelectionView.java`
- Custom View for interactive trapezoid/polygon selection; handles touch events and drawing; integrates with `OpenCVUtils`.

## 7. PDF Generation and Text Layer Placement

The PDF creation pipeline (`PdfCreator.createSearchablePdf`) performs:
1. Page setup (A4 points), image insertion (JPEG or lossless), optional grayscale.
2. OCR word mapping:
   - Compute scale/offset between source bitmap and PDF page (respecting chosen layout).
   - Derive font size from box height with alignment factors (`DEFAULT_TEXT_SIZE_RATIO`, `TEXT_POSITION_ADJUSTMENT`, `VERTICAL_ALIGNMENT_FACTOR`).
   - Convert Android image coordinates (origin top-left) to PDF coordinates (origin bottom-left).
3. Write text with appropriate spacing so extractors (PDFBox) read words properly in order.

Unit (`PdfCreatorTest`) and instrumented tests (`PdfCreatorInstrumentedTest`, `PdfCreatorIntegrationTest`) assert that:
- Font sizing and baseline math are consistent.
- Extracted text contains expected tokens and preserves adjacency ("Alpha Beta").
- Various page shapes/layouts still produce searchable text layers.

## 8. Build, Dependencies, and Packaging

- Gradle module: `app/` (see `app/build.gradle`).
  - compileSdk 36, targetSdk 36, Java 17, ViewBinding enabled.
  - Reproducible build settings and ABI splits (configurable via `enableAbiSplits`).
  - NDK 27.3.13750724 for native libs (OpenCV built from source).
  - BuildConfig includes `OPENCV_VERSION`.
- Dependencies:
  - AndroidX appcompat, material, constraintlayout, lifecycle, navigation.
  - CameraX (core, camera2, lifecycle, view).
  - Tesseract Android Tools.
  - PdfBox-Android.
  - Lombok (compileOnly + annotationProcessor).
  - ONNX Runtime (optional) via `libs/*.jar` as needed.
  - ONNX Runtime submodule: source provided via `external/onnxruntime` (MIT); built from source using `scripts/build_onnxruntime_android.sh` (CPU-only, Java bindings). Resulting native libs are placed in `app/src/main/jniLibs/<ABI>/` and the Java JAR in `app/libs/`. See NOTICE for attribution.
- OpenCV Integration:
  - Java wrapper classes are vendored into the source tree; native libs built via scripts (see repository `external/opencv`).

### 8.1 Repository Scripts (scripts/)

The project ships helper scripts to build and integrate native components and to keep the repository F-Droid compliant. Typical usage is from a developer shell or within CI.

- scripts/build_opencv_android.sh
  - Purpose: Build OpenCV native libraries from the `external/opencv` submodule into a clean, reproducible output.
  - Inputs/Env: ANDROID_NDK_HOME (auto-detected when possible), ANDROID_SDK_ROOT (optional), CPU cores auto-detected. Patches `cmake/OpenCVUtils.cmake` to stabilize `ocv_output_status()` for reproducible build info.
  - Outputs: Compiled `.so` libraries under `/tmp/opencv-build/lib/<ABI>/`. Source is copied to `/tmp/opencv-src/` for a clean build tree.
  - ABIs: arm64-v8a, armeabi-v7a, x86, x86_64.
  - Example:
    - `./scripts/build_opencv_android.sh`

- scripts/prepare_opencv.sh
  - Purpose: Copy the built OpenCV `.so` files from `/tmp/opencv-build` into the app’s `jniLibs` directories so Gradle packages them.
  - Inputs: Expects `/tmp/opencv-build/lib/<ABI>/*.so` from the previous step.
  - Outputs: `app/src/main/jniLibs/<ABI>/*.so` for all ABIs. Fails if any ABI is missing.
  - Example:
    - `./scripts/prepare_opencv.sh`

- scripts/build_onnxruntime_android.sh
  - Purpose: Build ONNX Runtime (CPU-only, Java) for Android across ABIs and integrate artifacts into the app.
  - Inputs/Env: ANDROID_SDK_ROOT/ANDROID_HOME (optional), ANDROID_NDK_HOME (auto-detected when possible), JDK 17/21. Uses Ninja if available.
  - Outputs:
    - `app/src/main/jniLibs/<ABI>/libonnxruntime.so`
    - `app/src/main/jniLibs/<ABI>/libonnxruntime4j_jni.so`
    - `app/libs/onnxruntime-1.22.1.jar` (copied once)
    - Intermediate build trees under `/tmp/onnxruntime-build/<ABI>/` and copy of sources at `/tmp/onnxruntime-src/`.
  - Example:
    - `./scripts/build_onnxruntime_android.sh`

- scripts/check_opencv_modules.sh
  - Purpose: Inspect the codebase to detect which OpenCV modules are referenced and suggest CMake flags to enable only those (reduce APK size).
  - Inputs: Optional path argument (defaults to current directory).
  - Outputs: Console report listing used modules and `-D BUILD_opencv_<module>=ON/OFF` suggestions.
  - Example:
    - `./scripts/check_opencv_modules.sh app/src/main/java`

- scripts/clean_opencv_binaries.sh
  - Purpose: Ensure F-Droid compliance by removing prebuilt binaries and native sources from the vendored OpenCV Java wrapper tree and recreating empty ABI directories.
  - Inputs: None.
  - Outputs: Cleans `opencv/...` native artifacts, recreates empty ABI directory structure (e.g., `opencv/src/main/jniLibs/<ABI>/.gitkeep`) and ensures `app/libs/.gitkeep` exists.
  - Example:
    - `./scripts/clean_opencv_binaries.sh`

## 9. Permissions and FileProvider

- Camera permission (`android.permission.CAMERA`).
- `FileProvider` declared with `@xml/provider_paths` for secure sharing of exported files/PDFs.

## 10. Testing Strategy

- Unit tests (`app/src/test/...`): logic-level tests, e.g., `PdfCreatorTest` validating math without Android runtime.
- Instrumented tests (`app/src/androidTest/...`): run on device/emulator to validate end-to-end behaviors:
  - Rendering PDFs to bitmaps, running through `PdfCreator`, and extracting text via PDFBox to verify searchability.

## 11. Privacy

- App is 100% offline; no tracking or cloud services.
- `dependenciesInfo` disabled in APK/AAB for privacy and reproducibility.

## 12. Notable Files and Paths

- Root docs (web pages): `docs/index.html`, `docs/index_de.html`, `docs/privacy*.html`.
- Technical documentation (this file): `docs/TECHNICAL_DOCUMENTATION.md`.
- README and license/notice at repository root.

## 13. Extensibility Notes

- New image filters or ML models can be added to `OpenCVUtils` with minimal changes to UI fragments.
- New export formats can be modeled similarly to `PdfCreator` and integrated into `ExportFragment`/`ExportViewModel`.
- Additional OCR languages supported by adding traineddata files and updating `OCRWhitelist` if needed.

## 14. Versioning

- `versionCode`/`versionName` in `app/build.gradle` (currently 10101 / 1.1.1).

## 15. GitHub Workflow

This project uses GitHub Actions to automate builds and releases. While the YAML files may not be present in this repository snapshot, the workflow described here reflects the intended CI/CD setup and is mirrored in the README.

- Workflows:
  - Release pipeline (on tags starting with `v*`)
  - Continuous Integration pipeline (on pushes to `main` and on pull requests)

- Triggers:
  - Release: `on: push: tags: [ 'v*' ]`
  - CI: `on: [ push, pull_request ]` (optionally restricted to `branches: [ main ]`)

- Common job steps:
  1. Checkout with submodules: uses `actions/checkout@v4` with `submodules: true`.
  2. JDK setup: `actions/setup-java@v4` with `java-version: '21'` (or '17'), `distribution: 'temurin'`, Gradle cache enabled.
  3. NDK setup: install Android NDK `27.3.13750724` (matches `app/build.gradle`).
  4. Build OpenCV native libs from source: `scripts/build_opencv_android.sh`.
  5. Integrate native libs into the app: `scripts/prepare_opencv.sh`.
  6. Gradle build: `./gradlew assembleDebug` for CI; `./gradlew assembleRelease bundleRelease` for Release.
  7. Artifacts: upload APKs/AAB via `actions/upload-artifact@v4`.

- Signing and secrets (Release job):
  - Conditional signing is supported in `app/build.gradle` when the following are provided as repository or org secrets and passed as env/Gradle properties:
    - `SIGNING_STORE_PASSWORD`
    - `SIGNING_KEY_ALIAS`
    - `SIGNING_KEY_PASSWORD`
  - The keystore (`keystore.jks`) is not stored in the repo; for GitHub releases you typically upload unsigned artifacts, or inject a keystore at runtime (e.g., from an encrypted secret) if desired. F-Droid will sign with their own keys.

- Caching (optional but recommended):
  - Gradle cache: enabled by `setup-java` cache or `actions/cache` targeting `~/.gradle/caches`.
  - OpenCV build cache: cache `/tmp/opencv-build` keyed by OS + NDK version + commit to speed up subsequent CI runs.

- Example minimal YAMLs (drop into `.github/workflows/`):
  - ci.yml
    name: CI
    on:
      push:
        branches: [ main ]
      pull_request:
        branches: [ main ]
    jobs:
      build:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v4
            with:
              submodules: true
          - uses: actions/setup-java@v4
            with:
              distribution: temurin
              java-version: '21'
              cache: gradle
          - name: Install Android NDK 27.3.13750724
            run: echo "ndkVersion=27.3.13750724" >> $GITHUB_ENV
          - name: Build OpenCV
            run: scripts/build_opencv_android.sh
          - name: Prepare OpenCV
            run: scripts/prepare_opencv.sh
          - name: Build debug APK
            run: ./gradlew --stacktrace assembleDebug
          - uses: actions/upload-artifact@v4
            with:
              name: app-debug-apk
              path: app/build/outputs/apk/debug/*.apk
  
  - release.yml
    name: Release
    on:
      push:
        tags: [ 'v*' ]
    permissions:
      contents: write
    jobs:
      release:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@v4
            with:
              submodules: true
          - uses: actions/setup-java@v4
            with:
              distribution: temurin
              java-version: '21'
              cache: gradle
          - name: Install Android NDK 27.3.13750724
            run: echo "ndkVersion=27.3.13750724" >> $GITHUB_ENV
          - name: Build OpenCV
            run: scripts/build_opencv_android.sh
          - name: Prepare OpenCV
            run: scripts/prepare_opencv.sh
          - name: Build release artifacts
            env:
              SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
              SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
              SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
            run: |
              ./gradlew --stacktrace assembleRelease bundleRelease assembleDebug
          - uses: actions/upload-artifact@v4
            with:
              name: release-apk
              path: app/build/outputs/apk/release/*.apk
          - uses: actions/upload-artifact@v4
            with:
              name: release-aab
              path: app/build/outputs/bundle/release/*.aab

Notes:
- The README under “Automated Builds” provides a human-friendly explanation of these workflows.
- The Release job typically publishes artifacts to the GitHub Release created for the tag; you can add a step to create a release and upload assets.

---

For an introduction, installation, and release automation overview, see the repository `README.md`.
