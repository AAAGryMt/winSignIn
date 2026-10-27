package org.example.winsignin

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 解码时的缩放系数，交给 wechat_qrcode 的 `setScaleFactor`。
 *
 * - `0`：引擎默认，先把输入缩到约 15 万像素面积再检测（快）
 * - `1`：不缩放，小码/远距离更清楚，但耗时随面积线性增长
 *
 * 远距离扫不到时优先调这个，其次再考虑把分析分辨率提到 1920×1080。
 */
private const val SCALE_FACTOR = 0f

/** debug 下每多少帧打一条耗时日志（约 1 秒一条）。 */
private const val LOG_EVERY_N_FRAMES = 15

/**
 * 扫码页：CameraX 出预览，wechat_qrcode（OpenCV 微信扫码引擎）逐帧解码。
 *
 * 只有这一条识别路径：Y 平面直接进 native，不做 Bitmap、不走 ML Kit。
 * 相机相关的辅助能力保留：点击对焦、双指缩放、手电筒。
 *
 * @param onResult 识别到第一个二维码时回调一次（原文）。
 * @param onCancel 点返回箭头或系统返回键时回调。
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult = rememberUpdatedState(onResult)
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    // 分析在单线程串行跑，配合 KEEP_ONLY_LATEST 不会堆积。
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val frameCounter = remember { AtomicInteger(0) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    // 引擎是单例，第一次进扫码页时才加载模型；之后每帧都直接用。
    LaunchedEffect(Unit) {
        if (!WeChatQrDecoder.isReady) {
            withContext(Dispatchers.IO) { WeChatQrDecoder.init(context) }
        }
        if (BuildConfig.DEBUG) {
            Log.d("QrScanner", "decoder ready=${WeChatQrDecoder.isReady}")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 先停分析、解绑相机，避免相机常驻和 ImageProxy 泄漏。
            // 解码器是跨页面的单例，这里不释放。
            disposed.set(true)
            camera?.cameraControl?.enableTorch(false)
            analysisUseCase?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(700)
            focusPoint = null
        }
    }

    BackHandler { onCancel() }

    val density = LocalDensity.current
    val focusRingSize = 48.dp
    val focusRingHalfPx = with(density) { focusRingSize.toPx() / 2f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 双指缩放：按当前比例乘缩放因子，夹在相机支持的最小/最大倍率之间
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val cam = camera ?: return@detectTransformGestures
                    val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val ratio = (state.zoomRatio * zoomChange)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                }
            }
            // 点击对焦：同时锁 AF/AE，3 秒后自动恢复
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val pv = previewView ?: return@detectTapGestures
                    val cam = camera ?: return@detectTapGestures
                    val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    focusPoint = offset
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx).apply {
                    // 预览和分析统一 16:9，避免「预览里看得到、分析帧里被裁掉」
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewView = pv
                val executor = ContextCompat.getMainExecutor(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)

                providerFuture.addListener({
                    if (!disposed.get()) {
                        try {
                            val provider = providerFuture.get()
                            cameraProvider = provider

                            val preview = Preview.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .build(),
                                )
                                .build()
                                .also { it.setSurfaceProvider(pv.surfaceProvider) }

                            val analysis = ImageAnalysis.Builder()
                                // 显式指定分析流分辨率，默认的 640×480 对小码太糊
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build(),
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysisUseCase = analysis

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (disposed.get() || handled.get() || !WeChatQrDecoder.isReady) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                try {
                                    val frame = frameCounter.incrementAndGet()
                                    val start = SystemClock.elapsedRealtime()
                                    val result = WeChatQrDecoder.decode(imageProxy, SCALE_FACTOR)
                                    val cost = SystemClock.elapsedRealtime() - start

                                    if (BuildConfig.DEBUG) {
                                        if (frame == 1) {
                                            Log.d(
                                                "QrScanner",
                                                "analysis ${imageProxy.width}x${imageProxy.height}",
                                            )
                                        }
                                        if (frame % LOG_EVERY_N_FRAMES == 1) {
                                            Log.d(
                                                "QrScanner",
                                                "frame $frame ${cost}ms ${if (result.isNullOrBlank()) "miss" else "hit"}",
                                            )
                                        }
                                    }

                                    if (!result.isNullOrBlank() && handled.compareAndSet(false, true)) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "QrScanner",
                                                "decoded ${result.length} chars at frame $frame",
                                            )
                                        }
                                        mainExecutor.execute {
                                            if (!disposed.get()) currentOnResult.value(result)
                                        }
                                    }
                                } catch (t: Throwable) {
                                    // 单帧失败不终止扫码，继续下一帧。
                                    if (BuildConfig.DEBUG) {
                                        Log.d(
                                            "QrScanner",
                                            "frame skipped: ${t.javaClass.simpleName}",
                                        )
                                    }
                                } finally {
                                    // 识别成功/失败/异常都必须 close，否则相机不再出帧。
                                    imageProxy.close()
                                }
                            }

                            if (disposed.get()) {
                                analysis.clearAnalyzer()
                                provider.unbindAll()
                            } else {
                                provider.unbindAll()
                                val boundCamera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                                camera = boundCamera
                                hasFlash = boundCamera.cameraInfo.hasFlashUnit()
                                cameraError = null
                                if (BuildConfig.DEBUG) {
                                    Log.d("QrScanner", "camera bound, flash=$hasFlash")
                                }
                            }
                        } catch (e: Exception) {
                            cameraProvider?.unbindAll()
                            cameraError = e.message ?: "相机启动失败"
                            if (BuildConfig.DEBUG) {
                                Log.e("QrScanner", "camera bind failed", e)
                            }
                        }
                    }
                }, executor)

                pv
            },
        )

        // 点击对焦的视觉反馈
        focusPoint?.let { point ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (point.x - focusRingHalfPx).roundToInt(),
                            (point.y - focusRingHalfPx).roundToInt(),
                        )
                    }
                    .size(focusRingSize)
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            )
        }

        // 手电筒开关
        if (hasFlash) {
            IconButton(
                onClick = { torchOn = !torchOn },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                    tint = Color.White,
                )
            }
        }

        cameraError?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // 透明返回栏盖在相机预览上
        ScreenTopBar(
            title = "",
            onBack = onCancel,
            transparent = true,
            inset = true,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
