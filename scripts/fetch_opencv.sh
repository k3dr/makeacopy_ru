#!/bin/bash
set -e
OPENCV_VERSION="4.12.0"

# Resolve absolute project root (directory where this script lives)
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPENCV_DIR="$SCRIPT_DIR/external/opencv"

echo "Fetching OpenCV source code (version $OPENCV_VERSION)..."

# Create external directory if it doesn't exist
mkdir -p "$(dirname "$OPENCV_DIR")"

# Check if OpenCV directory already exists
if [ ! -d "$OPENCV_DIR" ]; then
  echo "Cloning OpenCV repository..."
  git clone --depth 1 --branch "$OPENCV_VERSION" https://github.com/opencv/opencv.git "$OPENCV_DIR"
  echo "OpenCV source code downloaded successfully."
else
  echo "OpenCV source code already exists at $OPENCV_DIR. Skipping download."
fi

# Make sure we have the right version
cd "$OPENCV_DIR"
CURRENT_VERSION=$(git describe --tags || echo "unknown")
if [ "$CURRENT_VERSION" != "$OPENCV_VERSION" ]; then
  echo "Current version ($CURRENT_VERSION) doesn't match required version ($OPENCV_VERSION)."
  echo "Updating to the correct version..."
  git fetch --depth 1 origin tag "$OPENCV_VERSION"
  git checkout "$OPENCV_VERSION"
  echo "Updated to OpenCV version $OPENCV_VERSION."
fi
cd "$SCRIPT_DIR"

echo "OpenCV source preparation completed."
