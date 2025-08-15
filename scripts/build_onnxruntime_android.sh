#!/usr/bin/env bash
set -euo pipefail

# ===============================
# Reproducible build timestamp
# ===============================
export SOURCE_DATE_EPOCH=1700000000
export TZ=UTC

# ===============================
# Repo/Pfade
# ===============================
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ORT_DIR="$REPO_DIR/external/onnxruntime"            # Submodule (v1.22.1)
JNI_LIBS_BASE="$REPO_DIR/app/src/main/jniLibs"
APP_LIBS="$REPO_DIR/app/libs"
BUILD_ROOT="/tmp/onnxruntime-build"

# ABIs (bei Bedarf erweitern)
ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
echo "== ONNX Runtime ${ORT_TAG} → ABIs: ${ABIS[*]} =="

# ===============================
# SDK/NDK finden
# ===============================
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    LATEST_NDK=$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)
    [ -n "$LATEST_NDK" ] && export ANDROID_NDK_HOME="$LATEST_NDK"
  fi
  if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk/ndk" ]; then
    LATEST_NDK=$(find "$HOME/Library/Android/sdk/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)
    [ -n "$LATEST_NDK" ] && export ANDROID_NDK_HOME="$LATEST_NDK"
  fi
  if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk/ndk-bundle" ]; then
    export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk-bundle"
  fi
fi
[ -z "${ANDROID_NDK_HOME:-}" ] && { echo "ERROR: ANDROID_NDK_HOME not set"; exit 1; }
echo "NDK: $ANDROID_NDK_HOME"

if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  [ -d "$HOME/Library/Android/sdk" ] && export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -d "$HOME/Android/Sdk" ] && export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
fi
echo "SDK: ${ANDROID_SDK_ROOT:-UNKNOWN}"

# ===============================
# JDK 17/21 setzen (für Gradle/Java)
# ===============================
set +e
if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
set -e
if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
  export ORG_GRADLE_JAVA_HOME="$JAVA_HOME"
  echo "JAVA_HOME: $JAVA_HOME"
  java -version
else
  echo "WARN: Konnte JAVA_HOME nicht automatisch setzen. Stelle sicher, dass 'java -version' >= 17 ist."
  java -version || true
fi

# ===============================
# Strip-Tool (optional)
# ===============================
HOST_OS="$(uname | tr '[:upper:]' '[:lower:]')"
HOST_TAG="${HOST_OS}-x86_64"
STRIP_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
if [ ! -x "$STRIP_BIN" ] && [ "$HOST_OS" = "darwin" ]; then
  :
fi
[ -x "$STRIP_BIN" ] && echo "llvm-strip: $STRIP_BIN" || echo "WARN: llvm-strip nicht gefunden – skip stripping."


pushd "$ORT_DIR" >/dev/null
git clean -xfd
git checkout .
popd >/dev/null

# ===============================
# Build je ABI (FULL, CPU-only, Java)
# ===============================
rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT" "$APP_LIBS"

for ABI in "${ABIS[@]}"; do
  echo "=== Build ONNX Runtime for $ABI (FULL, CPU-only) ==="
  ABI_BUILD_DIR="$BUILD_ROOT/$ABI"
  rm -rf "$ABI_BUILD_DIR"
  mkdir -p "$ABI_BUILD_DIR"

  # Repro/Path-Neutral Flags
  SRC_DIR="$ORT_DIR"
  C_FLAGS="-g0 -fdebug-prefix-map=$SRC_DIR=. -fmacro-prefix-map=$SRC_DIR=. -ffile-prefix-map=$SRC_DIR=."
  CXX_FLAGS="$C_FLAGS"
  LDFLAGS="-Wl,--build-id=none"

  # build.py common args
  COMMON_ARGS=(
    --build_dir "$ABI_BUILD_DIR"
    --config Release
    --build_shared_lib
    --skip_tests
    --skip_onnx_tests
    --parallel 1
    --use_full_protobuf
    --android
    --android_sdk_path "$ANDROID_SDK_ROOT"
    --android_ndk_path "$ANDROID_NDK_HOME"
    --android_api 21
    --android_abi "$ABI"
    --build_java
    --skip_submodule_sync
    --compile_no_warning_as_error
  )

  # cmake defines
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
    # Optional kleineren Footprint testen:
    # onnxruntime_DISABLE_SPARSE_TENSORS=ON
    # onnxruntime_DISABLE_OPTIONAL_TYPE=ON
    CMAKE_C_FLAGS="$C_FLAGS"
    CMAKE_CXX_FLAGS="$CXX_FLAGS"
    CMAKE_SHARED_LINKER_FLAGS="$LDFLAGS"
    CMAKE_EXE_LINKER_FLAGS="$LDFLAGS"
  )

  pushd "$ORT_DIR" >/dev/null
  python3 tools/ci_build/build.py \
    "${COMMON_ARGS[@]}" \
    --cmake_extra_defines "${CMAKE_DEFINES[@]}"
  popd >/dev/null

  # Artefakte lokalisieren
  LIB_CORE="$ABI_BUILD_DIR/Release/libonnxruntime.so"
  LIB_JNI="$ABI_BUILD_DIR/Release/libonnxruntime4j_jni.so"
  [ -f "$LIB_CORE" ] || LIB_CORE="$(find "$ABI_BUILD_DIR" -type f -name 'libonnxruntime.so' -print -quit || true)"
  [ -f "$LIB_JNI"  ] || LIB_JNI="$(find "$ABI_BUILD_DIR" -type f -name 'libonnxruntime4j_jni.so' -print -quit || true)"
  if [ -z "$LIB_CORE" ] || [ -z "$LIB_JNI" ]; then
    echo "ERROR: libonnxruntime(.so) oder libonnxruntime4j_jni(.so) nicht gefunden."
    exit 1
  fi

  # Kopieren ins Projekt
  mkdir -p "$JNI_LIBS_BASE/$ABI"
  cp -f "$LIB_CORE" "$JNI_LIBS_BASE/$ABI/libonnxruntime.so"
  cp -f "$LIB_JNI"  "$JNI_LIBS_BASE/$ABI/libonnxruntime4j_jni.so"

  # Strip (wenn verfügbar)
  if [ -x "$STRIP_BIN" ]; then
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
      echo "✅ Stripping done ;-)"
  fi

  # Java-JAR einmalig kopieren
  if [ ! -f "$APP_LIBS/onnxruntime-1.22.1.jar" ]; then
    JAR_PATH="$(find "$ORT_DIR/java/build/libs" -type f -name 'onnxruntime-*.jar' \
      ! -name '*sources*.jar' ! -name '*javadoc*.jar' -print -quit || true)"
    [ -n "$JAR_PATH" ] || { echo "ERROR: onnxruntime JAR nicht gefunden."; exit 1; }
    if ! jar tf "$JAR_PATH" | grep -q 'ai/onnxruntime/OrtEnvironment.class'; then
      echo "ERROR: JAR ohne Klassen gefunden: $JAR_PATH"
      exit 1
    fi
    mkdir -p "$APP_LIBS"
    cp -f "$JAR_PATH" "$APP_LIBS/onnxruntime-1.22.1.jar"
  fi
done

echo "✅ Done."
echo "→ JAR: $APP_LIBS/onnxruntime-1.22.1.jar"
for ABI in "${ABIS[@]}"; do
  echo "→ SOs ($ABI): $JNI_LIBS_BASE/$ABI/libonnxruntime.so, libonnxruntime4j_jni.so"
done
