# wechat_qrcode（微信扫码引擎）接入说明

## 这是什么

`opencv_contrib/modules/wechat_qrcode` —— 腾讯微信 CV 团队捐给 OpenCV 的扫码引擎，
微信/企业微信/QQ 用的就是这套。组成：

```
CNN 检测器（detect.caffemodel, 943KB）  →  定位二维码（远距离、多码）
超分模型（sr.caffemodel, 23KB）        →  小码/模糊码先放大再解
ZXing 解码器                            →  最后一步出字符串
```

许可证 Apache-2.0。

## 目录

```
native/wechat_qrcode/
├── CMakeLists.txt          JNI 库工程
├── build_opencv.sh         一次性：交叉编译 OpenCV 静态库（含 wechat_qrcode）
├── build.sh                编 libwechatqrcode.so → app/src/main/jniLibs/arm64-v8a/
└── jni/wechat_qrcode_jni.cpp   JNI 壳，输入 YUV 的 Y 平面，输出字符串

app/src/main/assets/wechat_qrcode/     四个 Caffe 模型（首次启动复制到 filesDir）
app/src/main/java/.../WeChatQrDecoder.kt    Kotlin 封装（单例）
```

## 构建

```bash
# 第一次（约 10~20 分钟，产物在 ~/Library/Caches/winsignin-opencv）
cd native/wechat_qrcode
./build_opencv.sh

# 之后每次改 JNI 代码
./build.sh

# 然后正常打包
cd ../.. && ./gradlew :app:assembleDebug
```

`build_opencv.sh` 只编译 `core,imgproc,dnn,objdetect,wechat_qrcode` 五个模块，
静态链接，不含 Java 绑定/Python/示例，所以比完整 OpenCV 小很多。

## 当前接入方式

**wechat_qrcode 是唯一的解码器**，ML Kit / ZXing 已全部移除。

`QrScannerScreen.kt` 顶部只有一个可调常量：

| 常量 | 默认 | 含义 |
|---|---|---|
| `SCALE_FACTOR` | `0f` | 0=引擎默认（缩到约 15 万像素面积再检测），1=不缩放 |

每帧流程：

```
CameraX ImageAnalysis(1280x720, KEEP_ONLY_LATEST)
  → Y 平面（direct ByteBuffer，零拷贝）
  → cv::rotate（按 rotationDegrees 摆正）
  → WeChatQRCode::detectAndDecode（CNN 检测 + 超分 + 解码）
  → 多码时取 boundingBox 面积最大的那个
  → 回调原文
```

保留的相机能力：点击对焦（AF/AE + 对焦圈）、双指缩放、手电筒。
相机释放、分析线程关闭都在 `onDispose` 里；解码器是单例，跨页面复用，
App 启动时就在后台预热。

## 怎么验证

```bash
adb logcat -s QrScanner
```

关键日志：

```
decoder ready=true              引擎初始化成功（false 就看 WeChatQr tag）
analysis 1280x720               实际分析分辨率
frame 16 21ms miss              第 16 帧，解码 21ms，没解出
frame 31 18ms hit               解出来了
decoded 46 chars at frame 31    原文长度和命中帧号
```

判据：

- **耗时**：超过 80ms 说明设备偏弱，可以把分析流降到 960×540
- **命中帧号**：从进入页面到 `decoded` 的帧数，越小越快

## 调优方向（按性价比）

1. `SCALE_FACTOR`：默认 0 会把 1280×720 缩到约 420×320 再检测。
   小码扫不到就试 `0.6 ~ 1.0`，代价是耗时按面积线性增长。
2. 分析流分辨率：`QrScannerScreen.kt` 里的 `Size(1280, 720)`，
   弱机可以降到 `960×540` 换帧率。
3. 远距离场景最有效的是 ROI：先把中央区域裁出来再送引擎，
   相当于免费放大二维码，比调 SCALE_FACTOR 更划算。
