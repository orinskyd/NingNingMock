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

    // === HandlerThread: 所有位置操作在独立线程，不阻塞UI ===
    private val locationThread = HandlerThread("NingNingLocation", Process.THREAD_PRIORITY_URGENT_DISPLAY)
    private lateinit var locationHandler: Handler

    // WakeLock: 防止CPU休眠导致Handler.postDelayed回调被冻结
    private var wakeLock: PowerManager.WakeLock? = null

    // WakeLock 续期 Handler（每30分钟续期一次，防止永久泄漏）
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                Log.d("MockService", "WakeLock renewed (30min)")
            } catch (e: Exception) {
                Log.d("MockService", "WakeLock renewal failed: ${e.message}")
            }
            locationHandler.postDelayed(this, WAKELOCK_RENEWAL_INTERVAL)
        }
    }

    private val GPS_PROVIDER = LocationManager.GPS_PROVIDER
    private val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
    private val FUSED_PROVIDER = "fused"
    private val PASSIVE_PROVIDER = LocationManager.PASSIVE_PROVIDER

    @Volatile private var currentLat = 0.0
    @Volatile private var currentLng = 0.0
    @Volatile private var useGcj02 = true
    @Volatile private var pushCount = 0L
    @Volatile private var consecutivePushFailures = 0  // v1.18: 连续推送失败计数
    @Volatile private var gpsRegistered = false
    @Volatile private var networkRegistered = false
    @Volatile private var fusedRegistered = false
    @Volatile private var passiveRegistered = false

    @Volatile var lastError: String? = null
        private set

    // 推送间隔: 50ms (后台线程，不影响UI)
    private val pushInterval = 50L

    private val pushRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            pushLocation()
            if (isRunning) {
                locationHandler.postDelayed(this, pushInterval)
            }
        }
    }

    @Volatile private var isRunning = false

    /**
     * 真实定位拦截器：监听GPS和Network的真实定位更新
     * 回调在locationHandler线程处理，不影响UI
     * 检测到真实定位时burst push 5次覆盖
     */
    private val realLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d("MockService", "Real loc from ${location.provider} - burst push 5x!")
            for (i in 1..5) {
                pushLocation()
            }
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
        locationThread.start()
        locationHandler = Handler(locationThread.looper)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiController = WifiController(this)
        LocationHooks.init()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 处理通知栏"停止"按钮
        if (intent?.action == ACTION_STOP) {
            Log.d("MockService", "Stop action received from notification")
            stopMocking()
            return START_NOT_STICKY
        }

        val lat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        val lng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0
        useGcj02 = intent?.getBooleanExtra(EXTRA_USE_GCJ02, true) ?: true

        if (lat != 0.0 && lng != 0.0) {
            currentLat = lat
            currentLng = lng
        }

        lastError = null
        startForeground(NOTIFICATION_ID, buildNotification())

        // 在后台线程执行 startMocking
        locationHandler.post {
            val success = startMocking()
            if (!success) {
                showErrorNotification()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("MockService", "onTaskRemoved - restart service")
        if (isRunning && currentLat != 0.0) {
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
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 1000,
                pendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    // ==================== WakeLock (v1.20: 带超时 + 自动续期) ====================

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NingNingMock::LocationPush"
            )
            wakeLock?.setReferenceCounted(false)
            // v1.20 修复：带超时防止泄漏，每30分钟自动续期
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            locationHandler.postDelayed(wakeLockRenewal, WAKELOCK_RENEWAL_INTERVAL)
            Log.d("MockService", "WakeLock acquired (timeout=${WAKELOCK_TIMEOUT_MS}ms)")
        } catch (e: Exception) {
            Log.d("MockService", "WakeLock fail: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            locationHandler.removeCallbacks(wakeLockRenewal)
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("MockService", "WakeLock released")
            }
        } catch (_: Exception) {}
    }

    /**
     * 完整清理：无论当前状态如何，先清理所有旧状态
     */
    private fun fullCleanup() {
        isRunning = false
        locationHandler.removeCallbacks(pushRunnable)

        try { locationManager.removeUpdates(realLocationListener) } catch (_: Exception) {}

        for (provider in listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER)) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}
        }

        gpsRegistered = false
        networkRegistered = false
        fusedRegistered = false
        passiveRegistered = false

        Log.d("MockService", "Full cleanup done")
    }

    /**
     * 清理 LocationManager 内部 lastKnownLocation 缓存
     */
    private fun clearLastKnownLocationCache() {
        try {
            val field = LocationManager::class.java.getDeclaredField("mLastKnownLocation")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = field.get(locationManager) as? HashMap<String, Location>
            cache?.clear()
            Log.d("MockService", "LastKnownLocation cache cleared")
        } catch (e: Exception) {
            Log.d("MockService", "Cache clear via field failed: ${e.message}")
        }
    }

    private fun startMocking(): Boolean {
        fullCleanup()

        // v1.18: 重置反检测状态
        LocationHooks.resetMockFlagStatus()

        wifiController.disableForMock()

        val gpsOk = registerProvider(GPS_PROVIDER)
        val netOk = registerProvider(NETWORK_PROVIDER)
        val fusedOk = tryRegisterExtra(FUSED_PROVIDER)
        val passiveOk = tryRegisterExtra(PASSIVE_PROVIDER)

        Log.d("MockService", "GPS=$gpsOk NET=$netOk FUSED=$fusedOk PASSIVE=$passiveOk useGcj02=$useGcj02")

        if (!gpsOk && !netOk) {
            lastError = lastError ?: "Provider注册失败,请确认已在开发者选项中将宁宁模拟设为模拟位置应用"
            wifiController.restoreWifiState()
            return false
        }

        LocationHooks.updateLastPosition(currentLat, currentLng)
        clearLastKnownLocationCache()
        acquireWakeLock()

        isRunning = true
        pushCount = 0
        consecutivePushFailures = 0  // v1.18

        // Burst push: 启动时快速推送10次
        for (i in 1..10) {
            pushLocation()
        }
        Log.d("MockService", "Burst push done (10x), continuous push starting")

        // 注册真实定位监听器
        try {
            if (gpsOk) {
                locationManager.requestLocationUpdates(
                    GPS_PROVIDER, 0L, 0f,
                    realLocationListener, locationThread.looper
                )
            }
            if (netOk) {
                locationManager.requestLocationUpdates(
                    NETWORK_PROVIDER, 0L, 0f,
                    realLocationListener, locationThread.looper
                )
            }
        } catch (e: SecurityException) {
            Log.d("MockService", "Listener registration failed: ${e.message}")
        }

        locationHandler.post(pushRunnable)
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
            // GCJ-02模式：转换后推送（部分钉钉版本需要）
            LocationHooks.wgs84ToGcj02(currentLat, currentLng)
        } else {
            // WGS-84模式：直接推送（标准GPS坐标，修复高德/百度600米偏移）
            Pair(currentLat, currentLng)
        }

        val (driftedLat, driftedLng) = LocationHooks.applyDrift(pushLat, pushLng)

        pushToProvider(GPS_PROVIDER, gpsRegistered, driftedLat, driftedLng)
        pushToProvider(NETWORK_PROVIDER, networkRegistered, driftedLat, driftedLng)
        pushToProvider(FUSED_PROVIDER, fusedRegistered, driftedLat, driftedLng)
        pushToProvider(PASSIVE_PROVIDER, passiveRegistered, driftedLat, driftedLng)

        LocationHooks.updateLastPosition(currentLat, currentLng)

        if (pushCount % 20 == 0L) {
            for (provider in listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER)) {
                val registered = when (provider) {
                    GPS_PROVIDER -> gpsRegistered
                    NETWORK_PROVIDER -> networkRegistered
                    FUSED_PROVIDER -> fusedRegistered
                    else -> passiveRegistered
                }
                if (registered) {
                    try { locationManager.setTestProviderEnabled(provider, true) } catch (_: Exception) {}
                }
            }
        }

        if (pushCount % 50 == 0L) {
            updateNotification()
        }
    }

    private fun pushToProvider(provider: String, registered: Boolean, lat: Double, lng: Double) {
        if (!registered) return
        val loc = LocationHooks.buildRealisticLocation(provider, lat, lng)
        try {
            locationManager.setTestProviderLocation(provider, loc)
            consecutivePushFailures = 0  // v1.18: 成功时重置计数器
        } catch (e: Exception) {
            consecutivePushFailures++
            if (e is IllegalArgumentException || e is SecurityException) {
                Log.d("MockService", "Re-registering $provider")
                if (registerProvider(provider)) {
                    try {
                        locationManager.setTestProviderLocation(provider, loc)
                        consecutivePushFailures = 0  // 重新注册后成功
                    } catch (_: Exception) {
                        // 重新推送也失败，由下方阈值检测处理
                    }
                }
            }
            // v1.18: 连续失败超过阈值 → 报错
            if (consecutivePushFailures >= 20) {
                lastError = "$provider 推送连续失败${consecutivePushFailures}次，模拟已停止"
                Log.e("MockService", lastError!!)
                isRunning = false
                showErrorNotification()
            }
        }
    }

    /**
     * v1.20 修复：停止模拟 — IPC 调用移到后台线程，UI 不阻塞
     *
     * 快速操作在主线程执行（设标志位 + 移除回调 + stopForeground）
     * IPC 调用（removeTestProvider x4, removeUpdates）在后台线程执行
     */
    fun stopMocking() {
        // === 主线程快速操作（< 5ms）===
        isRunning = false
        locationHandler.removeCallbacks(pushRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)

        // === IPC 调用移到后台线程 ===
        locationHandler.post {
            try { locationManager.removeUpdates(realLocationListener) } catch (_: Exception) {}

            for (provider in listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER)) {
                try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}
            }

            gpsRegistered = false
            networkRegistered = false
            fusedRegistered = false
            passiveRegistered = false

            releaseWakeLock()
            wifiController.restoreWifiState()

            stopSelf()
            Log.d("MockService", "Stop done (async cleanup)")
        }
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
            error = lastError,
            mockFlagOk = !LocationHooks.hideMockFlagFailed  // v1.18
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

    /**
     * v1.20: 通知带"停止"操作按钮
     */
    private fun buildStopPendingIntent(): PendingIntent {
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(): Notification {
        val coordSys = if (useGcj02) "GCJ-02" else "WGS-84"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("宁宁模拟 v1.20 运行中")
            .setContentText("坐标: $coordSys | 正在提供位置信息")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "停止", buildStopPendingIntent())
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
        var providerInfo = ""
        if (gpsRegistered) providerInfo += "GPS"
        if (networkRegistered) providerInfo += "+NET"
        if (fusedRegistered) providerInfo += "+FUSED"
        if (passiveRegistered) providerInfo += "+PAS"

        val coordSys = if (useGcj02) "GCJ-02" else "WGS-84"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("宁宁模拟 v1.20")
            .setContentText("[$coordSys] ${pushCount}次 [$providerInfo] " +
                    "%.4f, %.4f".format(currentLat, currentLng))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "停止", buildStopPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isRunning = false
        locationHandler.removeCallbacks(pushRunnable)
        locationHandler.removeCallbacks(wakeLockRenewal)
        try { locationManager.removeUpdates(realLocationListener) } catch (_: Exception) {}
        for (provider in listOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER, PASSIVE_PROVIDER)) {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}
        }
        releaseWakeLock()
        wifiController.restoreWifiState()

        locationThread.quitSafely()
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
        val error: String? = null,
        val mockFlagOk: Boolean = true  // v1.18: 反检测状态
    )

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_USE_GCJ02 = "extra_use_gcj02"
        const val ACTION_STOP = "com.ningning.mock.STOP"
        private const val CHANNEL_ID = "ningning_location"
        private const val NOTIFICATION_ID = 1001

        // v1.20: WakeLock 超时 30 分钟，每 30 分钟自动续期
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKELOCK_RENEWAL_INTERVAL = 30 * 60 * 1000L
    }
}
