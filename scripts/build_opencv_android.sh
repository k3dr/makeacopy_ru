#!/bin/bash
set -Eeuo pipefail
umask 022

# Enable tracing via DEBUG/TRACE=1
if [ "${DEBUG:-0}" = "1" ] || [ "${TRACE:-0}" = "1" ]; then set -x; fi

# ===== Logging =====
VERBOSE="${VERBOSE:-0}"
info(){ if [ "$VERBOSE" = "1" ]; then echo "$@"; else >&2 echo "$@"; fi }

# ===== Config =====
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1700000000}"
export TZ=UTC LC_ALL=C LANG=C PYTHONHASHSEED=0

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR_ORIG="$SCRIPT_DIR/external/opencv"
BUILD_DIR="/tmp/opencv-build"
OPENCV_DIR="/tmp/opencv-src"

# ===== Clean + Copy =====
cd "$OPENCV_DIR_ORIG"
git clean -xfd
git checkout .

info "Copying OpenCV sources to $OPENCV_DIR..."
rm -rf "$OPENCV_DIR"; mkdir -p "$OPENCV_DIR"
cp -a "$OPENCV_DIR_ORIG/." "$OPENCV_DIR"

# ===== sed helper (GNU/macOS) =====
sedi(){ if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi }

# ===== Patch 1: deterministic ocv_output_status() =====
OPENCV_UTILS="$OPENCV_DIR/cmake/OpenCVUtils.cmake"
info "Patch ocv_output_status in $OPENCV_UTILS"
cp "$OPENCV_UTILS" "$OPENCV_UTILS.bak" || true
sedi '/^[[:space:]]*function(ocv_output_status/,/^[[:space:]]*endfunction/ d' "$OPENCV_UTILS"
cat <<'EOF' >> "$OPENCV_UTILS"

# Patched: deterministic ocv_output_status()
function(ocv_output_status msg)
  set(OPENCV_BUILD_INFO_STR "\"OpenCV 4.12.0 (reproducible build)\\n\"" CACHE INTERNAL "")
endfunction()
EOF

# ========= Patch 2: disable internal POST_BUILD strip (idempotent) =========
JNI_CMAKELISTS="$OPENCV_DIR/modules/java/jni/CMakeLists.txt"
info "Neutralize internal strip in $JNI_CMAKELISTS..."
cp "$JNI_CMAKELISTS" "$JNI_CMAKELISTS.bak" || true
if command -v perl >/dev/null 2>&1; then
  perl -0777 -pe 's/^[ \t]*add_custom_command\(TARGET[^\n]*POST_BUILD[^\n]*\n//m' -i "$JNI_CMAKELISTS"
else
  sedi '/^[[:space:]]*add_custom_command(TARGET[[:space:]]\+\${the_module}[[:space:]]\+POST_BUILD/d' "$JNI_CMAKELISTS"
fi

# ===== Patch 3: don’t auto-build Android AAR via Gradle =====
ANDROID_SDK_CMAKE="$OPENCV_DIR/modules/java/android_sdk/CMakeLists.txt"
info "Remove ALL from android_sdk target in $ANDROID_SDK_CMAKE"
cp "$ANDROID_SDK_CMAKE" "$ANDROID_SDK_CMAKE.bak" || true
perl -0777 -pe 's/add_custom_target\(([^)]*_android)\s+ALL/add_custom_target($1/g' -i "$ANDROID_SDK_CMAKE"

# ===== Patch 4: deterministic source ordering =====
JAVA_TOP="$OPENCV_DIR/modules/java/CMakeLists.txt"
JNI_TOP="$OPENCV_DIR/modules/java/jni/CMakeLists.txt"
info "Sort glob results & source lists"
cp -f "$JAVA_TOP" "$JAVA_TOP.bak" || true
cp -f "$JNI_TOP" "$JNI_TOP.bak" || true
perl -0777 -pe 's/(file\(GLOB _result[^\n]*\n)/$1  list(SORT _result)\n/s' -i "$JAVA_TOP"
awk '
  $0 ~ /^foreach\(m \${OPENCV_MODULES_BUILD}\)/ && !done {
    print "set(__mods ${OPENCV_MODULES_BUILD})";
    print "list(SORT __mods)";
    print "foreach(m ${__mods})";
    done=1; next
  }
  { print }
' "$JNI_TOP" > "${JNI_TOP}.tmp" && mv "${JNI_TOP}.tmp" "$JNI_TOP"
perl -0777 -pe 's/(file\(GLOB _result[^\n]*\n)/$1    list(SORT _result)\n/s' -i "$JNI_TOP"
perl -0777 -pe 's~(\n\s*ocv_add_library\(\$\{the_module\}.*\n)~\n# Repro: stable order of all source lists\nforeach(v handwritten_h_sources handwritten_cpp_sources generated_cpp_sources jni_sources java_sources srcs sources __srcs)\n  if(DEFINED \${v})\n    list(SORT \${v})\n  endif()\nendforeach()\n\1~s' -i "$JNI_TOP"

# ===== Patch 5: hook sorter + link map =====
info "Inject target to sort generated JNI (hpp primary, cpp fallback)"
perl -0777 -pe 's~(add_dependencies\(\$\{the_module\}\s+gen_opencv_java_source\)\s*\n)~$1# ---- Repro Patch 5 ----
set(_abi "\$\{CMAKE_ANDROID_ARCH_ABI\}")
if(NOT _abi)
  set(_abi "\$\{ANDROID_NDK_ABI_NAME\}")
endif()
set(_dump "\$\{CMAKE_CURRENT_BINARY_DIR\}/jni_state_\$\{_abi\}.txt")
set(_map  "\$\{CMAKE_CURRENT_BINARY_DIR\}/libopencv_java4_\$\{_abi\}.map")
file(WRITE "\$\{_dump\}" "Generator=\$\{CMAKE_GENERATOR\}\nCXX=\$\{CMAKE_CXX_COMPILER\}\nLinker=\$\{CMAKE_LINKER\}\n")
foreach(v handwritten_h_sources handwritten_cpp_sources generated_cpp_sources jni_sources java_sources srcs sources __srcs)
  if(DEFINED \$\{v\})
    list(SORT \$\{v\})
    file(APPEND "\$\{_dump\}" "\$\{v\}=\n")
    foreach(x IN LISTS \$\{v\})
      file(APPEND "\$\{_dump\}" "  \$\{x\}\n")
    endforeach()
  endif()
endforeach()
get_target_property(_tgt_sources \$\{the_module\} SOURCES)
if(_tgt_sources)
  list(SORT _tgt_sources)
  file(APPEND "\$\{_dump\}" "TARGET_SOURCES=\n")
  foreach(x IN LISTS _tgt_sources)
    file(APPEND "\$\{_dump\}" "  \$\{x\}\n")
  endforeach()
endif()

set(_gen_hpp "\$\{CMAKE_CURRENT_SOURCE_DIR\}/../generator/src/cpp/opencv_jni.hpp")
set(_gen_cpp "\$\{CMAKE_CURRENT_SOURCE_DIR\}/../generator/src/cpp/opencv_java.cpp")

add_custom_target(repro_sort_gen
  COMMAND "\$\{Python3_EXECUTABLE\}" "\$\{CMAKE_CURRENT_BINARY_DIR\}/repro_sort_jni.py" "\$\{_gen_hpp\}" "\$\{_gen_cpp\}"
  DEPENDS gen_opencv_java_source
  BYPRODUCTS "\$\{_gen_hpp\}"
  VERBATIM
)
add_dependencies(\$\{the_module\} repro_sort_gen)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  target_link_options(\$\{the_module\} PRIVATE -Wl,-Map,\$\{_map\})
endif()
# ---- End Repro Patch 5 ----
~s' -i "$JNI_TOP"

# ===== Error handler =====
log_error(){ echo "ERROR: $1"; echo "Check logs. Try NDK 27.3.13750724 if needed."; exit 1; }
trap 'log_error "Build failed at line $LINENO"' ERR

# ===== locate NDK =====
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    if [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
      LATEST_NDK="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n1 || true)"
      [ -n "$LATEST_NDK" ] && export ANDROID_NDK_HOME="$LATEST_NDK" && info "Found NDK at $ANDROID_NDK_HOME"
    fi
    if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk-bundle"; info "Found legacy NDK at $ANDROID_NDK_HOME"
    fi
  fi
  if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
      LATEST_NDK="$(find "$HOME/Library/Android/sdk/ndk" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n1 || true)"
      [ -n "$LATEST_NDK" ] && export ANDROID_NDK_HOME="$LATEST_NDK" && info "Found NDK at $ANDROID_NDK_HOME"
    elif [ -d "$HOME/Library/Android/sdk/ndk-bundle" ]; then
      export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk-bundle"; info "Found legacy NDK at $ANDROID_NDK_HOME"
    else
      echo "Error: ANDROID_NDK_HOME not set and NDK not found." >&2; exit 1
    fi
  fi
fi

# ===== NDK/CMake info =====
info "Using Android NDK: $ANDROID_NDK_HOME"
NDK_VERSION="$(basename "$ANDROID_NDK_HOME")"
RECOMMENDED_VERSION="27.3.13750724"
info "Detected NDK: $NDK_VERSION"
[ "$NDK_VERSION" != "$RECOMMENDED_VERSION" ] && info "NDK differs from recommended $RECOMMENDED_VERSION."

# ===== validate sources =====
[ -d "$OPENCV_DIR" ] || { echo "Error: $OPENCV_DIR not found"; exit 1; }

# ===== prepare build dir =====
rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR/lib"

# ===== locate SDK (optional) =====
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
  fi
  [ -n "${ANDROID_SDK_ROOT:-}" ] && info "Found Android SDK at $ANDROID_SDK_ROOT"
fi

# ===== pick CMake =====
if [ -z "${OPENCV_CMAKE:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    LATEST_CMAKE_DIR="$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d -name "[0-9]*" | sort -Vr | head -n1 || true)"
    if [ -n "$LATEST_CMAKE_DIR" ] && [ -x "$LATEST_CMAKE_DIR/bin/cmake" ]; then
      OPENCV_CMAKE="$LATEST_CMAKE_DIR/bin/cmake"
    fi
  fi
fi
[ -z "${OPENCV_CMAKE:-}" ] && OPENCV_CMAKE="$(command -v cmake || true)"
[ -n "${OPENCV_CMAKE:-}" ] || { echo "ERROR: CMake not found (set OPENCV_CMAKE)"; exit 1; }
OPENCV_CMAKE_VER="$("$OPENCV_CMAKE" --version | awk '/version/{print $3; exit}')"
info "OpenCV CMake: $OPENCV_CMAKE (v $OPENCV_CMAKE_VER)"
if [ -n "${OPENCV_CMAKE_REQ:-}" ] && [ "$OPENCV_CMAKE_VER" != "$OPENCV_CMAKE_REQ" ]; then
  echo "ERROR: CMake $OPENCV_CMAKE_VER != required $OPENCV_CMAKE_REQ" >&2; exit 1
fi

# ===== toolchain dir detect =====
PREBUILT_BASE="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
detect_toolchain_dir(){
  local host_os host_arch
  case "$(uname -s)" in Darwin) host_os=darwin;; Linux) host_os=linux;; *) host_os=linux;; esac
  case "$(uname -m)" in arm64|aarch64) host_arch=aarch64;; x86_64|amd64) host_arch=x86_64;; *) host_arch=x86_64;; esac
  for cand in "$PREBUILT_BASE/${host_os}-${host_arch}" "$PREBUILT_BASE/${host_os}-aarch64" "$PREBUILT_BASE/${host_os}-arm64" "$PREBUILT_BASE/${host_os}-x86_64"; do
    [ -x "$cand/bin/llvm-ar" ] && { echo "$cand"; return 0; }
  done
  local any; any="$(find "$PREBUILT_BASE" -maxdepth 1 -type d -name "${host_os}-*" 2>/dev/null | while read -r d; do [ -x "$d/bin/llvm-ar" ] && { echo "$d"; break; } done)"
  [ -n "$any" ] && { echo "$any"; return 0; } || return 1
}
TOOLCHAIN_DIR="$(detect_toolchain_dir || true)"
[ -n "$TOOLCHAIN_DIR" ] || { echo "ERROR: llvm toolchain dir not found under $PREBUILT_BASE"; exit 1; }
info "Using NDK toolchain: $TOOLCHAIN_DIR"

# ===== Build-Log =====
BUILD_LOG="$BUILD_DIR/opencv_build.log"
info "Build log: $BUILD_LOG"
echo "$(date): Starting OpenCV build (NDK $NDK_VERSION)" > "$BUILD_LOG"

# ===== per-ABI build =====
build_for_arch(){
  local arch="$1"
  local arch_build_dir="${BUILD_DIR}_${arch}"
  rm -rf "$arch_build_dir"; mkdir -p "$arch_build_dir"; cd "$arch_build_dir"

  local arch_log="$arch_build_dir/opencv_build_$arch.log"
  echo "$(date): Start OpenCV build for $arch" > "$arch_log"

  export ZERO_AR_DATE=1
  local PY3_BIN="${PY3_BIN:-$(command -v python3 || true)}"
  info "Python: $($PY3_BIN --version 2>&1 || echo unknown)"

  mkdir -p "$arch_build_dir/3rdparty/lib/$arch" "$arch_build_dir/lib/$arch"

  local AR_BIN="$TOOLCHAIN_DIR/bin/llvm-ar"
  local RANLIB_BIN="$TOOLCHAIN_DIR/bin/llvm-ranlib"
  [ -x "$AR_BIN" ] && [ -x "$RANLIB_BIN" ] || { echo "ERROR: missing llvm-ar/ranlib"; exit 1; }

  local BUILD_GENERATOR="${BUILD_GENERATOR:-Unix Makefiles}"
  info "Configure CMake ($BUILD_GENERATOR) for $arch"
  "$OPENCV_CMAKE" -G "$BUILD_GENERATOR" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$arch" \
    -DANDROID_NATIVE_API_LEVEL=21 \
    -DCMAKE_AR="$AR_BIN" -DCMAKE_RANLIB="$RANLIB_BIN" \
    -DPython3_EXECUTABLE="$PY3_BIN" \
    -DBUILD_opencv_python3=OFF -DBUILD_opencv_python_bindings_generator=OFF \
    -DCMAKE_C_FLAGS="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=. " \
    -DCMAKE_CXX_FLAGS="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=. " \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS_RELEASE="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=." \
    -DCMAKE_CXX_FLAGS_RELEASE="-g0 -fdebug-prefix-map=$OPENCV_DIR=. -ffile-prefix-map=$OPENCV_DIR=." \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,--build-id=none" \
    -DCMAKE_INSTALL_PREFIX="/__repro" \
    -DBUILD_ANDROID_PROJECTS=ON \
    -DBUILD_SHARED_LIBS=ON -DBUILD_STATIC_LIBS=OFF \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF -DBUILD_ANDROID_EXAMPLES=OFF \
    -DBUILD_JAVA=ON -DBUILD_opencv_java=ON \
    -DBUILD_opencv_imgproc=ON -DBUILD_opencv_imgcodecs=ON -DBUILD_opencv_video=ON -DBUILD_opencv_videoio=ON \
    -DBUILD_opencv_flann=OFF -DBUILD_opencv_calib3d=OFF -DBUILD_opencv_features2d=OFF -DBUILD_opencv_objdetect=OFF \
    -DBUILD_opencv_dnn=OFF -DBUILD_opencv_gapi=OFF -DBUILD_opencv_ml=OFF -DBUILD_opencv_highgui=OFF \
    -DBUILD_opencv_photo=OFF -DBUILD_opencv_stitching=OFF \
    -DWITH_OPENCL=OFF -DWITH_IPP=OFF \
    -DCMAKE_CXX_STANDARD=11 -DCMAKE_CXX_STANDARD_REQUIRED=ON \
    -DCMAKE_C_ARCHIVE_CREATE="<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_C_ARCHIVE_FINISH=":" \
    -DCMAKE_CXX_ARCHIVE_CREATE="<CMAKE_AR> qcD <TARGET> <LINK_FLAGS> <OBJECTS>" \
    -DCMAKE_CXX_ARCHIVE_FINISH=":" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$OPENCV_DIR" >> "$arch_log" 2>&1 || log_error "CMake config for $arch failed ($arch_log)"

  # CMakeCache snippet
  if [ -f "$arch_build_dir/CMakeCache.txt" ]; then
    { echo "===== CMakeCache ($arch): key entries =====";
      grep -E '^(CMAKE_(GENERATOR|BUILD_TYPE|CXX_COMPILER|C_COMPILER)|ANDROID_|OPENCV_|Python3_EXECUTABLE):' "$arch_build_dir/CMakeCache.txt" | sed -n '1,120p';
      echo "===== END CMakeCache ($arch) ====="; } >> "$arch_log" 2>&1 || true
  fi

  # Python sorter (robust to wrapped lines; prototype before impl)
  SORTER_PATH="$arch_build_dir/modules/java/jni/repro_sort_jni_inl.py"
  cat > "$SORTER_PATH" << 'PY'
#!/usr/bin/env python3
import io, os, re, sys, glob

# Allow up to ~256 chars (with newlines) between JNIEXPORT and JNICALL, then capture Java_* symbol
jni_name = re.compile(r'^JNIEXPORT[\s\S]{0,256}?JNICALL\s+(Java_[A-Za-z0-9_]+)', re.M)

def read(p):
    with io.open(p, 'r', encoding='utf-8', errors='ignore') as f:
        return f.read()

def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(s)

def split_blocks(txt):
    parts = re.split(r'^(?=JNIEXPORT\b)', txt, flags=re.M)
    return parts[0], parts[1:]  # header, blocks

def key_of(block: str):
    # Use robust name extraction across wrapped lines
    m = jni_name.search(block)
    base = (m.group(1).lower() if m else block.splitlines(True)[0].strip().lower())
    # Prototype if first ';' precedes first '{'
    semi = block.find(';')
    brace = block.find('{')
    is_proto = (semi != -1 and (brace == -1 or semi < brace))
    # Stable tiebreaker
    first = block.splitlines(True)[0]
    return (base, 0 if is_proto else 1, first.lower())

def sort_inl(p):
    txt = read(p)
    if 'JNIEXPORT' not in txt:
        return False
    head, blocks = split_blocks(txt)
    if len(blocks) <= 1:
        return False
    order = sorted(range(len(blocks)), key=lambda i: key_of(blocks[i]))
    out = head + ''.join(blocks[i] for i in order)
    if out != txt:
        write(p, out)
        return True
    return False

def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    changed = 0
    for p in sorted(glob.glob(os.path.join(base, "*.inl.hpp"))):
        try:
            if sort_inl(p):
                changed += 1
                print(f"sorted: {os.path.basename(p)}")
        except Exception:
            pass
    print(f"Done. Files changed: {changed}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
PY
  chmod +x "$SORTER_PATH"

  # Shim expected by CMake target repro_sort_gen
  SHIM_PATH="$arch_build_dir/modules/java/jni/repro_sort_jni.py"
  cat > "$SHIM_PATH" << 'PY'
#!/usr/bin/env python3
import io, os, re, sys, glob

jni_name = re.compile(r'^JNIEXPORT[\s\S]{0,256}?JNICALL\s+(Java_[A-Za-z0-9_]+)', re.M)

def read(p):
    with io.open(p, 'r', encoding='utf-8', errors='ignore') as f:
        return f.read()

def write(p, s):
    with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(s)

def split_blocks(txt):
    parts = re.split(r'^(?=JNIEXPORT\b)', txt, flags=re.M)
    return parts[0], parts[1:]

def key_of(block: str):
    m = jni_name.search(block)
    base = (m.group(1).lower() if m else block.splitlines(True)[0].strip().lower())
    semi = block.find(';'); brace = block.find('{')
    is_proto = (semi != -1 and (brace == -1 or semi < brace))
    first = block.splitlines(True)[0]
    return (base, 0 if is_proto else 1, first.lower())

def sort_blocks_file(p):
    txt = read(p)
    if 'JNIEXPORT' not in txt:
        return False
    head, blocks = split_blocks(txt)
    if len(blocks) <= 1:
        return False
    order = sorted(range(len(blocks)), key=lambda i: key_of(blocks[i]))
    out = head + ''.join(blocks[i] for i in order)
    if out != txt:
        write(p, out)
        return True
    return False

def main():
    # 1) Try explicit args first (e.g., opencv_jni.hpp / opencv_java.cpp)
    for p in sys.argv[1:]:
        try:
            if os.path.exists(p) and 'JNIEXPORT' in read(p):
                sort_blocks_file(p)
        except Exception:
            pass
    # 2) Always sort the generated *.inl.hpp in build tree
    here = os.path.dirname(os.path.abspath(__file__))
    inl_dir = os.path.normpath(os.path.join(here, "..", "..", "java_bindings_generator", "gen", "cpp"))
    changed = 0
    for p in sorted(glob.glob(os.path.join(inl_dir, "*.inl.hpp"))):
        try:
            if sort_blocks_file(p):
                changed += 1
                print(f"sorted: {os.path.basename(p)}")
        except Exception:
            pass
    print(f"Done. Files changed: {changed}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
PY
  chmod +x "$SHIM_PATH"
  # --- End shim ---

  # Generate sources
  info "Run gen_opencv_java_source for $arch"
  "$OPENCV_CMAKE" --build . --target gen_opencv_java_source >> "$arch_log" 2>&1 || {
    echo "ERROR: gen_opencv_java_source failed ($arch)"; tail -n 80 "$arch_log" >&2 || true; cd "$SCRIPT_DIR"; return 1; }

  # Sort all *.inl.hpp in build tree + short log
  INL_DIR="$arch_build_dir/modules/java_bindings_generator/gen/cpp"
  info "Sort JNIEXPORT in *.inl.hpp ($INL_DIR)"
  "$SORTER_PATH" "$INL_DIR" >> "$arch_log" 2>&1 || true

  # Quick dump
  one_inl="$(ls -1 "$INL_DIR"/*.inl.hpp 2>/dev/null | head -n1 || true)"
  { echo "===== HEAD JNIEXPORT ($arch) from $(basename "$one_inl") ====="
    [ -n "$one_inl" ] && grep -n '^JNIEXPORT' "$one_inl" | sed -n '1,20p' || true
    echo "===== END JNIEXPORT ====="; } >> "$arch_log" 2>&1 || true

  # Build (single-threaded for reproducibility)
  info "Build OpenCV for $arch (-j1)"
  if [ "$BUILD_GENERATOR" = "Ninja" ]; then
    ninja -j1 >> "$arch_log" 2>&1 || { echo "Build failed ($arch)"; tail -n 80 "$arch_log" >&2; cd "$SCRIPT_DIR"; return 1; }
  else
    make -j1 >> "$arch_log" 2>&1 || { echo "Build failed ($arch)"; tail -n 80 "$arch_log" >&2; cd "$SCRIPT_DIR"; return 1; }
  fi

  # Map + dumps
  local JNI_DIR="$arch_build_dir/modules/java/jni"
  local STATE_DUMP="$(ls "$JNI_DIR"/jni_state_*.txt 2>/dev/null | head -n1 || true)"
  local MAP_DUMP="$(ls "$JNI_DIR"/libopencv_java4_*.map 2>/dev/null | head -n1 || true)"
  if [ -n "$STATE_DUMP" ]; then
    echo "===== DUMP: $STATE_DUMP ====="; sed -n '1,160p' "$STATE_DUMP" || true; echo "===== END ====="
  fi
  if [ -n "$MAP_DUMP" ]; then
    echo "===== DUMP: $MAP_DUMP ====="
    grep -nE 'CamShift|meanShift|OpticalFlow|BackgroundSubtractor(MOG2|KNN)' "$MAP_DUMP" | head -n 400 || true
    echo "===== END ====="
  fi

  # Stage libopencv_java4.so
  local SRC_LIB_DIR="$arch_build_dir/lib/$arch"; mkdir -p "$SRC_LIB_DIR"
  local JNI_SO; JNI_SO="$(find "$arch_build_dir" -path "*/jni/$arch/libopencv_java4.so" -print -quit 2>/dev/null || true)"
  [ -n "$JNI_SO" ] && cp -f "$JNI_SO" "$SRC_LIB_DIR/" && info "Staged libopencv_java4.so ($arch)" || info "WARN: no libopencv_java4.so for $arch"

  local OUT_DIR="$BUILD_DIR/lib/$arch"
  rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"
  shopt -s nullglob; cp -f "$SRC_LIB_DIR"/*.so "$OUT_DIR/" 2>/dev/null || true; shopt -u nullglob
  ls -1 "$OUT_DIR"/*.so >/dev/null 2>&1 || { echo "ERROR: no .so staged ($arch)"; tail -n 50 "$arch_log" >&2; cd "$SCRIPT_DIR"; return 1; }

  # Normalize timestamps
  if touch -d "@$SOURCE_DATE_EPOCH" / >/dev/null 2>&1; then
    find "$OUT_DIR" -type f -name "*.so" -exec touch -d "@$SOURCE_DATE_EPOCH" {} +
  fi

  # Hashes
  if command -v shasum >/dev/null 2>&1; then (cd "$OUT_DIR" && shasum -a 256 *.so) >> "$arch_log" 2>&1 || true
  elif command -v sha256sum >/dev/null 2>&1; then (cd "$OUT_DIR" && sha256sum *.so) >> "$arch_log" 2>&1 || true; fi

  # Strip (safe: unneeded) + remove noisy sections
  local STRIP="$TOOLCHAIN_DIR/bin/llvm-strip"
  if [ -x "$STRIP" ]; then
    info "Strip $arch libs"
    find "$OUT_DIR" -name "*.so" -exec "$STRIP" \
      --strip-unneeded \
      --remove-section=.comment \
      --remove-section=.note \
      --remove-section=.note.gnu.build-id \
      --remove-section=.note.gnu.property \
      --remove-section=.note.ABI-tag \
      --remove-section=.eh_frame_hdr \
      --remove-section=.eh_frame \
      {} \;
  fi

  cd "$SCRIPT_DIR"
  info "Done $arch"
  return 0
}

# ===== Build loop =====
info "Building OpenCV for ABIs: [$ABIS]"
BUILD_FAILED=0
for ARCH in $ABIS; do build_for_arch "$ARCH" || BUILD_FAILED=1; done

if [ $BUILD_FAILED -ne 0 ]; then echo "❌ Error: some builds failed"; exit 1; fi
info "✅ OpenCV for Android built successfully."

# ===== Summary =====
if command -v sha256sum >/dev/null 2>&1; then H="sha256sum"; else H="shasum -a 256"; fi
echo "===== SHA256 summary (libopencv_java4.so) ====="
for ARCH in $ABIS; do
  OUT_DIR="$BUILD_DIR/lib/$ARCH"
  if [ -f "$OUT_DIR/libopencv_java4.so" ]; then $H "$OUT_DIR/libopencv_java4.so" || true
  else echo "$ARCH: libopencv_java4.so not found"; fi
done
echo "===== END SHA256 summary ====="

# Final head (source-tree) — usually empty for .hpp now; harmless if missing
GEN_DIR_SRC="$OPENCV_DIR/modules/java/generator/src/cpp"
GEN_HPP_PATH="$GEN_DIR_SRC/opencv_jni.hpp"
GEN_CPP_PATH="$GEN_DIR_SRC/opencv_java.cpp"
echo "===== HEAD of final sorted JNIEXPORT (source tree) ====="
[ -f "$GEN_HPP_PATH" ] && grep -n "^JNIEXPORT" "$GEN_HPP_PATH" | sed -n '1,20p' || true
if [ -f "$GEN_CPP_PATH" ]; then
  echo "--- (cpp fallback) ---"
  grep -n "^JNIEXPORT" "$GEN_CPP_PATH" | sed -n '1,10p' || true
fi
echo "===== END ====="

info "Build log: $BUILD_LOG"
exit 0
