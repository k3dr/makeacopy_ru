#!/usr/bin/env bash
set -euo pipefail

# Quiet mode default: reduce console output. Set VERBOSE=1 for detailed logs.
VERBOSE="${VERBOSE:-0}"
info() {
  if [ "$VERBOSE" = "1" ]; then
    echo "$@"
  else
    >&2 echo "$@"
  fi
}

# ===============================
# Reproducible build timestamp
# ===============================
export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC

# ===============================
# Repo/paths
# ===============================
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ORT_DIR_ORIG="$REPO_DIR/external/onnxruntime"   # submodule (e.g., v1.22.1)
ORT_DIR="/tmp/onnxruntime-src"
JNI_LIBS_BASE="$REPO_DIR/app/src/main/jniLibs"
APP_LIBS="$REPO_DIR/app/libs"
BUILD_ROOT="/tmp/onnxruntime-build"

# ABIs (extend if needed)
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
info "ONNX Runtime ABIs: $ABIS"

# ===============================
# Cross-platform CPU jobs
# ===============================
cpu_jobs() {
  # Try POSIX and platform-specific ways; fall back to 1
  getconf _NPROCESSORS_ONLN 2>/dev/null || \
  nproc 2>/dev/null || \
  sysctl -n hw.ncpu 2>/dev/null || \
  echo 1
}
JOBS="$(cpu_jobs)"
export CMAKE_BUILD_PARALLEL_LEVEL="$JOBS"   # CMake/Ninja honor this
info "Parallel jobs: $JOBS"

# ===============================
# Prefer Ninja if available (faster), otherwise fall back
# ===============================
if command -v ninja >/dev/null 2>&1; then
  CMAKE_GENERATOR="Ninja"
  info "CMake generator: Ninja"
else
  CMAKE_GENERATOR=""
  info "CMake generator: Unix Makefiles (ninja not found)"
fi

# ===============================
# Locate SDK/NDK (macOS & Linux)
# ===============================
# Prefer existing env if provided
# SDK root
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"         # macOS default
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"                 # Linux default
  fi
fi
info "SDK: ${ANDROID_SDK_ROOT:-UNKNOWN}"

# NDK home
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  CANDIDATES=()
  [ -n "${ANDROID_SDK_ROOT:-}" ] && CANDIDATES+=("$ANDROID_SDK_ROOT/ndk")
  [ -d "$HOME/Library/Android/sdk/ndk" ] && CANDIDATES+=("$HOME/Library/Android/sdk/ndk")
  [ -d "$HOME/Android/Sdk/ndk" ] && CANDIDATES+=("$HOME/Android/Sdk/ndk")

  for base in "${CANDIDATES[@]}"; do
    if [ -d "$base" ]; then
      LATEST_NDK="$(find "$base" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)"
      if [ -n "$LATEST_NDK" ]; then
        export ANDROID_NDK_HOME="$LATEST_NDK"
        break
      fi
    fi
  done
fi
[ -z "${ANDROID_NDK_HOME:-}" ] && { echo "ERROR: ANDROID_NDK_HOME not set and no NDK found"; exit 1; }
info "NDK: $ANDROID_NDK_HOME"

# ===============================
# JDK 17/21 (Gradle/Java)
# ===============================
HOST_OS="$(uname | tr '[:upper:]' '[:lower:]')"
if [ "$HOST_OS" = "darwin" ]; then
  set +e
  if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  fi
  set -e
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
  export ORG_GRADLE_JAVA_HOME="$JAVA_HOME"
fi
info "JAVA_HOME: ${JAVA_HOME:-unset}"
java -version >/dev/null 2>&1 || info "WARN: 'java' not found or wrong version (need >= 17)"

# ===============================
# Strip tool (optional)
# ===============================
HOST_TAG="${HOST_OS}-x86_64"
STRIP_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
if [ ! -x "$STRIP_BIN" ]; then
  STRIP_BIN="$(command -v llvm-strip || true)"
fi
[ -x "$STRIP_BIN" ] && info "llvm-strip: $STRIP_BIN" || info "WARN: llvm-strip not found – skipping stripping."

# ===============================
# Clean submodule working tree
# ===============================
pushd "$ORT_DIR_ORIG" >/dev/null
git clean -xfd
git checkout .
popd >/dev/null

# Fresh copy of ONNX Runtime sources into a path that's stable across machines
info "Copying ONNX sources to $ORT_DIR ..."
rm -rf "$ORT_DIR"
mkdir -p "$ORT_DIR"
cp -a "$ORT_DIR_ORIG/." "$ORT_DIR"

# ===============================
# Pick a CMake >= 3.28 ONLY for ONNX Runtime
# (prefer Android SDK; allow override via ORT_CMAKE)
# ===============================
ver_ge() {  # returns 0 if $1 >= $2
  [ "$(printf '%s\n' "$1" "$2" | sort -V | head -n1)" = "$2" ]
}

pick_sdk_cmake() {
  local sdk="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
  if [ -d "$sdk/cmake" ]; then
    local cand
    cand=$(find "$sdk/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -V | tail -n1)
    if [ -n "$cand" ] && [ -x "$cand/bin/cmake" ]; then
      echo "$cand/bin/cmake"
      return 0
    fi
  fi
  return 1
}

if [ -n "${ORT_CMAKE:-}" ] && [ -x "${ORT_CMAKE:-}" ]; then
  CMAKE_PATH="$ORT_CMAKE"
else
  CMAKE_PATH="$(pick_sdk_cmake || true)"
  if [ -z "${CMAKE_PATH:-}" ]; then
    CMAKE_PATH="$(command -v cmake || true)"
  fi
fi

if [ -z "${CMAKE_PATH:-}" ]; then
  echo "ERROR: cmake not found (need >= 3.28 for ONNX Runtime)" >&2
  exit 1
fi

cmv="$("$CMAKE_PATH" --version | awk '/version/{print $3; exit}')"
info "CMake for ORT: $CMAKE_PATH (version $cmv)"
if ! ver_ge "$cmv" "3.28.0"; then
  echo "ERROR: CMake $cmv < 3.28.0 for ONNX Runtime." >&2
  echo "       Install SDK cmake >= 3.28 (e.g. ANDROID_SDK_ROOT/cmake/3.31.x) or set ORT_CMAKE=<path>." >&2
  exit 1
fi

# ===============================
# Build per ABI (FULL, CPU-only, Java)
# ===============================
rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT" "$APP_LIBS"

for ABI in $ABIS; do
  info "Building ONNX Runtime for $ABI (CPU-only)"
  ABI_BUILD_DIR="$BUILD_ROOT/$ABI"
  rm -rf "$ABI_BUILD_DIR"
  mkdir -p "$ABI_BUILD_DIR"

  # Repro/path-neutral flags
  SRC_DIR="$ORT_DIR"
  C_FLAGS="-g0 -fdebug-prefix-map=$SRC_DIR=. -fmacro-prefix-map=$SRC_DIR=. -ffile-prefix-map=$SRC_DIR=."
  CXX_FLAGS="$C_FLAGS"
  LDFLAGS="-Wl,--build-id=none"

  # Common build.py args
  COMMON_ARGS=(
    --build_dir "$ABI_BUILD_DIR"
    --config Release
    --build_shared_lib
    --skip_tests
    --skip_onnx_tests
    --parallel "$JOBS"
    --use_full_protobuf
    --android
    --android_sdk_path "${ANDROID_SDK_ROOT:?}"
    --android_ndk_path "${ANDROID_NDK_HOME:?}"
    --android_api 21
    --android_abi "$ABI"
    --build_java
    --skip_submodule_sync
    --compile_no_warning_as_error
  )
  # Prefer Ninja if available
  if [ -n "${CMAKE_GENERATOR:-}" ]; then
    COMMON_ARGS+=( --cmake_generator "$CMAKE_GENERATOR" )
  fi

  # CMake defines
  CMAKE_DEFINES=(
    onnxruntime_BUILD_SHARED_LIB=ON
    onnxruntime_BUILD_JAVA=ON
    onnxruntime_ENABLE_PYTHON=OFF
    onnxruntime_BUILD_CSHARP=OFF
    onnxruntime_BUILD_NODEJS=OFF
    onnxruntime_BUILD_OBJC=OFF
    onnxruntime_BUILD_BENCHMARKS=OFF
    onnxruntime_BUILD_MS_EXPERIMENTAL_OPS=OFF

    onnxruntime_USE_FULL_PROTOBUF=ON
    onnxruntime_USE_MIMALLOC=OFF
    onnxruntime_USE_XNNPACK=OFF
    onnxruntime_USE_KLEIDIAI=OFF
    onnxruntime_USE_CUDA=OFF
    onnxruntime_USE_TENSORRT=OFF
    onnxruntime_USE_ROCM=OFF
    onnxruntime_USE_DNNL=OFF
    onnxruntime_USE_OPENVINO=OFF
    onnxruntime_USE_QNN=OFF
    onnxruntime_USE_NNAPI_BUILTIN=OFF
    onnxruntime_USE_ARMNN=OFF
    onnxruntime_USE_ACL=OFF
    onnxruntime_USE_WEBNN=OFF
    onnxruntime_USE_WEBGPU=OFF
    onnxruntime_BUILD_WEBASSEMBLY_STATIC_LIB=OFF

    onnxruntime_ENABLE_LTO=OFF
    onnxruntime_ENABLE_MEMORY_PROFILE=OFF
    onnxruntime_ENABLE_LAZY_TENSOR=OFF

    onnxruntime_DISABLE_CONTRIB_OPS=OFF
    onnxruntime_DISABLE_ML_OPS=ON
    onnxruntime_DISABLE_RTTI=ON
    onnxruntime_DISABLE_EXCEPTIONS=ON
    onnxruntime_DISABLE_FLOAT8_TYPES=ON
    onnxruntime_BUILD_SHARED_LIB_TESTS=OFF

    # Optional footprint tweaks:
    # onnxruntime_DISABLE_SPARSE_TENSORS=ON
    # onnxruntime_DISABLE_OPTIONAL_TYPE=ON

    CMAKE_C_FLAGS="$C_FLAGS"
    CMAKE_CXX_FLAGS="$CXX_FLAGS"
    CMAKE_SHARED_LINKER_FLAGS="$LDFLAGS"
    CMAKE_EXE_LINKER_FLAGS="$LDFLAGS"
  )

  pushd "$ORT_DIR" >/dev/null
  BUILD_LOG_FILE="$ABI_BUILD_DIR/build_$ABI.log"
  python3 tools/ci_build/build.py \
    "${COMMON_ARGS[@]}" \
    --cmake_path "$CMAKE_PATH" \
    --cmake_extra_defines "${CMAKE_DEFINES[@]}" \
    >> "$BUILD_LOG_FILE" 2>&1 || { echo "ERROR: ONNX build failed for $ABI. See $BUILD_LOG_FILE" >&2; tail -n 40 "$BUILD_LOG_FILE" >&2 || true; popd >/dev/null; exit 1; }
  popd >/dev/null

  # Locate artifacts
  LIB_CORE="$ABI_BUILD_DIR/Release/libonnxruntime.so"
  LIB_JNI="$ABI_BUILD_DIR/Release/libonnxruntime4j_jni.so"
  [ -f "$LIB_CORE" ] || LIB_CORE="$(find "$ABI_BUILD_DIR" -type f -name 'libonnxruntime.so' -print -quit || true)"
  [ -f "$LIB_JNI"  ] || LIB_JNI="$(find "$ABI_BUILD_DIR" -type f -name 'libonnxruntime4j_jni.so' -print -quit || true)"
  if [ -z "$LIB_CORE" ] || [ -z "$LIB_JNI" ]; then
    echo "ERROR: libonnxruntime(.so) or libonnxruntime4j_jni(.so) not found." >&2
    exit 1
  fi

  # Copy into project
  mkdir -p "$JNI_LIBS_BASE/$ABI"
  cp -f "$LIB_CORE" "$JNI_LIBS_BASE/$ABI/libonnxruntime.so"
  cp -f "$LIB_JNI"  "$JNI_LIBS_BASE/$ABI/libonnxruntime4j_jni.so"

  # Strip (optional)
  if [ -n "${STRIP_BIN:-}" ] && [ -x "$STRIP_BIN" ]; then
    "$STRIP_BIN" --strip-all \
      --remove-section=.comment \
      --remove-section=.note \
      --remove-section=.note.gnu.build-id \
      --remove-section=.note.gnu.property \
      "$JNI_LIBS_BASE/$ABI/libonnxruntime.so" || true
    "$STRIP_BIN" --strip-all \
      --remove-section=.comment \
      --remove-section=.note \
      --remove-section=.note.gnu.build-id \
      --remove-section=.note.gnu.property \
      "$JNI_LIBS_BASE/$ABI/libonnxruntime4j_jni.so" || true
    info "Stripping done."
  fi

  # Copy Java JAR once
  if [ ! -f "$APP_LIBS/onnxruntime-1.22.1.jar" ]; then
    JAR_PATH="$(find "$ORT_DIR/java/build/libs" -type f -name 'onnxruntime-*.jar' \
      ! -name '*sources*.jar' ! -name '*javadoc*.jar' -print -quit || true)"
    [ -n "$JAR_PATH" ] || { echo "ERROR: onnxruntime JAR not found."; exit 1; }
    if ! jar tf "$JAR_PATH" | grep -q 'ai/onnxruntime/OrtEnvironment.class'; then
      echo "ERROR: JAR missing classes: $JAR_PATH"
      exit 1
    fi
    mkdir -p "$APP_LIBS"
    cp -f "$JAR_PATH" "$APP_LIBS/onnxruntime-1.22.1.jar"
  fi
done

info "Done."
info "JAR: $APP_LIBS/onnxruntime-1.22.1.jar"
for ABI in $ABIS; do
  info "SOs ($ABI): $JNI_LIBS_BASE/$ABI/libonnxruntime.so, libonnxruntime4j_jni.so"
done
