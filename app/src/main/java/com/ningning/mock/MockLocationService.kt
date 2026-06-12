package com.ningning.mock

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.random.Random

class MockLocationService : Service() {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private lateinit var wifiController: WifiController

    private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
    private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
    private val FUSED_PROVIDER = "fused"
    private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER

    private var currentLat = 0.0
    private var currentLng = 0.0
    private var useGcj02 = true  // 默认启用GCJ-02坐标修正
    private var pushCount = 0L
    private var gpsRegistered = false
    private var networkRegistered = false
    private var fusedRegistered = false
    private var passiveRegistered = false

    @Volatile var lastError: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val pushInterval = 300L
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
        useGcj02 = intent?.getBooleanExtra(EXTRA_USE_GCJ02, true) ?: true

        if (lat != 0.0 && lng != 0.0) {
            currentLat = lat
            currentLng = lng
        }

        lastError = null
        startForeground(NOTIFICATION_ID, buildNotification())

        val success = startMocking()
        if (!success) {
            showErrorNotification()
        }
        return START_NOT_STICKY
    }

    private fun startMocking(): Boolean {
        if (isRunning) return true

        wifiController.disableForMock()

        val gpsOk = registerProvider(GPS_PROVIDER)
        val netOk = registerProvider(NETWORK_PROVIDER)
        val fusedOk = tryRegisterExtra(FUSED_PROVIDER)
        val passiveOk = tryRegisterExtra(PASSIVE_PROVIDER)

        Log.d("MockService", "GPS=$gpsOk NET=$netOk FUSED=$fusedOk PASSIVE=$passiveOk useGcj02=$useGcj02")

        if (!gpsOk && !netOk) {
            lastError = lastError ?: "Provider注册失败，请确认已在开发者选项中将宁宁模拟设为模拟位置应用"
            wifiController.restoreWifiState()
            return false
        }

        LocationHooks.updateLastPosition(currentLat, currentLng)

        isRunning = true
        pushCount = 0
        handler.post(pushRunnable)
        return true
    }

    private fun tryRegisterExtra(provider: String): Boolean {
        return try {
            registerProvider(provider)
        } catch (e: Exception) {
            Log.d("MockService", "Extra provider $provider failed: ${e.message}")
            false
        }
    }

    private fun registerProvider(provider: String): Boolean {
        return try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

            when (provider) {
                GPS_PROVIDER -> gpsRegistered = true
                NETWORK_PROVIDER -> networkRegistered = true
                FUSED_PROVIDER -> fusedRegistered = true
                PASSIVE_PROVIDER -> passiveRegistered = true
            }

            Log.d("MockService", "Provider registered: $provider")
            true
        } catch (e: SecurityException) {
            lastError = "SecurityException: 请在开发者选项中设置模拟位置应用"
            false
        } catch (e: Exception) {
            Log.d("MockService", "Provider $provider error: ${e.message}")
            lastError = "Provider注册异常: ${e.message}"
            false
        }
    }

    private fun pushLocation() {
        if (!isRunning) return
        pushCount++

        // 坐标修正：WGS-84 → GCJ-02
        // 中国APP（钉钉等）内部使用GCJ-02坐标系
        // 推送GCJ-02坐标，APP读取后直接显示，位置正确
        val (pushLat, pushLng) = if (useGcj02) {
            LocationHooks.wgs84ToGcj02(currentLat, currentLng)
        } else {
            Pair(currentLat, currentLng)
        }

        // 添加微小漂移（模拟真实GPS信号波动，2-5米）
        val (driftedLat, driftedLng) = LocationHooks.applyDrift(pushLat, pushLng)

        pushToProvider(GPS_PROVIDER, gpsRegistered, driftedLat, driftedLng)
        pushToProvider(NETWORK_PROVIDER, networkRegistered, driftedLat, driftedLng)
        pushToProvider(FUSED_PROVIDER, fusedRegistered, driftedLat, driftedLng)
        pushToProvider(PASSIVE_PROVIDER, passiveRegistered, driftedLat, driftedLng)

        LocationHooks.updateLastPosition(currentLat, currentLng)

        if (pushCount % 20 == 0L) {
            updateNotification()
        }
    }

    private fun pushToProvider(provider: String, registered: Boolean, lat: Double, lng: Double) {
        if (!registered) return
        val loc = LocationHooks.buildRealisticLocation(provider, lat, lng)
        try {
            locationManager.setTestProviderLocation(provider, loc)
        } catch (e: Exception) {
            if (e is IllegalArgumentException || e is SecurityException) {
                Log.d("MockService", "Re-registering $provider")
                if (registerProvider(provider)) {
                    try {
                        locationManager.setTestProviderLocation(provider, loc)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopMocking() {
        isRunning = false
        handler.removeCallbacks(pushRunnable)

        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(FUSED_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(PASSIVE_PROVIDER) } catch (_: Exception) {}
        gpsRegistered = false
        networkRegistered = false
        fusedRegistered = false
        passiveRegistered = false

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
            fusedRegistered = fusedRegistered,
            passiveRegistered = passiveRegistered,
            wifiEnabled = wifiController.isWifiEnabled(),
            useGcj02 = useGcj02,
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
        val coordSys = if (useGcj02) "GCJ-02" else "WGS-84"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("宁宁模拟 v1.12 运行中")
            .setContentText("坐标: $coordSys | 正在提供位置信息")
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
        var providerInfo = "GPS"
        if (gpsRegistered) providerInfo += "+"
        if (networkRegistered) providerInfo += "NET"
        if (fusedRegistered) providerInfo += "+FUSED"
        if (passiveRegistered) providerInfo += "+PAS"

        val coordSys = if (useGcj02) "GCJ-02" else "WGS-84"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("宁宁模拟 v1.12")
            .setContentText("[$coordSys] ${pushCount}次 [$providerInfo] " +
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
        try { locationManager.removeTestProvider(FUSED_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(PASSIVE_PROVIDER) } catch (_: Exception) {}
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
        val fusedRegistered: Boolean,
        val passiveRegistered: Boolean,
        val wifiEnabled: Boolean,
        val useGcj02: Boolean,
        val mockLocationApp: String,
        val error: String? = null
    )

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_USE_GCJ02 = "extra_use_gcj02"
        private const val CHANNEL_ID = "ningning_location"
        private const val NOTIFICATION_ID = 1001
    }
}
