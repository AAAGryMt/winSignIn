package org.example.winsignin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前是否有可用网络：有激活网络，且系统已确认能连互联网（VALIDATED）。
 * 连着 WiFi 但没外网（如 captive portal）也算无网。
 */
fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * 网络类错误统一追加一句提醒，返回原样 otherwise。
 * Rust 侧所有网络失败都以「网络错误：」开头（lib.rs 的 send/interpret、cas.rs 的请求路径），
 * 在这里统一判断，调用点不用各自记规则。
 */
fun netHint(message: String): String =
    if (message.contains("网络错误")) "$message（请检查网络连接）" else message
