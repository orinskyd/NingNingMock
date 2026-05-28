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

    private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
    private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER

    private var currentLat = 0.0
    private var currentLng = 0.0
    private var pushCount = 0L
    private var gpsRegistered = false
    private var networkRegistered = false

    // 错误信息
    @Volatile var lastError: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val pushInterval = 500L
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

        lastError = null

        // FIX v1.5: 必须在onStartCommand中立即调用startForeground
        // Android 9+ 要求 startForegroundService 后5秒内必须调用 startForeground，
        // 否则系统会直接杀死APP（闪退）
        startForeground(NOTIFICATION_ID, buildNotification())

        val success = startMocking()
        if (!success) {
            // Provider注册失败，更新通知显示错误（不要stopSelf，让Activity来处理）
            showErrorNotification()
        }
        return START_NOT_STICKY
    }

    /**
     * 开始模拟：注册Provider + 推送循环
     * 注意：startForeground 已在 onStartCommand 中调用，此处不再重复调用
     * @return true = 成功, false = 失败
     */
    private fun startMocking(): Boolean {
        if (isRunning) return true

        // WiFi控制
        wifiController.disableForMock()

        // 直接尝试注册Provider，不提前检查权限
        // 如果权限不对，registerProvider会抛SecurityException，我们捕获后报错
        val gpsOk = registerProvider(GPS_PROVIDER)
        val netOk = registerProvider(NETWORK_PROVIDER)

        if (!gpsOk && !netOk) {
            lastError = lastError ?: "Provider注册失败，请确认已在开发者选项中将「宁宁模拟」设为模拟位置应用"
            wifiController.restoreWifiState()
            return false
        }

        // 前台通知已在 onStartCommand 中调用

        LocationHooks.updateLastPosition(currentLat, currentLng)

        isRunning = true
        pushCount = 0
        handler.post(pushRunnable)
        return true
    }

    /**
     * 检查是否设置了正确的模拟位置应用
     * 兼容 Android 14+ ：Settings.Secure.mock_location 可能返回 "0" 或其他值
     * 优先通过 AppOpsManager 检查
     */
    private fun checkMockPermission(): Boolean {
        // 方式1: AppOpsManager 检查（最可靠）
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    "android:mock_location",
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOp(
                    "android:mock_location",
                    android.os.Process.myUid(),
                    packageName
                )
            }
            if (result == android.app.AppOpsManager.MODE_ALLOWED) return true
        } catch (_: Exception) {}

        // 方式2: Settings.Secure 回退检查
        return try {
            val mockApp = Settings.Secure.getString(contentResolver, "mock_location")
            mockApp == packageName
        } catch (_: Exception) {
            true // 无法读取时继续尝试
        }
    }

    /**
     * 注册 Mock Provider
     * @return true = 成功
     */
    private fun registerProvider(provider: String): Boolean {
        return try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 使用反射调用新版API
                val builderClass = Class.forName("android.location.provider.ProviderProperties\$Builder")
                val builder = builderClass.getConstructor().newInstance()
                builderClass.getMethod("setHasAltitudeSupport", Boolean::class.java).invoke(builder, true)
                builderClass.getMethod("setHasSpeedSupport", Boolean::class.java).invoke(builder, true)
                builderClass.getMethod("setHasBearingSupport", Boolean::class.java).invoke(builder, true)
                builderClass.getMethod("setPowerUsage", Int::class.java).invoke(builder, 1)
                builderClass.getMethod("setAccuracy", Int::class.java).invoke(builder, 1)
                val props = builderClass.getMethod("build").invoke(builder)
                val method = LocationManager::class.java.getMethod("addTestProvider",
                    String::class.java, props.javaClass)
                method.invoke(locationManager, provider, props)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    false, true, false, false, true, true, true,
                    Criteria.POWER_MEDIUM, Criteria.ACCURACY_FINE
                )
            }

            locationManager.setTestProviderEnabled(provider, true)

            if (provider == GPS_PROVIDER) gpsRegistered = true
            else networkRegistered = true

            true
        } catch (e: SecurityException) {
            // 模拟位置应用未设置
            lastError = "SecurityException: 请在开发者选项中设置模拟位置应用"
            false
        } catch (e: Exception) {
            lastError = "Provider注册异常: ${e.message}"
            false
        }
    }

    /**
     * 推送位置
     */
    private fun pushLocation() {
        if (!isRunning) return
        pushCount++

        val (driftedLat, driftedLng) = LocationHooks.applyDrift(currentLat, currentLng)

        if (gpsRegistered) {
            val gpsLoc = LocationHooks.buildRealisticLocation(GPS_PROVIDER, driftedLat, driftedLng)
            try {
                locationManager.setTestProviderLocation(GPS_PROVIDER, gpsLoc)
            } catch (e: Exception) {
                // Provider可能被系统移除了，尝试重新注册
                if (e is IllegalArgumentException || e is SecurityException) {
                    gpsRegistered = false
                    if (registerProvider(GPS_PROVIDER)) {
                        try {
                            locationManager.setTestProviderLocation(GPS_PROVIDER, gpsLoc)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        if (networkRegistered) {
            val netLoc = LocationHooks.buildRealisticLocation(NETWORK_PROVIDER, driftedLat, driftedLng)
            try {
                locationManager.setTestProviderLocation(NETWORK_PROVIDER, netLoc)
            } catch (e: Exception) {
                if (e is IllegalArgumentException || e is SecurityException) {
                    networkRegistered = false
                    if (registerProvider(NETWORK_PROVIDER)) {
                        try {
                            locationManager.setTestProviderLocation(NETWORK_PROVIDER, netLoc)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        LocationHooks.updateLastPosition(currentLat, currentLng)

        if (pushCount % 20 == 0L) {
            updateNotification()
        }
    }

    fun stopMocking() {
        isRunning = false
        handler.removeCallbacks(pushRunnable)

        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        gpsRegistered = false
        networkRegistered = false

        wifiController.restoreWifiState()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun getStatus(): MockStatus {
        return MockStatus(
            isRunning = isRunning,
            lat = currentLat,
            lng = currentLng,
            pushCount = pushCount,
            gpsRegistered = gpsRegistered,
            networkRegistered = networkRegistered,
            wifiEnabled = wifiController.isWifiEnabled(),
            mockLocationApp = getMockLocationApp(),
            error = lastError
        )
    }

    private fun getMockLocationApp(): String {
        return try {
            Settings.Secure.getString(contentResolver, "mock_location") ?: "未设置"
        } catch (_: Exception) { "无法读取" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "位置服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "位置服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置服务运行中")
            .setContentText("正在提供位置信息")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showErrorNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("模拟启动失败")
            .setContentText(lastError ?: "请检查开发者选项设置")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置服务运行中")
            .setContentText("已推送 ${pushCount}次 | " +
                    "%.4f, %.4f".format(currentLat, currentLng))
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
        val mockLocationApp: String,
        val error: String? = null
    )

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private const val CHANNEL_ID = "ningning_location"
        private const val NOTIFICATION_ID = 1001
    }
}
