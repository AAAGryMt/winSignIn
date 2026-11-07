//! HTTP handlers：JSON 请求体 -> `crate::` 里现有的业务函数 -> JSON 响应。
//!
//! 这里刻意不做任何业务判断：成功/失败都体现在返回的 `ok` / `success` 字段里，
//! 和 Android 端拿到的是同一套语义。

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use rand::Rng;
use serde::Deserialize;

use crate::{
    AccountInfo, GeoPoint, LoginResult, NumberCodeResult, RadarTarget, RollcallsResult, SignResult,
    VerifyResult,
};

// ---------------------------------------------------------------- 错误

/// 只用于「请求本身有问题」的情况（JSON 解析失败、参数缺失）。
/// 业务失败走返回体里的 ok/success，不使用 HTTP 错误码。
pub struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    fn internal(message: String) -> Self {
        Self {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            message,
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(serde_json::json!({ "error": self.message })),
        )
            .into_response()
    }
}

/// 把阻塞业务放到 blocking pool 上跑，避免「在 runtime 里 block 另一个 runtime」。
async fn blocking<T, F>(f: F) -> Result<T, ApiError>
where
    F: FnOnce() -> T + Send + 'static,
    T: Send + 'static,
{
    tokio::task::spawn_blocking(f)
        .await
        .map_err(|e| ApiError::internal(format!("任务执行失败：{e}")))
}

// ---------------------------------------------------------------- 请求体

#[derive(Deserialize)]
pub struct LoginReq {
    pub username: String,
    pub password: String,
    /// 金智浏览器指纹。留空时后端生成一个并返回给调用方持久化。
    #[serde(default)]
    pub bfp: String,
    #[serde(default)]
    pub remember_me: bool,
    #[serde(default)]
    pub cas_cookie: String,
}

#[derive(Deserialize)]
pub struct RefreshReq {
    #[serde(default)]
    pub cas_cookie: String,
}

#[derive(Deserialize)]
pub struct CookieReq {
    pub cookie: String,
}

#[derive(Deserialize)]
pub struct NumberCodeReq {
    pub cookie: String,
    pub rollcall_id: String,
}

#[derive(Deserialize)]
pub struct SignQrReq {
    pub cookie: String,
    #[serde(default)]
    pub device_id: String,
    pub raw_qr: String,
}

#[derive(Deserialize)]
pub struct SignNumberReq {
    pub cookie: String,
    #[serde(default)]
    pub device_id: String,
    pub rollcall_id: String,
    pub number_code: String,
}

#[derive(Deserialize)]
pub struct SignRadarReq {
    pub cookie: String,
    #[serde(default)]
    pub device_id: String,
    pub rollcall_id: String,
    pub latitude: f64,
    pub longitude: f64,
}

/// 只需要 (cookie, rollcall_id) 的请求：回查签到状态、雷达空请求签到。
#[derive(Deserialize)]
pub struct CookieRollcallReq {
    pub cookie: String,
    pub rollcall_id: String,
}

#[derive(Deserialize)]
pub struct SolveReq {
    pub points: Vec<GeoPoint>,
    pub distances: Vec<f64>,
}

// ---------------------------------------------------------------- 参数兜底

/// 和设备端 `bfpFor` 保持一致：16 字节 hex、大写。
fn ensure_bfp(bfp: String) -> String {
    if !bfp.trim().is_empty() {
        return bfp.trim().to_string();
    }
    let mut rng = rand::thread_rng();
    (0..16)
        .map(|_| format!("{:02X}", rng.gen::<u8>()))
        .collect()
}

/// deviceId 用于签到去重，必须每个账号不同。
/// 网页端没有稳定的设备标识，缺省时由后端生成一个 UUID v4。
fn ensure_device_id(device_id: String) -> String {
    if !device_id.trim().is_empty() {
        return device_id.trim().to_string();
    }
    use rand::RngCore;
    let mut b = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut b);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let h: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &h[0..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..32]
    )
}

// ---------------------------------------------------------------- handlers

pub async fn health() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "ok": true,
        "service": "winsignin-server",
        "version": env!("CARGO_PKG_VERSION"),
    }))
}

pub async fn login(Json(req): Json<LoginReq>) -> Result<Json<LoginResult>, ApiError> {
    let bfp = ensure_bfp(req.bfp);
    let out = blocking(move || {
        crate::login(
            req.username,
            req.password,
            bfp,
            req.remember_me,
            req.cas_cookie,
        )
    })
    .await?;
    Ok(Json(out))
}

pub async fn refresh_session(Json(req): Json<RefreshReq>) -> Result<Json<LoginResult>, ApiError> {
    let out = blocking(move || crate::refresh_session(req.cas_cookie)).await?;
    Ok(Json(out))
}

pub async fn get_account(Json(req): Json<CookieReq>) -> Result<Json<AccountInfo>, ApiError> {
    let out = blocking(move || crate::get_account(req.cookie)).await?;
    Ok(Json(out))
}

pub async fn get_rollcalls(Json(req): Json<CookieReq>) -> Result<Json<RollcallsResult>, ApiError> {
    let out = blocking(move || crate::get_rollcalls(req.cookie)).await?;
    Ok(Json(out))
}

pub async fn get_number_code(
    Json(req): Json<NumberCodeReq>,
) -> Result<Json<NumberCodeResult>, ApiError> {
    let out = blocking(move || crate::get_number_code(req.cookie, req.rollcall_id)).await?;
    Ok(Json(out))
}

pub async fn verify_signed(Json(req): Json<CookieRollcallReq>) -> Result<Json<VerifyResult>, ApiError> {
    let out = blocking(move || crate::verify_signed(req.cookie, req.rollcall_id)).await?;
    Ok(Json(out))
}

pub async fn sign_qr(Json(req): Json<SignQrReq>) -> Result<Json<SignResult>, ApiError> {
    let device_id = ensure_device_id(req.device_id);
    let out = blocking(move || crate::sign_qr(req.cookie, device_id, req.raw_qr)).await?;
    Ok(Json(out))
}

pub async fn sign_number(Json(req): Json<SignNumberReq>) -> Result<Json<SignResult>, ApiError> {
    let device_id = ensure_device_id(req.device_id);
    let out = blocking(move || {
        crate::sign_number(req.cookie, device_id, req.rollcall_id, req.number_code)
    })
    .await?;
    Ok(Json(out))
}

pub async fn sign_radar(Json(req): Json<SignRadarReq>) -> Result<Json<SignResult>, ApiError> {
    let device_id = ensure_device_id(req.device_id);
    let out = blocking(move || {
        crate::sign_radar(
            req.cookie,
            device_id,
            req.rollcall_id,
            req.latitude,
            req.longitude,
        )
    })
    .await?;
    Ok(Json(out))
}

pub async fn sign_radar_empty(Json(req): Json<CookieRollcallReq>) -> Result<Json<SignResult>, ApiError> {
    let out = blocking(move || crate::sign_radar_empty(req.cookie, req.rollcall_id)).await?;
    Ok(Json(out))
}

pub async fn solve_radar_target(Json(req): Json<SolveReq>) -> Result<Json<RadarTarget>, ApiError> {
    let out = blocking(move || crate::solve_radar_target(req.points, req.distances)).await?;
    Ok(Json(out))
}
