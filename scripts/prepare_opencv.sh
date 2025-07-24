#!/bin/bash
set -euo pipefail

echo "Preparing OpenCV libraries for the app..."

SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_BASE="$SCRIPT_DIR/external"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"
ARCHS=(arm64-v8a armeabi-v7a x86 x86_64) # riscv64 optional

# Create jniLibs directory structure
for ARCH in "${ARCHS[@]}"; do
  mkdir -p "$JNILIBS_DIR/$ARCH"
done

MISSING=0

copy_libs() {
  local arch=$1
  local build_dir="$BUILD_BASE/opencv-build_$arch/lib/$arch"
  if [ -d "$build_dir" ]; then
    if compgen -G "$build_dir/"'*.so' > /dev/null; then
      cp -f "$build_dir/"*.so "$JNILIBS_DIR/$arch/"
      echo "Copied $arch shared libraries (.so)"
    else
      echo "Warning: No libraries found for $arch"
      MISSING=1
    fi
  else
    echo "Warning: $arch directory not found: $build_dir"
    MISSING=1
  fi
}

for ARCH in "${ARCHS[@]}"; do
  copy_libs "$ARCH"
done

if [[ $MISSING -eq 1 ]]; then
  echo "Some architectures missing libraries. Check build outputs!"
  exit 1
fi

echo "OpenCV libraries prepared successfully."
