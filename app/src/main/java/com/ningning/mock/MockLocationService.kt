package com.ningning.mock

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlin.random.Random

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private lateinit var wifiController: WifiController

    // 双Provider名称
    private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
    private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER

    // 当前模拟坐标
    private var currentLat = 0.0
    private var currentLng = 0.0

    // 推送计数器
    private var pushCount = 0L

    // Provider是否已注册
    private var gpsRegistered = false
    private var networkRegistered = false

    // 定时推送Handler
    private val handler = Handler(Looper.getMainLooper())
    private val pushInterval = 500L // 500ms推送间隔
    private val pushRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, pushInterval)
        }
    }

    private var isRunning = false

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationService = this@MockLocationService
    }

    override fun onBind(intent: Intent?) = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiController = WifiController(this)
        LocationHooks.init()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        val lng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0

        if (lat != 0.0 && lng != 0.0) {
            currentLat = lat
            currentLng = lng
        }

        startMocking()
        return START_STICKY
    }

    /**
     * 开始模拟：注册Provider + 启动推送循环
     */
    private fun startMocking() {
        if (isRunning) return

        // WiFi自动控制
        wifiController.disableForMock()

        // 注册GPS Provider
        registerProvider(GPS_PROVIDER)

        // 注册NETWORK Provider
        registerProvider(NETWORK_PROVIDER)

        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification())

        // 初始化位置记忆
        LocationHooks.updateLastPosition(currentLat, currentLng)

        // 开始推送循环
        isRunning = true
        pushCount = 0
        handler.post(pushRunnable)
    }

    /**
     * 注册Mock Provider（兼容新旧API）
     */
    private fun registerProvider(provider: String) {
        try {
            // 先移除旧的（如果存在）
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {}

            // 使用反射兼容新旧API
            try {
                // 尝试新版API (Android S+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val builderClass = Class.forName("android.location.provider.ProviderProperties\$Builder")
                    val builder = builderClass.getConstructor().newInstance()
                    builderClass.getMethod("setHasAltitudeSupport", Boolean::class.java).invoke(builder, true)
                    builderClass.getMethod("setHasSpeedSupport", Boolean::class.java).invoke(builder, true)
                    builderClass.getMethod("setHasBearingSupport", Boolean::class.java).invoke(builder, true)
                    builderClass.getMethod("setPowerUsage", Int::class.java).invoke(builder, 1) // POWER_USAGE_MEDIUM=1
                    builderClass.getMethod("setAccuracy", Int::class.java).invoke(builder, 1)   // ACCURACY_FINE=1
                    val props = builderClass.getMethod("build").invoke(builder)
                    val method = LocationManager::class.java.getMethod("addTestProvider", String::class.java, props.javaClass)
                    method.invoke(locationManager, provider, props)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        provider,
                        false, true, false, false, true, true, true,
                        Criteria.POWER_MEDIUM,
                        Criteria.ACCURACY_FINE
                    )
                }
            } catch (refError: Exception) {
                // 反射失败，降级使用旧API
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    false, true, false, false, true, true, true,
                    Criteria.POWER_MEDIUM,
                    Criteria.ACCURACY_FINE
                )
            }

            locationManager.setTestProviderEnabled(provider, true)

            if (provider == GPS_PROVIDER) gpsRegistered = true
            else networkRegistered = true

        } catch (e: SecurityException) {
            // 需要在开发者选项中启用"选择模拟位置信息应用"
            stopSelf()
        } catch (e: Exception) {
            // Provider已存在或其他错误
        }
    }

    /**
     * 推送位置到所有已注册的Provider
     */
    private fun pushLocation() {
        if (!isRunning) return
        pushCount++

        // 添加GPS自然漂移
        val (driftedLat, driftedLng) = LocationHooks.applyDrift(currentLat, currentLng)

        // 推送到GPS Provider
        if (gpsRegistered) {
            val gpsLoc = LocationHooks.buildRealisticLocation(GPS_PROVIDER, driftedLat, driftedLng)
            try {
                locationManager.setTestProviderLocation(GPS_PROVIDER, gpsLoc)
            } catch (_: Exception) {}
        }

        // 推送到NETWORK Provider
        if (networkRegistered) {
            val netLoc = LocationHooks.buildRealisticLocation(NETWORK_PROVIDER, driftedLat, driftedLng)
            try {
                locationManager.setTestProviderLocation(NETWORK_PROVIDER, netLoc)
            } catch (_: Exception) {}
        }

        // 更新位置记忆（用于方向角计算）
        LocationHooks.updateLastPosition(currentLat, currentLng)

        // 每20次推送更新一次通知（即每10秒）
        if (pushCount % 20 == 0L) {
            updateNotification()
        }
    }

    /**
     * 停止模拟
     */
    fun stopMocking() {
        isRunning = false
        handler.removeCallbacks(pushRunnable)

        // 移除Provider
        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        gpsRegistered = false
        networkRegistered = false

        // 恢复WiFi
        wifiController.restoreWifiState()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 更新当前坐标（Activity实时传入）
     */
    fun updateLocation(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): MockStatus {
        return MockStatus(
            isRunning = isRunning,
            lat = currentLat,
            lng = currentLng,
            pushCount = pushCount,
            gpsRegistered = gpsRegistered,
            networkRegistered = networkRegistered,
            wifiEnabled = wifiController.isWifiEnabled(),
            mockLocationApp = getMockLocationApp()
        )
    }

    /**
     * 获取系统设置的模拟位置信息应用
     */
    private fun getMockLocationApp(): String {
        return try {
            val mockApp = Settings.Secure.getString(
                contentResolver,
                "mock_location"
            )
            mockApp ?: "未设置"
        } catch (_: Exception) {
            "无法读取"
        }
    }

    /**
     * 创建通知渠道（Android O+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "宁宁模拟位置服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台通知 — 伪装为普通"位置服务"
     */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置服务运行中")
            .setContentText("正在提供位置信息")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 更新通知内容
     */
    private fun updateNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置服务运行中")
            .setContentText("已推送 ${pushCount}次 | 坐标: ${"%.4f".format(currentLat)}, ${"%.4f".format(currentLng)}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushRunnable)
        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        wifiController.restoreWifiState()
        super.onDestroy()
    }

    data class MockStatus(
        val isRunning: Boolean,
        val lat: Double,
        val lng: Double,
        val pushCount: Long,
        val gpsRegistered: Boolean,
        val networkRegistered: Boolean,
        val wifiEnabled: Boolean,
        val mockLocationApp: String
    )

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private const val CHANNEL_ID = "ningning_location"
        private const val NOTIFICATION_ID = 1001
    }
}
