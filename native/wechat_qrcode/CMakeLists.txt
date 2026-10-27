cmake_minimum_required(VERSION 3.18)
project(wechatqrcode_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV 静态库由 native/wechat_qrcode/build.sh 预先编好，
# 通过 -DOpenCV_DIR=<install>/lib/cmake/opencv4 传进来。
find_package(OpenCV REQUIRED COMPONENTS core imgproc dnn objdetect wechat_qrcode)

add_library(wechatqrcode SHARED jni/wechat_qrcode_jni.cpp)

target_include_directories(wechatqrcode PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(wechatqrcode PRIVATE ${OpenCV_LIBS} log)
target_compile_options(wechatqrcode PRIVATE -O2 -ffunction-sections -fdata-sections)
# 16KB 页对齐：Android 15+ 的部分设备（以及 Play 要求）用 16KB 页，
# 没这个参数编出来的 .so 在那些机器上会直接 UnsatisfiedLinkError。
target_link_options(wechatqrcode PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384)
