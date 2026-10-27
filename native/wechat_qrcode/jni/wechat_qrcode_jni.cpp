// wechat_qrcode 的 JNI 壳。
//
// 输入是 CameraX ImageAnalysis 的 Y 平面（直接 ByteBuffer，零拷贝读），
// 输出是第一个解出来的字符串。整条路径不碰 Bitmap，也不做 YUV->RGB。

#include <jni.h>
#include <android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/wechat_qrcode.hpp>

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "WeChatQrNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 检测器初始化一次很贵（要加载两个 Caffe 模型），全局复用一个。
cv::Ptr<cv::wechat_qrcode::WeChatQRCode> g_detector;
std::mutex g_mutex;

std::string to_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_example_winsignin_WeChatQrDecoder_nativeInit(
    JNIEnv* env, jobject /*thiz*/,
    jstring detect_proto, jstring detect_model,
    jstring sr_proto, jstring sr_model) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        g_detector = cv::makePtr<cv::wechat_qrcode::WeChatQRCode>(
            to_string(env, detect_proto), to_string(env, detect_model),
            to_string(env, sr_proto), to_string(env, sr_model));
        const bool ok = !g_detector.empty();
        LOGD("init %s", ok ? "ok" : "failed");
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("init exception: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * @param y_buffer      Y 平面直接缓冲区
 * @param row_stride    每行字节数（可能大于 width）
 * @param pixel_stride  相邻像素间隔（YUV_420_888 通常是 1，个别设备是 2）
 * @param rotation      需要顺时针旋转的角度（0/90/180/270），来自 ImageInfo
 * @param scale_factor  0 表示用 OpenCV 默认（缩到约 15 万像素面积）；1 表示不缩放
 */
JNIEXPORT jstring JNICALL
Java_org_example_winsignin_WeChatQrDecoder_nativeDecode(
    JNIEnv* env, jobject /*thiz*/,
    jobject y_buffer, jint row_stride, jint pixel_stride,
    jint width, jint height, jint rotation, jfloat scale_factor) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_detector.empty() || y_buffer == nullptr) return nullptr;

    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(y_buffer));
    if (base == nullptr) return nullptr;

    cv::Mat gray;
    if (pixel_stride == 1) {
        // 零拷贝：直接按 rowStride 包一层
        gray = cv::Mat(height, width, CV_8UC1, base, static_cast<size_t>(row_stride));
    } else {
        gray.create(height, width, CV_8UC1);
        for (int y = 0; y < height; ++y) {
            const uint8_t* src = base + static_cast<size_t>(y) * row_stride;
            uint8_t* dst = gray.ptr<uint8_t>(y);
            for (int x = 0; x < width; ++x) dst[x] = src[static_cast<size_t>(x) * pixel_stride];
        }
    }

    cv::Mat upright;
    switch (((rotation % 360) + 360) % 360) {
        case 90: cv::rotate(gray, upright, cv::ROTATE_90_CLOCKWISE); break;
        case 180: cv::rotate(gray, upright, cv::ROTATE_180); break;
        case 270: cv::rotate(gray, upright, cv::ROTATE_90_COUNTERCLOCKWISE); break;
        default: upright = gray; break;
    }

    if (scale_factor > 0.0f && scale_factor <= 1.0f) {
        g_detector->setScaleFactor(scale_factor);
    }

    try {
        std::vector<cv::Mat> points;
        std::vector<std::string> results = g_detector->detectAndDecode(upright, points);

        // 一屏多码时取面积最大的，避免选中背景里的小码。
        int best = -1;
        double best_area = -1.0;
        for (size_t i = 0; i < results.size(); ++i) {
            if (results[i].empty()) continue;
            double area = 0.0;
            if (i < points.size() && !points[i].empty()) {
                try {
                    area = std::abs(cv::contourArea(points[i]));
                } catch (const cv::Exception&) {
                    area = 0.0;
                }
            }
            if (area > best_area) {
                best_area = area;
                best = static_cast<int>(i);
            }
        }
        if (best >= 0) return env->NewStringUTF(results[best].c_str());
    } catch (const std::exception& e) {
        LOGE("decode exception: %s", e.what());
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_org_example_winsignin_WeChatQrDecoder_nativeRelease(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_detector.reset();
    LOGD("released");
}

}  // extern "C"
