//! HTTP 后端入口。
//!
//! ```bash
//! # 本机跑（默认 127.0.0.1:8080）
//! cargo run --bin server --features server
//!
//! # 让 iPhone 在同一个 WiFi 下访问，并托管前端静态目录
//! WINSIGNIN_ADDR=0.0.0.0:8080 WINSIGNIN_STATIC=../web/dist \
//!   cargo run --bin server --features server
//!
//! # 发布到服务器
//! cargo build --release --bin server --features server
//! ```
//!
//! 注意：iOS Safari 要开相机必须走 HTTPS（localhost 除外），
//! 正式部署请在前面挂 Caddy / nginx 做 TLS 终止。

use std::net::SocketAddr;
use std::path::PathBuf;

fn main() {
    let addr = std::env::var("WINSIGNIN_ADDR").unwrap_or_else(|_| "127.0.0.1:8080".to_string());
    let static_dir = std::env::var("WINSIGNIN_STATIC").ok().map(PathBuf::from);

    let addr: SocketAddr = match addr.parse() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("WINSIGNIN_ADDR 格式不对（{addr}）：{e}");
            std::process::exit(1);
        }
    };

    let rt = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            eprintln!("创建 tokio runtime 失败：{e}");
            std::process::exit(1);
        }
    };

    rt.block_on(async move {
        let app = winsignin::http::router(winsignin::http::ServerConfig {
            static_dir: static_dir.clone(),
        });

        let listener = match tokio::net::TcpListener::bind(addr).await {
            Ok(l) => l,
            Err(e) => {
                eprintln!("监听 {addr} 失败：{e}");
                std::process::exit(1);
            }
        };

        println!("winsignin-server 已启动：http://{addr}");
        match &static_dir {
            Some(dir) => println!("前端静态目录：{}", dir.display()),
            None => println!("前端静态目录：未配置，返回内置冒烟测试页"),
        }
        println!("接口前缀：/api（GET /api/health 可探活）");

        if let Err(e) = axum::serve(listener, app).await {
            eprintln!("服务异常退出：{e}");
            std::process::exit(1);
        }
    });
}
