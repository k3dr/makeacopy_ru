#!/bin/bash
set -Eeuo pipefail
umask 022

# ========= Logging =========
VERBOSE="${VERBOSE:-0}"
info() {
  if [ "$VERBOSE" = "1" ]; then echo "$@"; else >&2 echo "$@"; fi
}

# ========= Konfiguration =========
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
info "ABIS: [$ABIS]"

export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1700000000}"
export TZ=UTC
export LC_ALL=C
export LANG=C
export PYTHONHASHSEED=0

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR_ORIG="$SCRIPT_DIR/external/opencv"
BUILD_DIR="/tmp/opencv-build"
OPENCV_DIR="/tmp/opencv-src"

# ========= Clean + Copy =========
cd "$OPENCV_DIR_ORIG"
git clean -xfd
git checkout .

info "Copying OpenCV sources to $OPENCV_DIR..."
rm -rf "$OPENCV_DIR"
mkdir -p "$OPENCV_DIR"
cp -a "$OPENCV_DIR_ORIG/." "$OPENCV_DIR"

# ========= sed helper (macOS/GNU) =========
sedi() {
  if sed --version >/dev/null 2>&1; then
    sed -i "$@"
  else
    sed -i '' "$@"
  fi
}

# ========= Patch 1: deterministisches Build-Info-String =========
OPENCV_UTILS="$OPENCV_DIR/cmake/OpenCVUtils.cmake"
info "Patching ocv_output_status() in $OPENCV_UTILS..."
cp "$OPENCV_UTILS" "$OPENCV_UTILS.bak"
# Funktion entfernen
sedi '/^[[:space:]]*function(ocv_output_status/,/^[[:space:]]*endfunction/ d' "$OPENCV_UTILS"
# Ersatzfunktion anhängen
cat <<'EOF' >> "$OPENCV_UTILS"

# Patched: deterministic ocv_output_status()
function(ocv_output_status msg)
  set(OPENCV_BUILD_INFO_STR "\"OpenCV 4.12.0 (reproducible build)\\n\"" CACHE INTERNAL "")
endfunction()
EOF
info "ocv_output_status() patched."

# ========= Patch 2: internes Strip von libopencv_java4.so deaktivieren =========
JNI_CMAKELISTS="$OPENCV_DIR/modules/java/jni/CMakeLists.txt"
info "Patching internal strip in $JNI_CMAKELISTS..."
cp "$JNI_CMAKELISTS" "$JNI_CMAKELISTS.bak"

if command -v perl >/dev/null 2>&1; then
  # Entweder: komplette Zeile löschen (sauberste Lösung)
  perl -0777 -pe 's/^[ \t]*add_custom_command\(TARGET[^\n]*POST_BUILD[^\n]*\n//m' -i "$JNI_CMAKELISTS"
  # Alternativ (wenn du lieber einen Kommentar hinterlassen willst):
  # perl -0777 -pe 's/^[ \t]*add_custom_command\(TARGET[^\n]*POST_BUILD[^\n]*\n/# removed by build script: strip disabled\n/m' -i "$JNI_CMAKELISTS"
else
  # Fallback nur mit sed (BSD/GNU): Zeile entfernen
  sedi '/^[[:space:]]*add_custom_command(TARGET[[:space:]]\+\${the_module}[[:space:]]\+POST_BUILD/d' "$JNI_CMAKELISTS"
fi
info "Removed POST_BUILD strip of libopencv_java4.so."


# Verifizieren
if grep -q 'POST_BUILD' "$JNI_CMAKELISTS"; then
  info "WARN: POST_BUILD strip still present somewhere in $JNI_CMAKELISTS (check manuell)."
else
  info "Removed POST_BUILD strip of libopencv_java4.so."
fi

# ========= Patch 3: Gradle-AAR nicht standardmäßig bauen (remove `ALL`) =========
ANDROID_SDK_CMAKE="$OPENCV_DIR/modules/java/android_sdk/CMakeLists.txt"
info "Patching Gradle target (remove ALL) in $ANDROID_SDK_CMAKE..."
cp "$ANDROID_SDK_CMAKE" "$ANDROID_SDK_CMAKE.bak"
if command -v perl >/dev/null 2>&1; then
  perl -0777 -pe 's/add_custom_target\(([^)]*_android)\s+ALL/add_custom_target($1/g' -i "$ANDROID_SDK_CMAKE"
else
  awk '{
    if ($0 ~ /add_custom_target\(.+_android[[:space:]]+ALL/) sub(/_android[[:space:]]+ALL/, "_android")
    print
  }' "$ANDROID_SDK_CMAKE" > "${ANDROID_SDK_CMAKE}.tmp" && mv "${ANDROID_SDK_CMAKE}.tmp" "$ANDROID_SDK_CMAKE"
fi
if grep -qE 'add_custom_target\(.+_android[[:space:]]+ALL' "$ANDROID_SDK_CMAKE"; then
  echo "ERROR: Failed to remove 'ALL' from add_custom_target in $ANDROID_SDK_CMAKE" >&2
  exit 1
fi
info "Gradle AAR target no longer built by default."

# ========= Patch 4: deterministische Reihenfolge für Java/JNI-Quellen =========
JAVA_TOP="$OPENCV_DIR/modules/java/CMakeLists.txt"
JNI_TOP="$OPENCV_DIR/modules/java/jni/CMakeLists.txt"

info "Patching deterministic ordering in $JAVA_TOP and $JNI_TOP ..."
cp -f "$JAVA_TOP" "$JAVA_TOP.bak" || true
cp -f "$JNI_TOP" "$JNI_TOP.bak" || true

# (A) modules/java/CMakeLists.txt
# A1: Nach jedem file(GLOB _result ...) die Ergebnisse sortieren
perl -0777 -pe 's/(file\(GLOB _result[^\n]*\n)/$1  list(SORT _result)\n/s' -i "$JAVA_TOP"

# (B) modules/java/jni/CMakeLists.txt
# B1: Modulliste sortieren, bevor darüber iteriert wird
awk '
  $0 ~ /^foreach\(m \${OPENCV_MODULES_BUILD}\)/ && !done {
    print "set(__mods ${OPENCV_MODULES_BUILD})";
    print "list(SORT __mods)";
    print "foreach(m ${__mods})";
    done=1; next
  }
  { print }
' "$JNI_TOP" > "${JNI_TOP}.tmp" && mv "${JNI_TOP}.tmp" "$JNI_TOP"

# B2: Auch hier: GLOB-Ergebnisse sortieren
perl -0777 -pe 's/(file\(GLOB _result[^\n]*\n)/$1    list(SORT _result)\n/s' -i "$JNI_TOP"

# B3: Direkt vor dem JNI-Target die gesammelten Listen (falls vorhanden) sortieren
perl -0777 -pe 's~(\n\s*ocv_add_library\(\$\{the_module\}.*\n)~\n# Repro: stabile Reihenfolge aller Quelllisten erzwingen\nforeach(v handwritten_h_sources handwritten_cpp_sources generated_cpp_sources jni_sources java_sources srcs sources __srcs)\n  if(DEFINED \${v})\n    list(SORT \${v})\n  endif()\nendforeach()\n\1~s' -i "$JNI_TOP"

info "Deterministic ordering patches applied."


# ========= Error-Handler =========
log_error() {
  echo "ERROR: $1"
  echo "Please check the full build log for details."
  echo "If issues persist, try Android NDK 27.3.13750724."
  exit 1
}
trap 'log_error "Build failed at line $LINENO"' ERR

# ===== locate NDK =====
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
      LATEST_NDK="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)"
      if [ -n "$LATEST_NDK" ]; then export ANDROID_NDK_HOME="$LATEST_NDK"; info "Found NDK at $ANDROID_NDK_HOME"; fi
    fi
    if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk-bundle"; info "Found NDK at $ANDROID_NDK_HOME"
    fi
  fi
  if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
      LATEST_NDK="$(find "$HOME/Library/Android/sdk/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)"
      if [ -n "$LATEST_NDK" ]; then export ANDROID_NDK_HOME="$LATEST_NDK"; info "Found NDK at $ANDROID_NDK_HOME"; fi
    elif [ -d "$HOME/Library/Android/sdk/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk-bundle"; info "Found NDK at $ANDROID_NDK_HOME"
    else
      echo "Error: ANDROID_NDK_HOME is not set and NDK could not be found automatically." >&2
      exit 1
    fi
  fi
fi

# ========= NDK-Version loggen =========
info "Using Android NDK at: $ANDROID_NDK_HOME"
NDK_VERSION="$(basename "$ANDROID_NDK_HOME")"
RECOMMENDED_VERSION="27.3.13750724"
info "Detected NDK version: $NDK_VERSION"
if [[ "$NDK_VERSION" != "$RECOMMENDED_VERSION" ]]; then
  info "NDK version $NDK_VERSION differs from recommended $RECOMMENDED_VERSION."
fi

# ===== validate sources =====
info "OpenCV source directory: $OPENCV_DIR"
if [ ! -d "$OPENCV_DIR" ]; then
  echo "Error: OpenCV source not found at $OPENCV_DIR" >&2
  exit 1
fi

# ===== prepare build dir =====
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/lib"

# ===== locate SDK (optional) =====
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  info "ANDROID_SDK_ROOT is not set. Trying to find it automatically..."
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"; info "Found Android SDK at $ANDROID_SDK_ROOT"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"; info "Found Android SDK at $ANDROID_SDK_ROOT"
  fi
fi

# ===== pick CMake =====
if [ -z "${OPENCV_CMAKE:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    LATEST_CMAKE_DIR="$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n 1 || true)"
    if [ -n "$LATEST_CMAKE_DIR" ] && [ -x "$LATEST_CMAKE_DIR/bin/cmake" ]; then
      OPENCV_CMAKE="$LATEST_CMAKE_DIR/bin/cmake"; info "Found SDK CMake for OpenCV at $OPENCV_CMAKE"
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

# ===== toolchain dir detect =====
PREBUILT_BASE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
detect_toolchain_dir() {
  local host_os host_arch
  case "$(uname -s)" in
    Darwin) host_os=darwin ;;
    Linux)  host_os=linux  ;;
    *)      host_os=linux  ;;
  esac
  case "$(uname -m)" in
    arm64|aarch64) host_arch=aarch64 ;;
    x86_64|amd64)  host_arch=x86_64  ;;
    *)             host_arch=x86_64  ;;
  esac
  for cand in \
      "$PREBUILT_BASE/${host_os}-${host_arch}" \
      "$PREBUILT_BASE/${host_os}-aarch64" \
      "$PREBUILT_BASE/${host_os}-arm64" \
      "$PREBUILT_BASE/${host_os}-x86_64"
  do
    if [ -x "$cand/bin/llvm-ar" ]; then echo "$cand"; return 0; fi
  done
  local any
  any="$(find "$PREBUILT_BASE" -maxdepth 1 -type d -name "${host_os}-*" 2>/dev/null | while read -r d; do
    [ -x "$d/bin/llvm-ar" ] && echo "$d" && break
  done)"
  if [ -n "$any" ]; then echo "$any"; return 0; fi
  return 1
}
TOOLCHAIN_DIR="$(detect_toolchain_dir || true)"
if [ -z "$TOOLCHAIN_DIR" ]; then
  echo "ERROR: Could not locate NDK toolchain dir with llvm-ar under $PREBUILT_BASE" >&2
  exit 1
fi
info "Using NDK toolchain: $TOOLCHAIN_DIR"

# ========= Build-Log =========
BUILD_LOG="$BUILD_DIR/opencv_build.log"
info "Build log: $BUILD_LOG"
echo "$(date): Starting OpenCV build" > "$BUILD_LOG"
echo "NDK version: $NDK_VERSION" >> "$BUILD_LOG"

# ===== per-ABI build =====
build_for_arch() {
  local arch="$1"
  local arch_build_dir="${BUILD_DIR}_${arch}"
  rm -rf "$arch_build_dir"
  mkdir -p "$arch_build_dir"
  cd "$arch_build_dir"

  local arch_log="$arch_build_dir/opencv_build_$arch.log"
  echo "$(date): Starting OpenCV build for $arch" > "$arch_log"

  export ZERO_AR_DATE=1
  local PY3_BIN="${PY3_BIN:-$(command -v python3)}"
  info "Python: $($PY3_BIN --version 2>&1)"
  info "Configuring CMake for $arch..."

  local AR_BIN="$TOOLCHAIN_DIR/bin/llvm-ar"
  local RANLIB_BIN="$TOOLCHAIN_DIR/bin/llvm-ranlib"
  if [ ! -x "$AR_BIN" ] || [ ! -x "$RANLIB_BIN" ]; then
    echo "ERROR: llvm-ar/llvm-ranlib not found under $TOOLCHAIN_DIR/bin" >&2
    exit 1
  fi

  mkdir -p "$arch_build_dir/3rdparty/lib/$arch" "$arch_build_dir/lib/$arch"

  "$OPENCV_CMAKE" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$arch" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DCMAKE_AR="$AR_BIN" \
    -DCMAKE_RANLIB="$RANLIB_BIN" \
    -DPYTHON_DEFAULT_EXECUTABLE="$PY3_BIN" \
    -DPython3_EXECUTABLE="$PY3_BIN" \
    -DBUILD_opencv_python3=OFF \
    -DBUILD_opencv_python_bindings_generator=OFF \
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
  fi

  # Gradle Kotlin jvmTarget safety (falls Gradle doch angerufen wird)
  echo "
  tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
      kotlinOptions { jvmTarget = '17' }
  }
  " | tee -a "$arch_build_dir/opencv_android/opencv/build.gradle" >/dev/null

  info "Building OpenCV for $arch (single-threaded)..."
  if ! ninja -j1 >> "$arch_log" 2>&1; then
    echo "Build failed for $arch" >&2
    tail -n 50 "$arch_log" >&2
    cd "$SCRIPT_DIR"
    return 1
  fi

  # ----- Deterministisches Staging -----
  local SRC_LIB_DIR="$arch_build_dir/lib/$arch"
  mkdir -p "$SRC_LIB_DIR"

  # libopencv_java4.so aus dem JNI-Output holen
  local JNI_SO="$arch_build_dir/jni/$arch/libopencv_java4.so"
  if [ -f "$JNI_SO" ]; then
    cp -f "$JNI_SO" "$SRC_LIB_DIR/"
    info "Staged libopencv_java4.so from JNI output -> $SRC_LIB_DIR"
  else
    info "WARN: libopencv_java4.so not found under $arch_build_dir/jni/$arch (continuing)"
  fi

  local OUT_DIR="$BUILD_DIR/lib/$arch"
  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR"
  shopt -s nullglob
  cp -f "$SRC_LIB_DIR"/*.so "$OUT_DIR/" 2>/dev/null || true
  shopt -u nullglob

  if ! ls -1 "$OUT_DIR"/*.so >/dev/null 2>&1; then
    echo "ERROR: No .so artifacts staged for $arch (expected in $SRC_LIB_DIR)" >&2
    tail -n 50 "$arch_log" >&2
    cd "$SCRIPT_DIR"
    return 1
  fi

  # Optionale Timestamps (nur GNU touch unterstützt -d)
  if touch -d "@$SOURCE_DATE_EPOCH" / >/dev/null 2>&1; then
    find "$OUT_DIR" -type f -name "*.so" -exec touch -d "@$SOURCE_DATE_EPOCH" {} +
  else
    info "Non-GNU touch detected, skipping timestamp normalization."
  fi

  # Hashes loggen
  if command -v shasum >/dev/null 2>&1; then
    ( cd "$OUT_DIR" && shasum -a 256 *.so ) >> "$arch_log" 2>&1 || true
  elif command -v sha256sum >/dev/null 2>&1; then
    ( cd "$OUT_DIR" && sha256sum *.so ) >> "$arch_log" 2>&1 || true
  fi

  # einziges Strip: unser llvm-strip
  local STRIP="$TOOLCHAIN_DIR/bin/llvm-strip"
  if [ -x "$STRIP" ]; then
    info "Stripping debug and metadata sections from $arch libraries..."
    find "$OUT_DIR" -name "*.so" -exec "$STRIP" \
      --strip-all \
      --remove-section=.comment \
      --remove-section=.note \
      --remove-section=.note.gnu.build-id \
      --remove-section=.note.gnu.property \
      --remove-section=.note.ABI-tag \
      {} \;
    info "Stripped and cleaned $arch libraries for reproducibility."
  else
    echo "⚠️  Warning: Strip tool not found at $STRIP. Skipping stripping for $arch."
  fi
  # ----- Ende Staging -----

  cd "$SCRIPT_DIR"
  info "OpenCV for $arch built successfully."
  return 0
}

# ========= Build loop =========
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
