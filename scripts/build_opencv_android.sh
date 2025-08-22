#!/bin/bash
set -o pipefail  # Fail if any command in a pipeline fails

# Quiet mode: reduce console noise by default. Set VERBOSE=1 to stream more info.
VERBOSE="${VERBOSE:-0}"
info() {
  if [ "$VERBOSE" = "1" ]; then
    echo "$@"
  else
    # In quiet mode, send minimal info to stderr so it appears on CI
    >&2 echo "$@"
  fi
}

# ===
# ABIs (extend if needed)
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
info "ABIS: [$ABIS]"

# ==== Reproducible build timestamp ====
export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC
export LC_ALL=C
export LANG=C

# ==== Absolute paths ====
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR_ORIG="$SCRIPT_DIR/external/opencv"
BUILD_DIR="/tmp/opencv-build"
OPENCV_DIR="/tmp/opencv-src"

# ==== Clean OpenCV sources ====
cd "$OPENCV_DIR_ORIG"
git clean -xfd
git checkout .

# ==== Copy OpenCV sources to build directory ====
info "Copying OpenCV sources to $OPENCV_DIR..."
rm -rf "$OPENCV_DIR"
mkdir -p "$OPENCV_DIR"
cp -a "$OPENCV_DIR_ORIG/." "$OPENCV_DIR"

# -----------------------------------------------------------------------------
# 🩹 Patch OpenCV to suppress status() output and fix build info string
# -----------------------------------------------------------------------------
OPENCV_UTILS="$OPENCV_DIR/cmake/OpenCVUtils.cmake"
BACKUP_UTILS="${OPENCV_UTILS}.bak"

sedi() {
  if sed --version >/dev/null 2>&1; then
    sed -i "$@"
  else
    sed -i '' "$@"
  fi
}

info "Patching ocv_output_status() in $OPENCV_UTILS..."
cp "$OPENCV_UTILS" "$BACKUP_UTILS"
sedi '/^[[:space:]]*function(ocv_output_status/,/^[[:space:]]*endfunction/ d' "$OPENCV_UTILS"
cat <<'EOF' >> "$OPENCV_UTILS"

# Patched: deterministic ocv_output_status()
function(ocv_output_status msg)
  set(OPENCV_BUILD_INFO_STR "\"OpenCV 4.12.0 (reproducible build)\\n\"" CACHE INTERNAL "")
endfunction()
EOF
info "ocv_output_status() patched."

# ==== Error handler ====
log_error() {
  echo "ERROR: $1"
  echo "Please check the full build log for more details."
  echo "If you're using a different NDK version and experiencing issues, try using NDK version 27.3.13750724 instead."
}
trap 'log_error "Build failed at line $LINENO"' ERR

# ==== Find ANDROID_NDK_HOME if not already set ====
if [ -z "$ANDROID_NDK_HOME" ]; then
  if [ -n "$ANDROID_SDK_ROOT" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
      LATEST_NDK=$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
      if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        info "Found NDK at $ANDROID_NDK_HOME"
      fi
    fi
    if [ -z "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_SDK_ROOT/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk-bundle"
      info "Found NDK at $ANDROID_NDK_HOME"
    fi
  fi
  if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
      LATEST_NDK=$(find "$HOME/Library/Android/sdk/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
      if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        info "Found NDK at $ANDROID_NDK_HOME"
      fi
    elif [ -d "$HOME/Library/Android/sdk/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk-bundle"
      info "Found NDK at $ANDROID_NDK_HOME"
    else
      echo "Error: ANDROID_NDK_HOME is not set and NDK could not be found automatically."
      exit 1
    fi
  fi
fi

# ==== Print NDK version ====
info "Using Android NDK at: $ANDROID_NDK_HOME"
NDK_VERSION=$(basename "$ANDROID_NDK_HOME")
RECOMMENDED_VERSION="27.3.13750724"
info "Detected NDK version: $NDK_VERSION"
if [[ "$NDK_VERSION" != "$RECOMMENDED_VERSION" ]]; then
  info "NDK version $NDK_VERSION differs from recommended $RECOMMENDED_VERSION."
fi

# ==== Validate OpenCV source directory ====
info "OpenCV source directory: $OPENCV_DIR"
if [ ! -d "$OPENCV_DIR" ]; then
  echo "Error: OpenCV source not found at $OPENCV_DIR"
  exit 1
fi

# ==== Prepare build directory ====
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/lib"

# ==== Find Android SDK if not already set ====
if [ -z "$ANDROID_SDK_ROOT" ]; then
  info "ANDROID_SDK_ROOT is not set. Trying to find it automatically..."
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
    info "Found Android SDK at $ANDROID_SDK_ROOT"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    info "Found Android SDK at $ANDROID_SDK_ROOT"
  fi
fi

# ==== Pick CMake for OpenCV (pin via OPENCV_CMAKE; optional version guard) ====
if [ -z "${OPENCV_CMAKE:-}" ]; then
  if [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    LATEST_CMAKE_DIR=$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1)
    if [ -n "$LATEST_CMAKE_DIR" ] && [ -x "$LATEST_CMAKE_DIR/bin/cmake" ]; then
      OPENCV_CMAKE="$LATEST_CMAKE_DIR/bin/cmake"
      info "Found SDK CMake for OpenCV at $OPENCV_CMAKE"
    fi
  fi
fi
if [ -z "${OPENCV_CMAKE:-}" ]; then
  OPENCV_CMAKE="$(command -v cmake 2>/dev/null || true)"
  [ -n "$OPENCV_CMAKE" ] && info "Using system CMake for OpenCV at $OPENCV_CMAKE"
fi
if [ -z "${OPENCV_CMAKE:-}" ] || [ ! -x "$OPENCV_CMAKE" ]; then
  echo "ERROR: CMake for OpenCV not found. Please set OPENCV_CMAKE." >&2
  exit 1
fi

OPENCV_CMAKE_VER="$("$OPENCV_CMAKE" --version | awk '/version/{print $3; exit}')"
info "OpenCV CMake: $OPENCV_CMAKE (version $OPENCV_CMAKE_VER)"
if [ -n "${OPENCV_CMAKE_REQ:-}" ] && [ "$OPENCV_CMAKE_VER" != "$OPENCV_CMAKE_REQ" ]; then
  echo "ERROR: OpenCV CMake $OPENCV_CMAKE_VER != required $OPENCV_CMAKE_REQ" >&2
  exit 1
fi

# ==== Robust toolchain dir detection (darwin-aarch64, darwin-x86_64, linux-x86_64) ====
PREBUILT_BASE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"

detect_toolchain_dir() {
  local host_os host_arch
  case "$(uname -s)" in
    Darwin) host_os=darwin ;;
    Linux)  host_os=linux  ;;
    *)      host_os=linux  ;;
  esac
  # NDK nutzt "aarch64" (nicht "arm64") als Verzeichnisname
  case "$(uname -m)" in
    arm64|aarch64) host_arch=aarch64 ;;
    x86_64|amd64)  host_arch=x86_64  ;;
    *)             host_arch=x86_64  ;;
  esac

  # 1) Bevorzugte Kandidaten in Reihenfolge prüfen
  for cand in \
      "$PREBUILT_BASE/${host_os}-${host_arch}" \
      "$PREBUILT_BASE/${host_os}-aarch64" \
      "$PREBUILT_BASE/${host_os}-arm64" \
      "$PREBUILT_BASE/${host_os}-x86_64"
  do
    if [ -x "$cand/bin/llvm-ar" ]; then
      echo "$cand"
      return 0
    fi
  done

  # 2) Fallback: irgendein passendes Verzeichnis mit llvm-ar nehmen
  local any
  any="$(find "$PREBUILT_BASE" -maxdepth 1 -type d -name "${host_os}-*" 2>/dev/null | while read -r d; do
    [ -x "$d/bin/llvm-ar" ] && echo "$d" && break
  done)"
  if [ -n "$any" ]; then
    echo "$any"
    return 0
  fi

  return 1
}

TOOLCHAIN_DIR="$(detect_toolchain_dir || true)"
if [ -z "$TOOLCHAIN_DIR" ]; then
  echo "ERROR: Could not locate NDK toolchain dir with llvm-ar under $PREBUILT_BASE" >&2
  exit 1
fi
info "Using NDK toolchain: $TOOLCHAIN_DIR"

# ==== Set log file for the build ====
BUILD_LOG="$BUILD_DIR/opencv_build.log"
info "Build log: $BUILD_LOG"
echo "$(date): Starting OpenCV build" > "$BUILD_LOG"
echo "NDK version: $NDK_VERSION" >> "$BUILD_LOG"

# ==== Per-ABI build function ====
build_for_arch() {
  local arch=$1
  local arch_build_dir="${BUILD_DIR}_${arch}"
  rm -rf "$arch_build_dir"
  mkdir -p "$arch_build_dir"
  cd "$arch_build_dir"

  local arch_log="$arch_build_dir/opencv_build_$arch.log"
  echo "$(date): Starting OpenCV build for $arch" > "$arch_log"

  export ZERO_AR_DATE=1
  info "Configuring CMake for $arch..."

  local AR_BIN="$TOOLCHAIN_DIR/bin/llvm-ar"
  local RANLIB_BIN="$TOOLCHAIN_DIR/bin/llvm-ranlib"
  if [ ! -x "$AR_BIN" ] || [ ! -x "$RANLIB_BIN" ]; then
    echo "ERROR: llvm-ar/llvm-ranlib not found under $TOOLCHAIN_DIR/bin" >&2
    exit 1
  fi

  # Ensure library target dirs exist
  mkdir -p "$arch_build_dir/3rdparty/lib/$arch" "$arch_build_dir/lib/$arch"

  "$OPENCV_CMAKE" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$arch" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DCMAKE_AR="$AR_BIN" \
    -DCMAKE_RANLIB="$RANLIB_BIN" \
    -DCMAKE_C_FLAGS="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=. " \
    -DCMAKE_CXX_FLAGS="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=. " \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS_RELEASE="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=." \
    -DCMAKE_CXX_FLAGS_RELEASE="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=." \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_INSTALL_PREFIX="/__repro" \
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
    -DCMAKE_C_ARCHIVE_CREATE="<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_C_ARCHIVE_FINISH=":" \
    -DCMAKE_CXX_ARCHIVE_CREATE="<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_CXX_ARCHIVE_FINISH=":" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$OPENCV_DIR" >> "$arch_log" 2>&1

  if [ $? -ne 0 ]; then
    log_error "CMake configuration for $arch failed. See $arch_log for details."
    tail -n 50 "$arch_log" >&2
    cd "$SCRIPT_DIR"
    return 1
  fi

  # Gradle Kotlin jvmTarget safety
  echo "
  tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
      kotlinOptions { jvmTarget = '17' }
  }
  " | tee -a "$arch_build_dir/opencv_android/opencv/build.gradle" >/dev/null

  info "Building OpenCV for $arch (single-threaded)..."
  if ! make -j1 >> "$arch_log" 2>&1; then
    echo "Build failed for $arch" >&2
    tail -n 50 "$arch_log" >&2
    cd "$SCRIPT_DIR"
    return 1
  fi

  mkdir -p "$BUILD_DIR/lib/$arch"
  info "Copying shared libraries for $arch..."
  find . -name "*.so" -exec cp -f {} "$BUILD_DIR/lib/$arch/" \;

  local STRIP="$TOOLCHAIN_DIR/bin/llvm-strip"
  if [ -x "$STRIP" ]; then
    info "Stripping debug and metadata sections from $arch libraries..."
    find "$BUILD_DIR/lib/$arch" -name "*.so" -exec "$STRIP" \
      --strip-all \
      --remove-section=.comment \
      --remove-section=.note \
      --remove-section=.note.gnu.build-id \
      --remove-section=.note.gnu.property \
      --remove-section=.note.ABI-tag \
      {} \;
    info "Stripped and cleaned $arch libraries for reproducibility."
  else
    echo "⚠️ Warning: Strip tool not found at $STRIP. Skipping stripping for $arch."
  fi

  cd "$SCRIPT_DIR"
  info "OpenCV for $arch built successfully."
  return 0
}

# ==== Build loop for all target ABIs ====
info "Building OpenCV for all target ABIs..."
BUILD_FAILED=0

for ARCH in $ABIS; do
  build_for_arch "$ARCH" || BUILD_FAILED=1
done

if [ $BUILD_FAILED -eq 0 ]; then
  info "✅ OpenCV for Android built successfully."
  echo "$(date): Build completed successfully." >> "$BUILD_LOG"
else
  echo "❌ Error: Some builds failed." >&2
  exit 1
fi

info "Build log: $BUILD_LOG"
exit 0
