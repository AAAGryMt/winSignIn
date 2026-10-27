#!/usr/bin/env bash
# 编 libwechatqrcode.so 并直接放进 app/src/main/jniLibs/arm64-v8a/。
#
# 前置：先跑一次 build_opencv.sh 产出 OpenCV 静态库。
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
OPENCV_INSTALL="${OPENCV_ANDROID_INSTALL:-$HOME/Library/Caches/winsignin-opencv/install-arm64}"
ABI="${ABI:-arm64-v8a}"
OUT="$PWD/../../app/src/main/jniLibs/$ABI"

# Android 安装布局是 sdk/native/jni，桌面布局是 lib/cmake/opencv4
if [[ -f "$OPENCV_INSTALL/sdk/native/jni/OpenCVConfig.cmake" ]]; then
    OPENCV_DIR="$OPENCV_INSTALL/sdk/native/jni"
elif [[ -f "$OPENCV_INSTALL/lib/cmake/opencv4/OpenCVConfig.cmake" ]]; then
    OPENCV_DIR="$OPENCV_INSTALL/lib/cmake/opencv4"
else
    echo "OpenCV 静态库还没编，先执行：./build_opencv.sh" >&2
    exit 1
fi

mkdir -p "$OUT"

cmake -S . -B "build/$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT" \
    -DOpenCV_DIR="$OPENCV_DIR"

cmake --build "build/$ABI" -j"$(sysctl -n hw.ncpu)"

# CMake 产物带调试符号（100MB+），打包前必须 strip
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
if [[ -x "$STRIP" ]]; then
    "$STRIP" --strip-unneeded "$OUT/libwechatqrcode.so"
fi

SIZE=$(du -h "$OUT/libwechatqrcode.so" | cut -f1)
echo "✅ libwechatqrcode.so ($ABI, $SIZE) -> $OUT"
