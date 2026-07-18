package com.ningning.mock

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
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

    // WakeLock: 防止CPU休眠导致Handler.postDelayed回调被冻结
    // 这是后台模拟定位被覆盖的根本原因之一
    private var wakeLock: PowerManager.WakeLock? = null

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
    private val pushInterval = 100L  // 100ms推送间隔
    private val pushRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, pushInterval)
        }
    }

    private var isRunning = false

    /**
     * 真实定位拦截器：监听GPS和Network的真实定位更新
     * 一旦检测到真实定位，立即推送模拟位置覆盖它
     */
    private val realLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d("MockService", "拦截到真实定位 from ${location.provider}，立即覆盖！")
            pushLocation()
            handler.post { pushLocation() }
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

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

        // START_STICKY: 系统杀掉Service后会自动重启
        // 配合 stopWithTask=false 确保APP退出后Service仍存活
        return START_STICKY
    }

    /**
     * 用户从最近任务列表划掉APP时触发
     * 重新启动Service，确保模拟不被中断
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("MockService", "onTaskRemoved - 重新启动Service")
        val restartIntent = Intent(applicationContext, MockLocationService::class.java).apply {
            putExtra(EXTRA_LAT, currentLat)
            putExtra(EXTRA_LNG, currentLng)
            putExtra(EXTRA_USE_GCJ02, useGcj02)
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 1秒后重启
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NingNingMock::LocationPush"
            )
            wakeLock?.setReferenceCounted(false)
            // 永久持有，直到模拟停止
            wakeLock?.acquire()
            Log.d("MockService", "WakeLock已获取 - CPU保持唤醒")
        } catch (e: Exception) {
            Log.d("MockService", "WakeLock获取失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("MockService", "WakeLock已释放")
            }
        } catch (_: Exception) {}
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

        // 获取WakeLock，防止CPU休眠导致推送停止
        acquireWakeLock()

        isRunning = true
        pushCount = 0

        // Burst push: 启动时快速推送5次
        for (i in 1..5) {
            pushLocation()
        }
        Log.d("MockService", "Burst push完成（5次），开始持续推送")

        // 注册真实定位监听器
        try {
            if (gpsOk) {
                locationManager.requestLocationUpdates(GPS_PROVIDER, 0L, 0f, realLocationListener, Looper.getMainLooper())
                Log.d("MockService", "GPS真实定位监听已注册")
            }
            if (netOk) {
                locationManager.requestLocationUpdates(NETWORK_PROVIDER, 0L, 0f, realLocationListener, Looper.getMainLooper())
                Log.d("MockService", "Network真实定位监听已注册")
            }
        } catch (e: SecurityException) {
            Log.d("MockService", "监听注册失败（权限）: ${e.message}")
        }

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

        val (pushLat, pushLng) = if (useGcj02) {
            LocationHooks.wgs84ToGcj02(currentLat, currentLng)
        } else {
            Pair(currentLat, currentLng)
        }

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

        try {
            locationManager.removeUpdates(realLocationListener)
            Log.d("MockService", "真实定位监听已注销")
        } catch (_: Exception) {}

        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(FUSED_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(PASSIVE_PROVIDER) } catch (_: Exception) {}
        gpsRegistered = false
        networkRegistered = false
        fusedRegistered = false
        passiveRegistered = false

        releaseWakeLock()
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
            .setContentTitle("宁宁模拟 v1.14 运行中")
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
            .setContentTitle("宁宁模拟 v1.14")
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
        try { locationManager.removeUpdates(realLocationListener) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(NETWORK_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(FUSED_PROVIDER) } catch (_: Exception) {}
        try { locationManager.removeTestProvider(PASSIVE_PROVIDER) } catch (_: Exception) {}
        releaseWakeLock()
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
