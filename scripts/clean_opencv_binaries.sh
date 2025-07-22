#!/bin/bash
# This script removes pre-compiled OpenCV binaries from the repository
# to ensure F-Droid compatibility.

set -e

echo "Removing pre-compiled OpenCV binaries from the repository..."

# Remove .so files from opencv/native/libs
if [ -d "opencv/native/libs" ]; then
  echo "Removing .so files from opencv/native/libs..."
  find opencv/native/libs -name "*.so" -type f -delete
  echo "Done."
else
  echo "Directory opencv/native/libs not found. Skipping."
fi

# Remove .so files from opencv/src/main/jniLibs if it exists
if [ -d "opencv/src/main/jniLibs" ]; then
  echo "Removing .so files from opencv/src/main/jniLibs..."
  find opencv/src/main/jniLibs -name "*.so" -type f -delete
  echo "Done."
else
  echo "Directory opencv/src/main/jniLibs not found. Skipping."
fi

# Remove pre-built JAR file from app/libs
if [ -f "app/libs/opencv-android.jar" ]; then
  echo "Removing pre-built opencv-android.jar from app/libs..."
  rm app/libs/opencv-android.jar
  echo "Done."
else
  echo "File app/libs/opencv-android.jar not found. Skipping."
fi

# Remove static libraries (.a files) from opencv/native/3rdparty/libs
if [ -d "opencv/native/3rdparty/libs" ]; then
  echo "Removing static libraries from opencv/native/3rdparty/libs..."
  find opencv/native/3rdparty/libs -name "*.a" -type f -delete
  echo "Done."
else
  echo "Directory opencv/native/3rdparty/libs not found. Skipping."
fi

# Remove static libraries (.a files) from opencv/native/staticlibs
if [ -d "opencv/native/staticlibs" ]; then
  echo "Removing static libraries from opencv/native/staticlibs..."
  find opencv/native/staticlibs -name "*.a" -type f -delete
  echo "Done."
else
  echo "Directory opencv/native/staticlibs not found. Skipping."
fi

# Remove binary files from opencv/build directory
if [ -d "opencv/build" ]; then
  echo "Removing binary files from opencv/build..."
  find opencv/build -name "*.so" -o -name "*.a" -o -name "*.dll" -o -name "*.dylib" -o -name "*.lib" -type f -delete
  echo "Done."
else
  echo "Directory opencv/build not found. Skipping."
fi

# Remove binary files from opencv/.cxx directory
if [ -d "opencv/.cxx" ]; then
  echo "Removing binary files from opencv/.cxx..."
  find opencv/.cxx -name "*.so" -o -name "*.a" -o -name "*.dll" -o -name "*.dylib" -o -name "*.lib" -type f -delete
  echo "Done."
else
  echo "Directory opencv/.cxx not found. Skipping."
fi

# Remove test binaries from opencv/native/bin directory
if [ -d "opencv/native/bin" ]; then
  echo "Removing test binaries from opencv/native/bin..."
  find opencv/native/bin -type f -delete
  echo "Done."
else
  echo "Directory opencv/native/bin not found. Skipping."
fi

# Remove cmake files and makefiles from opencv/native/jni directory
if [ -d "opencv/native/jni" ]; then
  echo "Removing cmake files and makefiles from opencv/native/jni..."
  find opencv/native/jni -type f \( -name "*.cmake" -o -name "*.mk" -o -name "*.make" \) -delete
  echo "Done."
else
  echo "Directory opencv/native/jni not found. Skipping."
fi

echo "Creating empty directories to maintain structure..."

# Create empty directories for native libs
mkdir -p opencv/native/libs/arm64-v8a
mkdir -p opencv/native/libs/armeabi-v7a
mkdir -p opencv/native/libs/x86
mkdir -p opencv/native/libs/x86_64

# Create empty directories for jniLibs if needed
mkdir -p opencv/src/main/jniLibs/arm64-v8a
mkdir -p opencv/src/main/jniLibs/armeabi-v7a
mkdir -p opencv/src/main/jniLibs/x86
mkdir -p opencv/src/main/jniLibs/x86_64

# Create empty directories for 3rdparty libs
mkdir -p opencv/native/3rdparty/libs/arm64-v8a
mkdir -p opencv/native/3rdparty/libs/armeabi-v7a
mkdir -p opencv/native/3rdparty/libs/x86
mkdir -p opencv/native/3rdparty/libs/x86_64

# Create empty directories for staticlibs
mkdir -p opencv/native/staticlibs/arm64-v8a
mkdir -p opencv/native/staticlibs/armeabi-v7a
mkdir -p opencv/native/staticlibs/x86
mkdir -p opencv/native/staticlibs/x86_64

# Create empty app/libs directory if needed
mkdir -p app/libs

echo "Adding .gitkeep files to maintain empty directory structure..."

# Add .gitkeep files to maintain empty directory structure
touch opencv/native/libs/arm64-v8a/.gitkeep
touch opencv/native/libs/armeabi-v7a/.gitkeep
touch opencv/native/libs/x86/.gitkeep
touch opencv/native/libs/x86_64/.gitkeep

touch opencv/src/main/jniLibs/arm64-v8a/.gitkeep
touch opencv/src/main/jniLibs/armeabi-v7a/.gitkeep
touch opencv/src/main/jniLibs/x86/.gitkeep
touch opencv/src/main/jniLibs/x86_64/.gitkeep

touch opencv/native/3rdparty/libs/arm64-v8a/.gitkeep
touch opencv/native/3rdparty/libs/armeabi-v7a/.gitkeep
touch opencv/native/3rdparty/libs/x86/.gitkeep
touch opencv/native/3rdparty/libs/x86_64/.gitkeep

touch opencv/native/staticlibs/arm64-v8a/.gitkeep
touch opencv/native/staticlibs/armeabi-v7a/.gitkeep
touch opencv/native/staticlibs/x86/.gitkeep
touch opencv/native/staticlibs/x86_64/.gitkeep

touch app/libs/.gitkeep

echo "Pre-compiled OpenCV binaries have been removed from the repository."
echo "The build process will now build these binaries from source."