#!/bin/bash
set -e

echo "Preparing OpenCV libraries for the app..."

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_BASE="$SCRIPT_DIR/external"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

# Create jniLibs directory structure
mkdir -p "$JNILIBS_DIR/arm64-v8a" "$JNILIBS_DIR/armeabi-v7a" "$JNILIBS_DIR/x86" "$JNILIBS_DIR/x86_64"

copy_libs() {
  local arch=$1
  local build_dir="$BUILD_BASE/opencv-build_$arch/lib/$arch"
  if [ -d "$build_dir" ]; then
    if compgen -G "$build_dir/*.so" > /dev/null; then
      cp -f "$build_dir/"*.so "$JNILIBS_DIR/$arch/"
      echo "Copied $arch shared libraries (.so)"
    else
      echo "Warning: No libraries found for $arch"
    fi
  else
    echo "Warning: $arch directory not found: $build_dir"
  fi
}

for ARCH in arm64-v8a armeabi-v7a x86 x86_64; do
  copy_libs "$ARCH"
done

echo "OpenCV libraries prepared successfully."
