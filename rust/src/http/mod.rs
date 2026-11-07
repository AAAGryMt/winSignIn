//! HTTP 层：把 `lib.rs` 里那些 `uniffi::export` 的纯函数包成 axum 接口。
//!
//! 设计取向：**只做转换，不重写业务**。
//! CAS 登录链（`cas.rs`）、签到请求（`lib.rs`）、二维码解包（`qr.rs`）、
//! 三边定位（`radar.rs`）一行都不改，这里只负责：
//!   1. JSON 请求 -> 函数参数
//!   2. 函数返回值（`uniffi::Record`，已 derive `Serialize`）-> JSON 响应
//!   3. 把阻塞调用丢到 `spawn_blocking`，不阻塞 axum 的异步 worker
//!
//! 为什么必须 `spawn_blocking`：
//! `lib.rs` 里的函数内部用 `runtime().block_on(...)` 跑自己的 tokio Runtime。
//! 直接在 handler 里调用会「在 runtime 里再 block 一个 runtime」而 panic；
//! 放到 blocking pool 上调用就等价于 `reqwest::blocking` 的用法，是安全的。
//!
//! 所有需要 cookie 的接口都用 POST + JSON body，而不是 GET + query：
//! cookie 是会话凭据，放在 URL 里会进 access log、浏览器历史和 Referer。

mod handlers;

use std::path::PathBuf;

use axum::Router;
use tower_http::cors::CorsLayer;
use tower_http::services::ServeDir;
use tower_http::trace::TraceLayer;

use handlers::*;

/// 服务配置。两个字段都可以用环境变量覆盖，见 `bin/server.rs`。
pub struct ServerConfig {
    /// 前端静态目录；为 None 时返回内置的冒烟测试页。
    pub static_dir: Option<PathBuf>,
}

/// 组装路由。
///
/// 生产环境请把 `CorsLayer::permissive()` 换成明确的 origin 白名单；
/// 这里放开是为了本地用 iPhone Safari 直连调试。
pub fn router(cfg: ServerConfig) -> Router {
    let api = Router::new()
        .route("/health", axum::routing::get(health))
        // 登录 / 会话
        .route("/login", axum::routing::post(login))
        .route("/session/refresh", axum::routing::post(refresh_session))
        .route("/account", axum::routing::post(get_account))
        // 点名
        .route("/rollcalls", axum::routing::post(get_rollcalls))
        .route("/rollcalls/number-code", axum::routing::post(get_number_code))
        .route("/rollcalls/verify", axum::routing::post(verify_signed))
        // 签到
        .route("/sign/qr", axum::routing::post(sign_qr))
        .route("/sign/number", axum::routing::post(sign_number))
        .route("/sign/radar", axum::routing::post(sign_radar))
        .route("/sign/radar/empty", axum::routing::post(sign_radar_empty))
        // 计算
        .route("/radar/solve", axum::routing::post(solve_radar_target));

    let app = Router::new().nest("/api", api);

    let app = match cfg.static_dir {
        Some(dir) => {
            if !dir.is_dir() {
                warn(format!("静态目录不存在，回退到内置测试页: {}", dir.display()));
                app.route("/", axum::routing::get(index_html))
            } else {
                app.route("/", axum::routing::get(index_html))
                    .fallback_service(ServeDir::new(dir))
            }
        }
        None => app.route("/", axum::routing::get(index_html)),
    };

    app.layer(TraceLayer::new_for_http())
        .layer(CorsLayer::permissive())
}

fn warn(msg: String) {
    // 只在这里用一次，避免为一个字符串引入 tracing 依赖。
    eprintln!("[warn] {msg}");
}

/// 内置冒烟测试页：不依赖前端工程，打开就能验证 iOS Safari 能不能用。
/// 真正的前端请放到 `--static-dir` 指定的目录里。
async fn index_html() -> axum::response::Html<&'static str> {
    axum::response::Html(INDEX_HTML)
}

const INDEX_HTML: &str = r##"<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>winSignIn · 后端冒烟测试</title>
<style>
*{box-sizing:border-box}
body{margin:0;padding:16px;font:15px/1.5 -apple-system,system-ui,sans-serif;background:#0f172a;color:#e2e8f0}
h1{font-size:18px;margin:0 0 12px}
fieldset{border:1px solid #334155;border-radius:10px;margin:0 0 12px;padding:10px}
legend{padding:0 6px;color:#94a3b8;font-size:13px}
input,textarea,button{font:inherit;width:100%;padding:9px;border-radius:8px;border:1px solid #475569;background:#1e293b;color:#e2e8f0}
textarea{min-height:64px;resize:vertical}
button{margin-top:8px;background:#2563eb;border-color:#2563eb;font-weight:600}
button:active{opacity:.8}
pre{white-space:pre-wrap;word-break:break-all;background:#1e293b;border-radius:8px;padding:10px;font-size:12px;min-height:80px}
.row{display:flex;gap:8px}.row>*{flex:1}
</style>
</head>
<body>
<h1>winSignIn · 后端冒烟测试</h1>

<fieldset><legend>账号</legend>
<input id="u" placeholder="统一认证码" autocomplete="username">
<input id="p" type="password" placeholder="密码" autocomplete="current-password" style="margin-top:8px">
<button onclick="doLogin()">登录</button>
</fieldset>

<fieldset><legend>会话 cookie（登录成功后自动填入）</legend>
<textarea id="cookie" placeholder="cookie"></textarea>
<textarea id="cas" placeholder="cas_cookie" style="margin-top:8px"></textarea>
<div class="row">
<button onclick="call('/api/account',{cookie:V('cookie')})">账号信息</button>
<button onclick="call('/api/rollcalls',{cookie:V('cookie')})">点名列表</button>
</div>
</fieldset>

<fieldset><legend>签到</legend>
<textarea id="qr" placeholder="扫到的二维码原文（iOS 端请用 zxing-wasm 解码后填这里）"></textarea>
<button onclick="signQr()">二维码签到</button>
</fieldset>

<pre id="out">等待操作…</pre>

<script>
const $ = id => document.getElementById(id);
const V = id => $(id).value.trim();
const out = t => { $('out').textContent = t; };

async function call(path, body) {
  out('请求 ' + path + ' …');
  try {
    const r = await fetch(path, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body),
    });
    const j = await r.json().catch(() => ({error: 'non-json ' + r.status}));
    out(r.status + '\n' + JSON.stringify(j, null, 2));
    return j;
  } catch (e) {
    out('请求失败：' + e);
    return null;
  }
}

async function doLogin() {
  const j = await call('/api/login', {
    username: V('u'), password: V('p'), remember_me: true, cas_cookie: V('cas'),
  });
  if (j && j.cookie) { $('cookie').value = j.cookie; $('cas').value = j.cas_cookie || ''; }
}

async function signQr() {
  await call('/api/sign/qr', {
    cookie: V('cookie'), device_id: 'web-device', raw_qr: V('qr'),
  });
}
</script>
</body>
</html>"##;
