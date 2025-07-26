#!/bin/bash
set -euo pipefail

echo "🔧 Preparing OpenCV native libraries for the app..."

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_BASE="$SCRIPT_DIR/external"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"
ARCHS=(arm64-v8a armeabi-v7a x86 x86_64)

# Clean existing jniLibs directory
echo "🧹 Cleaning jniLibs directory..."
rm -rf "$JNILIBS_DIR"
mkdir -p "$JNILIBS_DIR"

MISSING=0

copy_libs() {
  local arch=$1
  local possible_dirs=(
    "$BUILD_BASE/opencv-build_$arch/lib/$arch"
    "$BUILD_BASE/opencv-build/lib/$arch"
  )
  local target_dir="$JNILIBS_DIR/$arch"
  mkdir -p "$target_dir"

  local found=0

  echo "📁 Searching libraries for $arch..."

  for src_dir in "${possible_dirs[@]}"; do
    if [[ -d "$src_dir" ]]; then
      for lib in "$src_dir"/*.so; do
        if [[ -f "$lib" ]]; then
          cp -f "$lib" "$target_dir/"
          echo "✅ Copied $(basename "$lib") from $src_dir"
          found=1
        fi
      done
    fi
  done

  if [[ $found -eq 0 ]]; then
    echo "⚠️  No .so files found for $arch in expected locations"
    MISSING=1
  fi
}

for ARCH in "${ARCHS[@]}"; do
  copy_libs "$ARCH"
done

if [[ $MISSING -eq 1 ]]; then
  echo "⚠️  Some architectures are missing native libraries. Please check your OpenCV build outputs."
  exit 1
fi

echo "🎉 All OpenCV native libraries were copied successfully to: $JNILIBS_DIR"
exit 0
