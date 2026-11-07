# 更新日志

## Unreleased — Rust 逻辑 web 化（后端起底）

> 目标：让 iOS 能用。iOS 只能走浏览器，而浏览器受混合内容、CORS、
> CAS cookie 三条限制，无法直连学校接口，因此必须有后端代理。

### 新增

- `rust/src/http/mod.rs` — axum 路由、`ServerConfig`、内置冒烟测试页
- `rust/src/http/handlers.rs` — 12 个接口的 handler（JSON ↔ 现有函数）
- `rust/src/bin/server.rs` — 服务入口，读 `WINSIGNIN_ADDR` / `WINSIGNIN_STATIC`
- `rust/README.md` — 接口表、运行方式、部署与安全说明

### 修改

- `rust/Cargo.toml`
  - 新增 `server` feature（默认关闭），把 `axum` / `tower-http` 设为 optional
  - `[[bin]] server` 声明 `required-features = ["server"]`，
    因此 `cargo ndk build --release`（Android）不会把 axum 编进 `.so`
  - 新增 `serde`（derive）；`tokio` 追加 `net` / `macros` feature
- `rust/src/lib.rs`
  - 9 个 `uniffi::Record` 追加 `serde::Serialize`，`GeoPoint` 额外加 `Deserialize`
  - 新增 `#[cfg(feature = "server")] pub mod http;`
  - **业务函数一行未改**

### 接口

`POST /api/{login,session/refresh,account,rollcalls,rollcalls/number-code,rollcalls/verify,sign/qr,sign/number,sign/radar,sign/radar/empty,radar/solve}` + `GET /api/health`

返回体直接复用现有 `uniffi::Record`；业务失败走 `ok`/`success` 字段，不用 HTTP 错误码。
需要 cookie 的接口一律 POST + JSON，避免会话凭据出现在 URL 和日志里。

### 设计说明

- 所有 handler 通过 `tokio::task::spawn_blocking` 调用现有函数。
  因为 `lib.rs` 内部用 `runtime().block_on(...)` 跑自己的 Runtime，
  直接在 async handler 里调用会 panic。
- 后端不持久化密码：登录透传一次，客户端只存 `cookie` / `cas_cookie`。
- `device_id` / `bfp` 留空时由后端生成。

### 验证（本机已实测）

```text
cargo check --lib                        → Finished（Android 路径不受影响）
cargo check --features server --bin server → Finished
cargo build --features server --bin server → 产出 target/debug/server
cargo test --lib                         → 21 passed; 0 failed
```

实际起服务后 curl 验证：

| 请求 | 结果 | 说明 |
|---|---|---|
| `GET /api/health` | `{"ok":true,...}` | 服务正常 |
| `GET /` | 200, 2951 bytes | 内置冒烟测试页 |
| `POST /api/radar/solve`（真实探测点） | `{"ok":true,"lat":29.5348...,"lon":106.6109...}` | 纯计算路径通 |
| `POST /api/sign/qr`（合法二维码格式） | `{"success":false,"message":"登录已过期，请重新登录"}` | `qr.rs` 解包成功，请求已发出 |
| `POST /api/login`（错误账密） | `{"ok":false,"message":"账号或密码不正确"}` | **真实走完 CAS 12 跳链路**，拿到学校返回的错误文案 |

最后一条说明：`cas.rs` 的完整登录链通过 HTTP 层被真实调用并返回了学校侧的业务文案，
不是 mock。

### 未做

- 没有前端工程（只有一个内置冒烟测试页，供 iOS Safari 验证接口通不通）
- 没有服务端会话存储 / 账号表：cookie 由客户端持有并在请求里回传
- 没有 HTTPS 终止配置（部署时交给 Caddy / nginx）
- `CorsLayer::permissive()` 仅为本地调试，生产需换白名单

## Unreleased — 扫码管线优化（2026-10-27）

> 当前状态：**代码已修改，尚未构建成功，暂无 APK。**
>
> 构建阻塞原因：本机缺少 Android SDK、Rust、cargo-ndk 和 NDK，`assembleDebug` 无法完成。

### 新增

- 新增 `app/src/main/java/org/example/winsignin/ZxingQrFallback.kt`
  - ML Kit 未识别出二维码时，用 ZXing 做兜底解码。
  - 只识别二维码，使用 `TRY_HARDER`。
  - 支持按 `ImageProxy.imageInfo.rotationDegrees` 旋转后再解码。
  - 解码完成后回收 Bitmap，避免内存泄漏。

### 修改

#### `QrScannerScreen.kt`

**P0：输入质量与生命周期**

- `ImageAnalysis` 显式指定 1280×720 分析分辨率，不再使用 CameraX 默认约 640×480。
- Preview 与 ImageAnalysis 统一使用 16:9，减少预览画面和分析帧的取景差异。
- analyzer 从主线程 executor 改为单线程后台 executor，避免主线程卡顿导致掉帧。
- 离开扫码页时：
  - 设置 `disposed` 标记
  - 关闭手电筒
  - `clearAnalyzer()`
  - `unbindAll()`
  - 关闭 executor
  - 关闭 ML Kit scanner
- 单帧识别增加 3 秒超时，避免 ML Kit 异常时永久占住分析线程。
- 相机绑定失败时显示错误提示，不再直接崩溃。

**P1：对焦、变焦、手电筒**

- 新增点击对焦：
  - 使用 `PreviewView.meteringPointFactory` 把点击坐标转换为相机坐标。
  - 同时锁定 AF/AE。
  - 3 秒后自动恢复。
  - 界面上显示白色对焦圈。
- 保留双指缩放。
- 接入 ML Kit `ZoomSuggestionOptions`：
  - 远距离二维码过小时，ML Kit 建议 zoom ratio。
  - 自动调用 CameraX `setZoomRatio`。
  - 最大支持 8x，实际受设备上限限制。
  - 250ms 内不重复变焦，减少画面抖动。
- 新增手电筒开关：
  - 只有摄像头支持闪光灯时才显示。
  - 位于扫码页右下角。
  - 离开页面时自动关闭。

**P2：兜底解码与调试**

- ML Kit 没有解出二维码时，每 3 帧调用一次 ZXing 兜底。
- 画面里有多个二维码时，取面积最大的一个，避免选到背景里的小码。
- debug 构建增加日志：
  - 实际分析分辨率
  - ML Kit 解码成功
  - ZXing 兜底解码成功
  - ML Kit 自动变焦
  - 相机绑定与单帧异常
- 日志只记录二维码长度，不记录二维码原文。

#### `app/build.gradle.kts`

- 新增依赖：

```kotlin
implementation("com.google.zxing:core:3.5.3")
```

用于 ML Kit 失败时的二维码兜底解码。

### 复审修复（同日）

审阅第一版改动后修掉两处「必须修」：

1. **analyzer 线程中断标志未清 → 扫码会永久静默失效**
   - 原写法在 `InterruptedException` 里调用 `Thread.currentThread().interrupt()`。
   - analyzer 复用一个 worker 线程，标志一旦留下，后续每帧的 `Tasks.await`
     都立刻抛 `InterruptedException`，扫码彻底停止且没有任何提示。
   - 改为 `Thread.interrupted()` 清掉标志后正常返回。

2. **ZXing 兜底的开销抵消了 P0 的收益**
   - 原写法走 `imageProxy.toBitmap()`：1280×720 每次约 3.7MB Bitmap
     + 3.7MB 旋转副本 + 3.7MB `IntArray`，单次 50～150ms，
     还和 ML Kit 抢同一个单线程，等于把提上来的帧率又吃掉。
   - 改为直接读 YUV 的 Y 平面（约 0.9MB `ByteArray`），旋转在拷贝时完成，
     走 `PlanarYUVLuminanceSource`，不再生成 Bitmap。
   - 触发条件也从「每 3 帧」收紧为「ML Kit 连续失败 3 帧后，每 4 帧一次」。

顺带修的编译问题：

- `decodeQrFromBitmap` 里 `var rotated` 被 lambda 捕获，无法智能转换，
  `rotated.recycle()` 编译不过；改为 `val` 局部变量。
- 顶层说明改成 `//` 文件注释，避免 KDoc 挂到 `DECODE_HINTS` 上。

### 行为变化

- 扫码页新增点击对焦和对焦圈。
- 扫码页新增手电筒按钮。
- 远距离二维码会自动尝试拉近。
- ML Kit 失败时会用 ZXing 再试一次。
- 离开扫码页后相机和识别器会被释放。
- 相机启动失败时会显示错误提示，不再直接崩溃。

### 测试与验证

- 用 Kotlin 2.2.10 + Compose 编译器插件，对 `QrScannerScreen.kt`、
  `ZxingQrFallback.kt`、`ScreenHeader.kt` 做了**完整类型检查编译**，
  真实 classpath 包含 android-34 / CameraX 1.4.2 / ML Kit 17.3.0 /
  ZXing 3.5.3 / Compose 1.10.4 / material3 1.4.0：**0 error、0 warning**。
- 用脚本验证了 Y 平面旋转索引在 0/90/180/270 下与标准顺时针旋转一致，
  且是双射（4x3、3x2、1280x720、720x1280 全部通过）。
- 已用 CameraX 1.4.2、ML Kit 17.3.0、ZXing 3.5.3 的 AAR/JAR 校验关键 API：
  - `ResolutionSelector` / `ResolutionStrategy`
  - `FocusMeteringAction`
  - `ZoomSuggestionOptions`
  - `ImageProxy.toBitmap()`
  - `Tasks.await(...)`
  - ZXing `RGBLuminanceSource` / `HybridBinarizer` / `MultiFormatReader`
- **尚未完成完整编译和真机测试。**

### 构建阻塞

当前环境缺少：

- Android SDK 36
- JDK 21（系统默认是 JDK 25；IntelliJ 自带 JDK 21 可用）
- Rust / cargo
- cargo-ndk
- Android NDK 27.0.12077973
- `app/src/main/jniLibs/arm64-v8a/libwinsignin.so`

Gradle 实际报错：

```text
SDK location not found. Define a valid SDK location with an
ANDROID_HOME environment variable or by setting the sdk.dir path
in local.properties at .../winSignIn/local.properties
```

因此没有生成：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 已知限制

- 还没有真机验证远距离、斜角度、低光下的实际提升。
- ZXing 兜底每 3 帧执行一次，1280×720 的 Bitmap 转换和 `TRY_HARDER` 会增加 CPU 开销；如果实测掉帧明显，可以改成每 5 帧一次或移除。
- ML Kit 自动变焦可能覆盖手动双指缩放，这是当前实现的预期行为。
- 没有直接替换 ML Kit，ZXing 只作为兜底；ML Kit 仍负责主要识别和自动变焦。
- 当前没有修改版本号，`versionName` 仍为 `1.2`。

### 回滚方式

1. 恢复 `QrScannerScreen.kt` 到修改前版本。
2. 删除 `ZxingQrFallback.kt`。
3. 从 `app/build.gradle.kts` 移除：

```kotlin
implementation("com.google.zxing:core:3.5.3")
```

### 相关文档

- `scannerTest/README.md`：扫码效果 A/B 试验（简版）
- `scannerTest/optimization-plan.md`：扫码优化方案与优先级
