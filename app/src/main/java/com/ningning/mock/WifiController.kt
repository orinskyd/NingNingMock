package com.ningning.mock

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * WiFi控制器 — 按紫星路线自动关闭/恢复WiFi
 *
 * 原因：高德/百度等定位SDK会使用WiFi扫描来获取位置，
 * 这会覆盖通过 LocationManager 模拟的GPS数据。
 * 因此必须在模拟期间关闭WiFi。
 *
 * Android 10+ 限制：普通应用无法通过代码关闭WiFi，
 * 只能提示用户手动操作。
 */
class WifiController(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var wasWifiEnabled = false

    /**
     * 检查WiFi当前状态
     */
    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    /**
     * 是否可以自动控制WiFi
     * Android 10 (Q, API 29) 及以上不支持第三方应用关闭WiFi
     */
    fun canAutoControl(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * 模拟开始前：保存WiFi状态并关闭
     * @return true=已关闭, false=需要用户手动操作
     */
    fun disableForMock(): Boolean {
        wasWifiEnabled = wifiManager.isWifiEnabled

        if (!wasWifiEnabled) {
            Log.d("WifiController", "WiFi已经关闭，无需操作")
            return true
        }

        if (canAutoControl()) {
            val result = wifiManager.setWifiEnabled(false)
            Log.d("WifiController", "自动关闭WiFi: $result")
            return result
        } else {
            Log.d("WifiController", "Android 10+不支持自动关闭WiFi，需用户手动操作")
            return false
        }
    }

    /**
     * 模拟停止后：恢复到之前的WiFi状态
     */
    fun restoreWifiState() {
        if (!wasWifiEnabled) {
            Log.d("WifiController", "WiFi之前就是关闭的，不恢复")
            return
        }

        if (canAutoControl()) {
            wifiManager.setWifiEnabled(true)
            Log.d("WifiController", "恢复WiFi: true")
        }
        // Android 10+ 不自动打开WiFi，避免用户反感
    }
}
