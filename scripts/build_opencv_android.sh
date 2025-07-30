#!/bin/bash
set -o pipefail

# ==== Reproducible build timestamp ====
export SOURCE_DATE_EPOCH=1700000000

# ==== ABSOLUTER PFAD ====
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR="$SCRIPT_DIR/external/opencv"
BUILD_DIR="$SCRIPT_DIR/external/opencv-build"

# ==== FEHLERLOGIK ====
log_error() {
  echo "ERROR: $1"
  echo "Please check the full build log for more details."
  echo "If you're using a different NDK version and experiencing issues, try using NDK version 27.3.13750724 instead."
}
trap 'log_error "Build failed at line $LINENO"' ERR

# ==== ANDROID NDK SUCHEN ====
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

echo "Using Android NDK at: $ANDROID_NDK_HOME"
NDK_VERSION=$(basename "$ANDROID_NDK_HOME")
RECOMMENDED_VERSION="27.3.13750724"
echo "Detected NDK version: $NDK_VERSION"
if [[ "$NDK_VERSION" != "$RECOMMENDED_VERSION" ]]; then
  echo "WARNING: You are using NDK version $NDK_VERSION which is different from the recommended version $RECOMMENDED_VERSION."
fi

# ==== OpenCV Quellen prüfen ====
echo "OpenCV source directory: $OPENCV_DIR"
if [ ! -d "$OPENCV_DIR" ]; then
  echo "Error: OpenCV source not found at $OPENCV_DIR"
  exit 1
fi

# ==== BUILD DIR anlegen ====
mkdir -p "$BUILD_DIR/lib"

# ==== ANDROID_SDK_ROOT prüfen ====
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

# ==== CMake suchen ====
CMAKE_PATH=""
if [ -n "$ANDROID_SDK_ROOT" ]; then
  if [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    LATEST_CMAKE_DIR=$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
    if [ -n "$LATEST_CMAKE_DIR" ] && [ -d "$LATEST_CMAKE_DIR/bin" ]; then
      if [ -f "$LATEST_CMAKE_DIR/bin/cmake" ] && [ -x "$LATEST_CMAKE_DIR/bin/cmake" ]; then
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

# ==== LOG-FILE ====
BUILD_LOG="$BUILD_DIR/opencv_build.log"
echo "Build log will be saved to: $BUILD_LOG"
echo "$(date): Starting OpenCV build" > "$BUILD_LOG"
echo "NDK version: $NDK_VERSION" >> "$BUILD_LOG"

# ==== BUILD-FUNKTION FÜR ARCHITEKTUR ====
build_for_arch() {
  local arch=$1
  local arch_build_dir="${BUILD_DIR}_${arch}"
  rm -rf "$arch_build_dir"
  mkdir -p "$arch_build_dir"
  cd "$arch_build_dir"

  local arch_log="$arch_build_dir/opencv_build_$arch.log"
  echo "$(date): Starting OpenCV build for $arch" > "$arch_log"

  echo "Configuring CMake for $arch..."
  "$CMAKE_PATH" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$arch" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DCMAKE_C_FLAGS="-g0 -fdebug-prefix-map=$SCRIPT_DIR=." \
    -DCMAKE_CXX_FLAGS="-g0 -fdebug-prefix-map=$SCRIPT_DIR=." \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
    -DBUILD_ANDROID_PROJECTS=ON \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC_LIBS=OFF \
    -DBUILD_TESTS=OFF \
    -DBUILD_PERF_TESTS=OFF \
    -DBUILD_EXAMPLES=OFF \
    -DBUILD_opencv_java=ON \
    -DBUILD_opencv_core=ON \
    -DBUILD_opencv_imgproc=ON \
    -DBUILD_opencv_imgcodecs=OFF \
    -DBUILD_opencv_video=OFF \
    -DBUILD_opencv_videoio=OFF \
    -DBUILD_opencv_calib3d=OFF \
    -DBUILD_opencv_features2d=OFF \
    -DBUILD_opencv_objdetect=OFF \
    -DBUILD_opencv_dnn=OFF \
    -DBUILD_opencv_gapi=OFF \
    -DBUILD_opencv_ml=OFF \
    -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_photo=OFF \
    -DBUILD_opencv_stitching=OFF \
    -DBUILD_JAVA=ON \
    -DBUILD_DOCS=OFF \
    -DWITH_OPENCL=OFF \
    -DWITH_IPP=OFF \
    -DCMAKE_CXX_STANDARD=11 \
    -DCMAKE_CXX_STANDARD_REQUIRED=ON \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$OPENCV_DIR" 2>&1 | tee -a "$arch_log"


  if [ ${PIPESTATUS[0]} -ne 0 ]; then
    log_error "CMake configuration for $arch failed. See $arch_log for details."
    tail -n 20 "$arch_log"
    cd "$SCRIPT_DIR"
    return 1
  fi

  echo "Building OpenCV for $arch (single-threaded for reproducibility)..."
  echo "$(date): Starting single-threaded build for $arch" >> "$arch_log"
  if ! make -j1 2>&1 | tee -a "$arch_log"; then
    echo "Single-threaded build for $arch failed. See $arch_log for details."
    tail -n 20 "$arch_log"
    cd "$SCRIPT_DIR"
    return 1
  else
    echo "Single-threaded build for $arch completed successfully."
    echo "$(date): Single-threaded build for $arch completed successfully." >> "$arch_log"
  fi

  mkdir -p "$BUILD_DIR/lib/$arch"
  echo "Copying $arch libraries to main build directory..."
  # Besser: alle .so im Baum suchen und kopieren (falls Pfad sich ändert)
  find . -name "*.so" -exec cp -f {} "$BUILD_DIR/lib/$arch/" \;

  cd "$SCRIPT_DIR"
  echo "OpenCV for $arch built successfully."
  return 0
}

echo "Building OpenCV for all architectures..."
BUILD_FAILED=0
for ARCH in "arm64-v8a" "armeabi-v7a"; do # riscv64 optional
  build_for_arch "$ARCH" || BUILD_FAILED=1
done

if [ $BUILD_FAILED -eq 0 ]; then
  echo "✅ OpenCV for Android built successfully."
  echo "$(date): Build completed successfully." >> "$BUILD_LOG"
else
  echo "⚠️ WARNING: Some architectures failed to build. Continuing anyway."
  echo "$(date): Build completed with partial success." >> "$BUILD_LOG"
fi

echo "Build log is available at: $BUILD_LOG"
exit 0
