#!/bin/bash
set -o pipefail  # Fail if any command in a pipeline fails

# ==== Reproducible build timestamp ====
# This ensures deterministic timestamps in builds (for reproducibility)
export SOURCE_DATE_EPOCH=1700000000

# ==== Absolute paths ====
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR="$SCRIPT_DIR/external/opencv"
BUILD_DIR="$SCRIPT_DIR/external/opencv-build"

# ==== Clean OpenCV sources ====
# Removes all untracked files and directories (ensures clean state)
cd "$OPENCV_DIR"
git clean -xfd
git checkout .

# -----------------------------------------------------------------------------
# 🩹 Patch OpenCV to suppress status() output and fix build info string
# -----------------------------------------------------------------------------
OPENCV_UTILS="$OPENCV_DIR/cmake/OpenCVUtils.cmake"
BACKUP_UTILS="${OPENCV_UTILS}.bak"

# macOS-kompatible sed
sedi() {
  if sed --version >/dev/null 2>&1; then
    sed -i "$@"
  else
    sed -i '' "$@"
  fi
}

echo "🔧 Replacing ocv_output_status() in $OPENCV_UTILS..."
cp "$OPENCV_UTILS" "$BACKUP_UTILS"

# Delete original function
sedi '/^[[:space:]]*function(ocv_output_status/,/^[[:space:]]*endfunction/ d' "$OPENCV_UTILS"

# Append replacement at the end of the file
cat <<'EOF' >> "$OPENCV_UTILS"

# Patched: deterministic ocv_output_status()
function(ocv_output_status msg)
  set(OPENCV_BUILD_INFO_STR "\"OpenCV 4.12.0 (reproducible build)\\n\"" CACHE INTERNAL "")
endfunction()
EOF

echo "✅ ocv_output_status() replaced with reproducible version."

# ==== Error handler ====
# Function to report errors with helpful context
log_error() {
  echo "ERROR: $1"
  echo "Please check the full build log for more details."
  echo "If you're using a different NDK version and experiencing issues, try using NDK version 27.3.13750724 instead."
}
# Triggers error function if any command fails
trap 'log_error "Build failed at line $LINENO"' ERR

# ==== Find ANDROID_NDK_HOME if not already set ====
# Try several common locations in SDK directories
if [ -z "$ANDROID_NDK_HOME" ]; then
  if [ -n "$ANDROID_SDK_ROOT" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
      LATEST_NDK=$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
      if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        echo "Found NDK at $ANDROID_NDK_HOME"
      fi
    fi
    if [ -z "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_SDK_ROOT/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk-bundle"
      echo "Found NDK at $ANDROID_NDK_HOME"
    fi
  fi
  if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
      LATEST_NDK=$(find "$HOME/Library/Android/sdk/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
      if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        echo "Found NDK at $ANDROID_NDK_HOME"
      fi
    elif [ -d "$HOME/Library/Android/sdk/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk-bundle"
      echo "Found NDK at $ANDROID_NDK_HOME"
    else
      echo "Error: ANDROID_NDK_HOME is not set and NDK could not be found automatically."
      exit 1
    fi
  fi
fi

# ==== Print NDK version ====
echo "Using Android NDK at: $ANDROID_NDK_HOME"
NDK_VERSION=$(basename "$ANDROID_NDK_HOME")
RECOMMENDED_VERSION="27.3.13750724"
echo "Detected NDK version: $NDK_VERSION"
if [[ "$NDK_VERSION" != "$RECOMMENDED_VERSION" ]]; then
  echo "WARNING: You are using NDK version $NDK_VERSION which is different from the recommended version $RECOMMENDED_VERSION."
fi

# ==== Validate OpenCV source directory ====
echo "OpenCV source directory: $OPENCV_DIR"
if [ ! -d "$OPENCV_DIR" ]; then
  echo "Error: OpenCV source not found at $OPENCV_DIR"
  exit 1
fi

# ==== Prepare build directory ====
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/lib"

# ==== Find Android SDK if not already set ====
if [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "Warning: ANDROID_SDK_ROOT is not set. Trying to find it automatically..."
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    echo "Found Android SDK at $ANDROID_SDK_ROOT"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    echo "Found Android SDK at $ANDROID_SDK_ROOT"
  fi
fi

# ==== Find CMake binary ====
CMAKE_PATH=""
if [ -n "$ANDROID_SDK_ROOT" ]; then
  if [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    LATEST_CMAKE_DIR=$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
    if [ -n "$LATEST_CMAKE_DIR" ] && [ -d "$LATEST_CMAKE_DIR/bin" ]; then
      if [ -x "$LATEST_CMAKE_DIR/bin/cmake" ]; then
        CMAKE_PATH="$LATEST_CMAKE_DIR/bin/cmake"
        echo "Found CMake in Android SDK at $CMAKE_PATH"
      fi
    fi
  fi
fi
if [ -z "$CMAKE_PATH" ]; then
  CMAKE_PATH=$(which cmake 2>/dev/null)
  if [ -n "$CMAKE_PATH" ]; then
    echo "Found system CMake at $CMAKE_PATH"
  fi
fi
if [ -z "$CMAKE_PATH" ]; then
  echo "ERROR: CMake not found. Please install CMake."
  exit 1
fi
echo "Using CMake at: $CMAKE_PATH"

# ==== Set log file for the build ====
BUILD_LOG="$BUILD_DIR/opencv_build.log"
echo "Build log will be saved to: $BUILD_LOG"
echo "$(date): Starting OpenCV build" > "$BUILD_LOG"
echo "NDK version: $NDK_VERSION" >> "$BUILD_LOG"

# ==== Function to build OpenCV for a given architecture ====
build_for_arch() {
  local arch=$1
  local arch_build_dir="${BUILD_DIR}_${arch}"
  rm -rf "$arch_build_dir"
  mkdir -p "$arch_build_dir"
  cd "$arch_build_dir"

  local arch_log="$arch_build_dir/opencv_build_$arch.log"
  echo "$(date): Starting OpenCV build for $arch" > "$arch_log"

  # Configure CMake with appropriate options for Android
  echo "Configuring CMake for $arch..."
  "$CMAKE_PATH" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$arch" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DCMAKE_C_FLAGS="-g0 -fdebug-prefix-map=$SCRIPT_DIR=. -ffile-prefix-map=$SCRIPT_DIR=. " \
    -DCMAKE_CXX_FLAGS="-g0 -fdebug-prefix-map=$SCRIPT_DIR=. -ffile-prefix-map=$SCRIPT_DIR=. " \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
    -DBUILD_ANDROID_PROJECTS=ON \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC_LIBS=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_DOCS=OFF \
    -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_JAVA=ON \
    -DBUILD_opencv_java=ON \
    -DBUILD_opencv_imgproc=ON \
    -DBUILD_opencv_flann=OFF \
    -DBUILD_opencv_imgcodecs=ON \
    -DBUILD_opencv_video=ON \
    -DBUILD_opencv_videoio=ON \
    -DBUILD_opencv_calib3d=OFF \
    -DBUILD_opencv_features2d=OFF \
    -DBUILD_opencv_objdetect=OFF \
    -DBUILD_opencv_dnn=OFF \
    -DBUILD_opencv_gapi=OFF \
    -DBUILD_opencv_ml=OFF \
    -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_photo=OFF \
    -DBUILD_opencv_stitching=OFF \
    -DWITH_OPENCL=OFF \
    -DWITH_IPP=OFF \
    -DCMAKE_CXX_STANDARD=11 \
    -DCMAKE_CXX_STANDARD_REQUIRED=ON \
    -DCMAKE_C_ARCHIVE_CREATE="<CMAKE_AR> qc <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_C_ARCHIVE_FINISH=":" \
    -DCMAKE_CXX_ARCHIVE_CREATE="<CMAKE_AR> qc <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_CXX_ARCHIVE_FINISH=":" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$OPENCV_DIR" 2>&1 | tee -a "$arch_log"

  if [ ${PIPESTATUS[0]} -ne 0 ]; then
    log_error "CMake configuration for $arch failed. See $arch_log for details."
    tail -n 20 "$arch_log"
    cd "$SCRIPT_DIR"
    return 1
  fi

  # Append a fix to Gradle file to ensure Kotlin uses correct JVM target
  echo "
  tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
      kotlinOptions {
          jvmTarget = '17'
          println '✅ Set Kotlin JVM target to 17 for task'
      }
  }
  " | tee -a "$arch_build_dir/opencv_android/opencv/build.gradle"

  # Run the build (single-threaded for reproducibility)
  echo "Building OpenCV for $arch (single-threaded)..."
  if ! make -j1 2>&1 | tee -a "$arch_log"; then
    echo "Build failed for $arch"
    tail -n 20 "$arch_log"
    cd "$SCRIPT_DIR"
    return 1
  fi
  # Copy the built libraries to the build directory
  mkdir -p "$BUILD_DIR/lib/$arch"
  echo "Copying shared libraries for $arch..."
  find . -name "*.so" -exec cp -f {} "$BUILD_DIR/lib/$arch/" \;

  # Strip debug symbols to reduce file size
  HOST_TAG="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
  STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
  if [ -x "$STRIP" ]; then
      echo "Stripping debug and metadata sections from $arch libraries..."
      find "$BUILD_DIR/lib/$arch" -name "*.so" -exec "$STRIP" \
          --strip-all \
          --remove-section=.comment \
          --remove-section=.note \
          --remove-section=.note.gnu.build-id \
          --remove-section=.note.gnu.property \
          --remove-section=.note.ABI-tag \
          {} \;
      echo "✅ Stripped and cleaned $arch libraries for reproducibility."
  else
      echo "⚠️ Warning: Strip tool not found at $STRIP. Skipping stripping for $arch."
  fi

  cd "$SCRIPT_DIR"
  echo "✅ OpenCV for $arch built successfully."
  return 0
}

# ==== Build loop for all architectures ====
echo "Building OpenCV for all target ABIs..."
BUILD_FAILED=0
for ARCH in "arm64-v8a" "armeabi-v7a" "x86" "x86_64"; do
  build_for_arch "$ARCH" || BUILD_FAILED=1
done

if [ $BUILD_FAILED -eq 0 ]; then
  echo "✅ OpenCV for Android built successfully."
  echo "$(date): Build completed successfully." >> "$BUILD_LOG"
else
  echo "❌ Error: Some builds failed."
  exit 1
fi

echo "Build log is available at: $BUILD_LOG"
exit 0
