#!/bin/bash
set -e
OPENCV_VERSION="4.12.0"

echo "Fetching OpenCV source code (version $OPENCV_VERSION)..."

# Create external directory if it doesn't exist
mkdir -p external

# Check if OpenCV directory already exists
if [ ! -d "external/opencv" ]; then
  echo "Cloning OpenCV repository..."
  git clone --depth 1 --branch $OPENCV_VERSION https://github.com/opencv/opencv.git external/opencv
  echo "OpenCV source code downloaded successfully."
else
  echo "OpenCV source code already exists. Skipping download."
fi

# Make sure we have the right version
cd external/opencv
CURRENT_VERSION=$(git describe --tags)
if [ "$CURRENT_VERSION" != "$OPENCV_VERSION" ]; then
  echo "Current version ($CURRENT_VERSION) doesn't match required version ($OPENCV_VERSION)."
  echo "Updating to the correct version..."
  git fetch --depth 1 origin tag $OPENCV_VERSION
  git checkout $OPENCV_VERSION
  echo "Updated to OpenCV version $OPENCV_VERSION."
fi
cd ../..

echo "OpenCV source preparation completed."