# MakeACopy

MakeACopy is an open-source document scanner app for Android that allows you to digitize paper documents with OCR functionality. The app is designed to be privacy-friendly, working completely offline without any cloud connection or tracking.

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

1. **Java Classes**: The OpenCV module in the repository has been pre-cleaned of all binaries and contains only the necessary Java classes.
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
   - Set up JDK 21 and Android NDK 28.2.13676358
   - Fetch OpenCV source code from GitHub
   - Build OpenCV native libraries for all architectures
   - Integrate the built native libraries into the app
   - Build Debug APK, Release APK (unsigned), and Android App Bundle (AAB)
4. Artifacts will be attached to the GitHub Release

This ensures that release builds are 100% F-Droid compatible, with all native libraries built from source while using the pre-cleaned OpenCV module for Java classes.

##### OpenCV Integration Builds

This workflow builds the app on every push to main and pull request:

1. Sets up JDK 21 and Android NDK 28.2.13676358
2. Fetches OpenCV source code from GitHub
3. Builds OpenCV native libraries for all architectures
4. Integrates the built native libraries into the app
5. Builds the app with the integrated OpenCV components
6. Uploads the built APK as an artifact

This ensures that the OpenCV integration is tested with each code change, building all native libraries from source.

### Building from Source

1. Clone the repository:
   ```
   git clone https://github.com/egdels/makeacopy.git
   ```

2. The build process will automatically:
   - Fetch OpenCV source code from GitHub
   - Build OpenCV native libraries for all architectures
   - Integrate the built native libraries into the app

   This is handled by Gradle tasks that run the following scripts:
   - `scripts/fetch_opencv.sh`: Downloads OpenCV source code
   - `scripts/build_opencv_android.sh`: Builds OpenCV native libraries for all architectures
   - `scripts/prepare_opencv.sh`: Integrates the built native libraries into the app

3. Build the app using Gradle:
   ```
   ./gradlew assembleDebug
   ```

4. Install the generated APK on your device.

> **F-Droid Compatibility Note**: This project is 100% F-Droid compatible. It uses a two-part approach for OpenCV integration:
> 1. The OpenCV module in the repository has been pre-cleaned of all binaries and contains only the necessary Java classes.
> 2. All native libraries are built from source during the build process using the official OpenCV source code.
>
> This approach ensures that no pre-compiled binaries are included in the repository or the final APK.

#### Setting up the Android NDK

1. **Install the NDK using Android Studio**:
   - Open Android Studio > Settings/Preferences > Appearance & Behavior > System Settings > Android SDK
   - Select the 'SDK Tools' tab
   - Check 'NDK (Side by side)' and click 'Apply'

2. **Set the ANDROID_NDK_HOME environment variable** (optional):
   - The build script will attempt to locate the NDK automatically in common locations
   - If automatic detection fails, you can set the environment variable manually:
     ```bash
     # For Linux/macOS
     export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/28.2.13676358
     
     # For Windows (Command Prompt)
     set ANDROID_NDK_HOME=C:\path\to\android-sdk\ndk\28.2.13676358
     
     # For Windows (PowerShell)
     $env:ANDROID_NDK_HOME="C:\path\to\android-sdk\ndk\28.2.13676358"
     ```

#### Maintaining the OpenCV Integration

The OpenCV integration in this repository uses a two-part approach:

1. **Java Classes**: The pre-cleaned OpenCV module in the repository provides the Java classes.
2. **Native Libraries**: The native libraries are built from source during the build process.

If you need to update the OpenCV integration in the future, follow these steps:

##### Updating the Java Classes (OpenCV Module)

1. **Obtain the new OpenCV Android SDK**:
   - Download the new version from the [OpenCV releases page](https://opencv.org/releases/)
   - Extract the SDK and locate the `sdk` directory

2. **Replace the OpenCV module**:
   - Replace the `opencv` directory in the repository with the `sdk` directory from the new SDK
   - Make sure to preserve any custom modifications you've made to the module

3. **Clean the new OpenCV module**:
   - Run the `clean_opencv_binaries.sh` script to remove all binaries from the new module:
     ```bash
     ./scripts/clean_opencv_binaries.sh
     ```
   - This will remove all pre-compiled binaries and create empty directories with .gitkeep files

4. **Commit the cleaned OpenCV module**:
   - Add the cleaned OpenCV module to the repository:
     ```bash
     git add opencv
     git commit -m "Update OpenCV module to version X.Y.Z (pre-cleaned)"
     ```

##### Updating the Native Libraries Build Process

1. **Update the OpenCV version in the fetch script**:
   - Edit `scripts/fetch_opencv.sh` to update the `OPENCV_VERSION` variable to the new version
   - This ensures that the build process downloads the correct version of the OpenCV source code

2. **Test the build process**:
   - Run the build process to ensure that the native libraries are built correctly:
     ```bash
     ./gradlew clean assembleDebug
     ```

3. **Update version references**:
   - Update any version references in the code, such as in `app/build.gradle`
   - Update the documentation to reflect the new version

This approach ensures that the repository remains F-Droid compatible while allowing for updates to both the Java classes and native libraries.

## Usage

1. **Scan Document**: Open the app and tap the scan button to capture a document
2. **Adjust Corners**: Fine-tune the detected document edges
3. **Crop & Enhance**: Apply the crop and enhance the image if needed
4. **OCR Processing**: The app will recognize text in the document
5. **Export & Share**: Save as PDF or share with other apps

## Architecture

MakeACopy follows the Single-Activity + Multi-Fragment approach with MVVM architecture. The main components include:

- **Camera Fragment**: Document capture using CameraX/Camera2 API
- **Crop Fragment**: Document edge detection and perspective correction
- **OCR Fragment**: Text recognition using Tesseract
- **Export Fragment**: PDF creation and sharing options

## Libraries Used

| Purpose | Library | License |
|---------|---------|---------|
| Image Processing | OpenCV for Android | Apache 2.0 |
| OCR | tess-two (Tesseract JNI) | Apache 2.0 |
| PDF | Android PdfDocument | Apache 2.0 |
| UI | Material Components | Apache 2.0 |

## Privacy

MakeACopy is designed with privacy in mind:

- No internet connection required
- No server requests or telemetry
- OCR processing happens locally on your device
- Required permissions: Camera, File Storage
- No third-party connections

For more details, see our [Privacy Policy](https://egdels.github.io/makeacopy/privacy).

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Future Enhancements

- Multi-language OCR support (e.g., German, French, Spanish)
- Multi-page scanning with PDF merge
- OCR text editor with export as `.txt`
- Support for cloud storage uploads
- Scan optimization with machine learning

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