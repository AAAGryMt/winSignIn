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

## 当前接入方式（PoC）

`QrScannerScreen.kt` 顶部的常量控制：

| 常量 | 默认 | 含义 |
|---|---|---|
| `WECHAT_ENABLED` | `true` | 是否启用 WeChat 兜底 |
| `WECHAT_EVERY_N_FRAMES` | `2` | 每隔几帧跑一次 |
| `WECHAT_SCALE_FACTOR` | `0f` | 0=引擎默认（缩到 15 万像素面积），1=不缩放 |
| `ZXING_FALLBACK_ENABLED` | `false` | 和 WeChat 二选一，默认关 ZXing |

流程：**ML Kit 先解 → 连续 2 帧没解出来 → 每 2 帧交给 WeChat 再试**。
谁解开就用谁的结果，logcat 里 tag `QrScanner` 会打出命中来源和耗时。

## 怎么验证

```bash
adb logcat -s QrScanner
```

关键日志：

```
wechat decoder ready=true          引擎初始化成功（否则是 false，看 WeChatQr tag）
analysis resolution 1280x720       实际分析分辨率
wechat hit 35ms frame 42           WeChat 解出来了，耗时 35ms
wechat miss 31ms frame 44          WeChat 也没解出来
ML Kit decoded ... at frame N      ML Kit 的命中
```

判据：

- **命中率**：`wechat hit` 里有多少是 ML Kit 漏掉的 → 决定兜底有没有价值
- **耗时**：`wechat hit/miss` 后面的 ms。>80ms 就要考虑降 `scaleFactor` 或降频率
- **主路径延迟**：`ML Kit decoded ... at frame N` 的 N 有没有明显变大（说明兜底在拖慢主路径）

## 调优方向（按性价比）

1. `WECHAT_SCALE_FACTOR`：默认 0 会把 1280×720 缩到约 400×300 再检测。
   小码扫不到就试 `0.6 ~ 1.0`，代价是耗时按面积增长。
2. `WECHAT_EVERY_N_FRAMES`：从 2 调到 3 或 4，降低 CPU。
3. 如果实测 WeChat 明显强于 ML Kit，把主路径反过来（WeChat 先、ML Kit 兜底），
   ML Kit 的 51MB 依赖就能整个删掉。
4. 远距离场景更有效的是 ROI：先把中央区域裁出来再送 WeChat，
   相当于免费放大二维码。
