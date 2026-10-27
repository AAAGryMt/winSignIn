package org.example.winsignin

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer

/**
 * wechat_qrcode（OpenCV contrib 里的微信扫码引擎）的 Kotlin 侧封装。
 *
 * 引擎本身在 native/wechat_qrcode，编成 libwechatqrcode.so。
 * 四个 Caffe 模型放在 assets/wechat_qrcode/，首次初始化时复制到 filesDir
 * （OpenCV 的 FileStorage 读不了 assets 路径）。
 *
 * 单例：模型加载约 100ms 量级，别每次进扫码页都重建。
 */
object WeChatQrDecoder {
    private const val TAG = "WeChatQr"
    private const val ASSET_DIR = "wechat_qrcode"
    private const val STAMP_FILE = "wechat_qrcode.stamp"
    private const val MODEL_VERSION = "1"
    private val MODEL_FILES = listOf(
        "detect.prototxt",
        "detect.caffemodel",
        "sr.prototxt",
        "sr.caffemodel",
    )

    @Volatile
    private var ready = false

    private val loaded: Boolean = try {
        System.loadLibrary("wechatqrcode")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "loadLibrary(wechatqrcode) failed", t)
        false
    }

    val isReady: Boolean get() = ready
    val isAvailable: Boolean get() = loaded

    /** 复制模型 + 建检测器。可以重复调用，成功的第二次直接返回。 */
    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        if (!loaded) return false
        try {
            val dir = File(context.filesDir, ASSET_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "mkdirs failed: $dir")
                return false
            }
            val stamp = File(dir, STAMP_FILE)
            if (!stamp.exists() || stamp.readText().trim() != MODEL_VERSION) {
                MODEL_FILES.forEach { name ->
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        File(dir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                stamp.writeText(MODEL_VERSION)
            }
            fun path(name: String) = File(dir, name).absolutePath
            ready = nativeInit(
                path("detect.prototxt"),
                path("detect.caffemodel"),
                path("sr.prototxt"),
                path("sr.caffemodel"),
            )
            Log.d(TAG, if (ready) "init ok" else "init failed")
        } catch (t: Throwable) {
            Log.e(TAG, "init threw", t)
            ready = false
        }
        return ready
    }

    /**
     * 解一帧。
     *
     * @param scaleFactor 0 = 用引擎默认（先把图缩到约 15 万像素面积再检测）；
     *                    1 = 不缩放（小码更清楚，但耗时按面积增长）
     * @return 识别到的原文，没识别到返回 null
     */
    fun decode(image: ImageProxy, scaleFactor: Float = 0f): String? {
        if (!ready) return null
        if (image.format != ImageFormat.YUV_420_888) return null
        val plane = image.planes.firstOrNull() ?: return null
        // JNI 侧走 GetDirectBufferAddress，非 direct 的 buffer 拿不到地址
        val source = plane.buffer
        if (!source.isDirect) return null
        return try {
            nativeDecode(
                source,
                plane.rowStride,
                plane.pixelStride,
                image.width,
                image.height,
                image.imageInfo.rotationDegrees,
                scaleFactor,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "decode threw", t)
            null
        }
    }

    fun release() {
        if (ready) {
            nativeRelease()
            ready = false
        }
    }

    private external fun nativeInit(
        detectProto: String,
        detectModel: String,
        srProto: String,
        srModel: String,
    ): Boolean

    private external fun nativeDecode(
        y: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
        scaleFactor: Float,
    ): String?

    private external fun nativeRelease()
}
