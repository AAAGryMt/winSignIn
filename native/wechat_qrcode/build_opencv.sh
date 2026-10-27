#!/usr/bin/env bash
# 一次性：把 OpenCV（含 contrib 的 wechat_qrcode 模块）交叉编译成 Android arm64 静态库。
#
# 只在第一次或者升级 OpenCV 版本时需要跑。产物落在 $WORK/install-arm64，
# 供 build.sh 通过 OpenCV_DIR 引用。
set -euo pipefail

OPENCV_VERSION="${OPENCV_VERSION:-4.12.0}"
WORK="${OPENCV_WORK:-$HOME/Library/Caches/winsignin-opencv}"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"

mkdir -p "$WORK"
cd "$WORK"

if [[ ! -d "opencv-$OPENCV_VERSION" ]]; then
    echo ">> 下载 OpenCV $OPENCV_VERSION 源码"
    [[ -f "opencv-$OPENCV_VERSION.tar.gz" ]] || curl -L -o "opencv-$OPENCV_VERSION.tar.gz" \
        "https://github.com/opencv/opencv/archive/refs/tags/$OPENCV_VERSION.tar.gz"
    [[ -f "opencv_contrib-$OPENCV_VERSION.tar.gz" ]] || curl -L -o "opencv_contrib-$OPENCV_VERSION.tar.gz" \
        "https://github.com/opencv/opencv_contrib/archive/refs/tags/$OPENCV_VERSION.tar.gz"
    tar xzf "opencv-$OPENCV_VERSION.tar.gz"
    tar xzf "opencv_contrib-$OPENCV_VERSION.tar.gz"
fi

echo ">> 配置（只编 core/imgproc/dnn/objdetect/wechat_qrcode，静态链接）"
cmake -S "opencv-$OPENCV_VERSION" -B "build-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$WORK/install-$ABI" \
    -DOPENCV_EXTRA_MODULES_PATH="$WORK/opencv_contrib-$OPENCV_VERSION/modules" \
    -DBUILD_LIST=core,imgproc,dnn,objdetect,wechat_qrcode \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF \
    -DBUILD_opencv_apps=OFF -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF \
    -DBUILD_JAVA=OFF -DBUILD_ANDROID_PROJECTS=OFF \
    -DBUILD_ANDROID_EXAMPLES=OFF -DINSTALL_ANDROID_EXAMPLES=OFF \
    -DWITH_OPENCL=OFF -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_TBB=OFF -DWITH_OPENMP=OFF \
    -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF \
    -DWITH_OPENEXR=OFF -DWITH_JASPER=OFF -DWITH_EIGEN=OFF \
    -DWITH_PROTOBUF=BUILTIN -DCMAKE_POLICY_VERSION_MINIMUM=3.5

echo ">> 编译"
cmake --build "build-$ABI" -j"$(sysctl -n hw.ncpu)"
cmake --install "build-$ABI"

# 坑：没编 gapi 时，导出的 OpenCVModules.cmake 仍然引用 libade.a，
# find_package 会因为文件不存在直接报错。放一个空的占位归档即可
# （它属于未参与链接的 gapi，永远不会被真正用到）。
ADE_DIR="$WORK/install-$ABI/sdk/native/3rdparty/libs/$ABI"
if [[ -d "$ADE_DIR" && ! -f "$ADE_DIR/libade.a" ]]; then
    printf 'int ade_unused_dummy;\n' > "$WORK/ade_dummy.c"
    cc -c "$WORK/ade_dummy.c" -o "$WORK/ade_dummy.o"
    ar rcs "$ADE_DIR/libade.a" "$WORK/ade_dummy.o"
    echo ">> 补上 libade.a 占位"
fi

echo "✅ OpenCV $OPENCV_VERSION ($ABI) 已安装到 $WORK/install-$ABI"
