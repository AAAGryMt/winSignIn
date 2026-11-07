# winsignin-server

把 `rust/src/` 里现有的业务逻辑（CAS 登录链、签到、点名、二维码解包、三边定位）
包成 HTTP 接口，供 Web 前端（iOS Safari）调用。

**业务代码没有重写**：`cas.rs` / `qr.rs` / `radar.rs` / `lib.rs` 一行未改，
`http/` 只做「JSON ↔ 函数参数」的转换。

## 为什么需要后端

iOS 只能走浏览器，而浏览器有三条绕不过去的限制：

1. **混合内容**：HTTPS 页面不能请求明文 `http://lms.tc.cqupt.edu.cn`
2. **CORS**：畅课接口不返回 CORS 头，浏览器直连会被拦
3. **CAS cookie**：`CASTGC` / `session` 属于学校域，前端拿不到也不该拿

所以 CAS 登录链必须在服务端完成。

## 运行

```bash
# 本机（默认 127.0.0.1:8080），不带前端则返回内置冒烟测试页
cargo run --bin server --features server

# 同 WiFi 下让 iPhone 访问，并托管前端静态目录
WINSIGNIN_ADDR=0.0.0.0:8080 \
WINSIGNIN_STATIC=../web/dist \
cargo run --bin server --features server

# 发布（单文件二进制）
cargo build --release --bin server --features server
```

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `WINSIGNIN_ADDR` | `127.0.0.1:8080` | 监听地址；对外提供服务时用 `0.0.0.0:8080` |
| `WINSIGNIN_STATIC` | 空 | 前端静态目录；未设置时返回内置测试页 |

## 接口

统一 `POST` + `application/json`。需要 cookie 的接口**刻意不用 GET + query**：
cookie 是会话凭据，放 URL 里会进 access log / 浏览器历史 / Referer。

| 方法 | 路径 | 请求体 | 返回 |
|---|---|---|---|
| GET | `/api/health` | — | `{ok,service,version}` |
| POST | `/api/login` | `{username,password,bfp?,remember_me?,cas_cookie?}` | `LoginResult` |
| POST | `/api/session/refresh` | `{cas_cookie}` | `LoginResult` |
| POST | `/api/account` | `{cookie}` | `AccountInfo` |
| POST | `/api/rollcalls` | `{cookie}` | `RollcallsResult` |
| POST | `/api/rollcalls/number-code` | `{cookie,rollcall_id}` | `NumberCodeResult` |
| POST | `/api/rollcalls/verify` | `{cookie,rollcall_id}` | `VerifyResult` |
| POST | `/api/sign/qr` | `{cookie,device_id?,raw_qr}` | `SignResult` |
| POST | `/api/sign/number` | `{cookie,device_id?,rollcall_id,number_code}` | `SignResult` |
| POST | `/api/sign/radar` | `{cookie,device_id?,rollcall_id,latitude,longitude}` | `SignResult` |
| POST | `/api/sign/radar/empty` | `{cookie,rollcall_id}` | `SignResult` |
| POST | `/api/radar/solve` | `{points:[{lat,lon}],distances:[f64]}` | `RadarTarget` |

返回体就是 Android 端拿到的同一套 `uniffi::Record`（已 derive `Serialize`），
业务失败体现在 `ok` / `success` 字段里，不使用 HTTP 错误码。

`device_id` 留空时后端生成 UUID v4。签到按设备去重，**每个账号必须用不同的 device_id**。
`bfp` 留空时后端生成 16 字节大写 hex。

## 部署

单个静态二进制，前面挂 Caddy 做 TLS：

```text
example.com       → 前端静态文件（或交给 WINSIGNIN_STATIC）
example.com/api/* → 127.0.0.1:8080
```

iOS Safari 要开相机**必须 HTTPS**（`localhost` 除外），浏览器里 `getUserMedia`
必须由用户手势触发。

## 注意

- 后端**不持久化密码**。登录时透传一次用完即弃，客户端只保存 `cookie` / `cas_cookie`。
  这样后端泄露最多暴露 CASTGC 有效期（约 14 天）内的会话，而不是永久密码。
- `CorsLayer::permissive()` 是为了本地用 iPhone 直连调试；生产请换成明确的 origin 白名单。
- 前端扫码不要依赖 `BarcodeDetector`（iOS Safari 默认不支持），用 `zxing-wasm` 之类解码。
- 学校接口随时可能变，`cas.rs` / `lib.rs` 的维护成本现在由后端承担。

## 验证

```bash
cargo check --lib                      # Android 路径不受影响
cargo check --features server --bin server
cargo test --lib                       # 21 个既有单测
```
