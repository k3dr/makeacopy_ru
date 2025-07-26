# MakeACopy

MakeACopy is an open-source document scanner app for Android that allows you to digitize paper documents with OCR functionality. The app is designed to be privacy-friendly, working completely offline without any cloud connection or tracking.

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
alt="Get it on Google Play"
height="80">](https://play.google.com/store/apps/details?id=de.schliweb.makeacopy)

Or download the latest APK from the [Releases Section](https://github.com/egdels/makeacopy/releases/latest).

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **Camera Scanning**: Capture documents using the device camera
- **Edge Detection**: Automatic document edge detection using OpenCV
- **Perspective Correction**: Adjust and crop documents with manual or automatic perspective correction
- **Image Enhancement**: Apply filters (grayscale, contrast, sharpening)
- **OCR**: Offline text recognition with Tesseract
- **PDF Export**: Save as searchable PDF with recognized text
- **Share & Save**: Export locally or share with other apps
- **Dark Mode**: Material 3 theme with day/night support
- **Privacy-Focused**: 100% offline functionality, no internet connection required

## Screenshots

*Screenshots will be added soon*

## Installation

### F-Droid

MakeACopy is F-Droid compliant and will be available on F-Droid soon. The app uses a two-part approach for OpenCV integration:

1. **Java Classes**: The OpenCV Java wrapper is integrated via a Git submodule at `external/opencv`, containing only Java code and cleaned of all binaries.
2. **Native Libraries**: All native libraries are built from source during the build process using the official OpenCV source code from https://github.com/opencv/opencv.git.

This approach ensures F-Droid compatibility by not including any pre-compiled binaries in the repository and building all native components from source.

### GitHub Releases

You can download the latest APK from the [Releases](https://github.com/egdels/makeacopy/releases) page.

#### Automated Builds

The project includes two GitHub workflows for automated builds:

##### Release Builds

This workflow automatically builds APK and AAB files when a new tag is created:

1. Create a new tag starting with 'v' (e.g., `v1.0.0`)
2. Push the tag to GitHub
3. The workflow will automatically:
   - Set up JDK 21 and Android NDK 29.0.13599879
   - Initialize OpenCV source (via Git submodule)
   - Build OpenCV native libraries for all architectures
   - Integrate the built native libraries into the app
   - Build Debug APK, Release APK (unsigned), and Android App Bundle (AAB)
4. Artifacts will be attached to the GitHub Release

This ensures that release builds are 100% F-Droid compatible, with all native libraries built from source while using the pre-cleaned OpenCV submodule for Java classes.

##### OpenCV Integration Builds

This workflow builds the app on every push to main and pull request:

1. Sets up JDK 21 and Android NDK 29.0.13599879
2. Uses OpenCV submodule source
3. Builds OpenCV native libraries for all architectures
4. Integrates the built native libraries into the app
5. Builds the app with the integrated OpenCV components
6. Uploads the built APK as an artifact

This ensures that the OpenCV integration is tested with each code change, building all native libraries from source.

### Building from Source

1. Clone the repository with submodules:
   ```
   git clone --recurse-submodules https://github.com/egdels/makeacopy.git
   ```

2. The build process will automatically:
   - Build OpenCV native libraries for all architectures
   - Integrate the built native libraries into the app

   This is handled by Gradle tasks that run the following scripts:
   - `scripts/build_opencv_android.sh`: Builds OpenCV native libraries
   - `scripts/prepare_opencv.sh`: Integrates the built native libraries into the app

3. Build the app using Gradle:
   ```
   ./gradlew assembleDebug
   ```

4. Install the generated APK on your device.

> **F-Droid Compatibility Note**: This project is 100% F-Droid compatible. It uses a two-part approach for OpenCV integration:
> 1. Java classes provided via Git submodule, cleaned of binaries
> 2. Native libraries built from source during the build process
>
> This ensures no pre-compiled binaries are included in the repository or final APK.

#### Setting up the Android NDK

1. **Install the NDK using Android Studio**:
   - Open Android Studio > Settings/Preferences > Appearance & Behavior > System Settings > Android SDK
   - Select the 'SDK Tools' tab
   - Check 'NDK (Side by side)' and click 'Apply'

2. **Set the ANDROID_NDK_HOME environment variable** (optional):
   - The build script will attempt to locate the NDK automatically
   - If automatic detection fails, you can set it manually:
     ```bash
     export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/29.0.13599879
     ```

#### Maintaining the OpenCV Integration

The OpenCV integration uses a Git submodule and native build process.

##### Updating the Java Classes (OpenCV Submodule)

1. Update the OpenCV submodule:
   ```bash
   git submodule update --remote --checkout external/opencv
   ```
2. Clean out native binaries (if any):
   ```bash
   ./scripts/clean_opencv_binaries.sh
   ```
3. Commit the updated and cleaned OpenCV submodule:
   ```bash
   git add external/opencv
   git commit -m "Update OpenCV submodule to version X.Y.Z (cleaned)"
   ```

##### Updating the Native Libraries Build Process

1. Update the OpenCV version in `.gitmodules` or in the `scripts/build_opencv_android.sh` logic
2. Run the build process to verify:
   ```bash
   ./gradlew clean assembleDebug
   ```
3. Update version references if needed (e.g., in Gradle files or docs)

## Usage

1. **Scan Document**: Open the app and tap the scan button
2. **Adjust Corners**: Fine-tune document edge detection
3. **Crop & Enhance**: Crop and apply enhancements
4. **OCR Processing**: Recognize text
5. **Export & Share**: Save as PDF or share

## Architecture

MakeACopy follows the Single-Activity + Multi-Fragment pattern with MVVM architecture.

- **Camera Fragment**: Capture via CameraX/Camera2
- **Crop Fragment**: Perspective correction
- **OCR Fragment**: Tesseract-based recognition
- **Export Fragment**: PDF/text export

## Libraries Used

| Purpose | Library | License |
|--------|---------|---------|
| Image Processing | OpenCV for Android | Apache 2.0 |
| OCR | tess-two (Tesseract JNI) | Apache 2.0 |
| PDF | Android PdfDocument | Apache 2.0 |
| UI | Material Components | Apache 2.0 |

## Privacy

MakeACopy respects your privacy:

- Works 100% offline
- No tracking, telemetry, or analytics
- No cloud upload
- Requires only camera and storage permissions

See our [Privacy Policy](https://egdels.github.io/makeacopy/privacy) for details.

## Contributing

Contributions welcome!

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Future Enhancements

- OCR for multiple languages
- Multi-page scanning
- Editable OCR text export
- Integration with cloud storage
- ML-based scan enhancements

## License

```
Copyright 2025 Christian Kierdorf

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

