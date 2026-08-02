package com.yiyi.mock

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.util.Log
import kotlin.math.*
import kotlin.random.Random

/**
 * 多层反检测系统 + 坐标转换 (v1.20 增强)
 *
 * Layer 1: 反射隐藏 mIsFromMockProvider + isMock() (Android 12+)
 * Layer 2: 真实GPS元数据模拟（海拔/精度/速度/方向/卫星动态变化）
 * Layer 3: GPS + NETWORK + FUSED + PASSIVE 四Provider同步推送
 * Layer 4: WGS-84 <-> GCJ-02 坐标转换（按需切换）
 * Layer 5: v1.20新增 — extras伪装 + provider状态模拟
 */
object LocationHooks {

    private const val TAG = "LocationHooks"

    // === 反射缓存 ===
    private var fieldIsFromMockProvider: java.lang.reflect.Field? = null
    private var methodSetIsFromMockProvider: java.lang.reflect.Method? = null
    private var methodIsMock: java.lang.reflect.Method? = null        // Android 12+ isMock()
    private var fieldIsMock: java.lang.reflect.Field? = null          // Android 12+ mIsMock 字段
    private var initialized = false

    // === 位置追踪 ===
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var altitudeBase = Random.nextDouble(80.0, 250.0)

    // v1.18: 反检测状态追踪 — 供 MockLocationService 检查
    @Volatile var hideMockFlagFailed = false
        private set
    private var stepCounter = 0
    private var lastBearing = Random.nextDouble(0.0, 360.0).toFloat()

    // ==================== GCJ-02 坐标转换 ====================

    private const val PI = 3.1415926535897932384626
    private const val A_PARAM = 6378245.0
    private const val EE = 0.00669342162296594323

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A_PARAM * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A_PARAM / sqrtMagic * cos(radLat) * PI)
        return Pair(lat + dLat, lng + dLng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        val gcj = wgs84ToGcj02(lat, lng)
        val dLat = gcj.first - lat
        val dLng = gcj.second - lng
        return Pair(lat - dLat, lng - dLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    // ==================== 反检测系统 ====================

    /**
     * 初始化反射：获取所有可能隐藏 mock 标记的 Field/Method
     *
     * v1.17 增强：
     * - mIsFromMockProvider 字段 (Android < 12)
     * - setIsFromMockProvider 方法 (Android 12+)
     * - isMock() 方法 + mIsMock 字段 (Android 12+ API 31)
     */
    fun init() {
        if (initialized) return

        // 方法1: 反射字段 mIsFromMockProvider
        try {
            fieldIsFromMockProvider = Location::class.java.getDeclaredField("mIsFromMockProvider")
            fieldIsFromMockProvider?.isAccessible = true
            Log.d(TAG, "mIsFromMockProvider field found")
        } catch (e: Exception) {
            fieldIsFromMockProvider = null
            Log.d(TAG, "mIsFromMockProvider field not found: ${e.message}")
        }

        // 方法2: setIsFromMockProvider 方法 (Android 12+)
        try {
            methodSetIsFromMockProvider = Location::class.java.getDeclaredMethod(
                "setIsFromMockProvider", Boolean::class.javaPrimitiveType
            )
            methodSetIsFromMockProvider?.isAccessible = true
            Log.d(TAG, "setIsFromMockProvider method found")
        } catch (e: Exception) {
            methodSetIsFromMockProvider = null
        }

        // 方法3: isMock() 方法 — Android 12 (API 31) 新增
        // 某些 APP 调用 Location.isMock() 而非 isFromMockProvider()
        try {
            methodIsMock = Location::class.java.getDeclaredMethod("isMock")
            methodIsMock?.isAccessible = true
            Log.d(TAG, "isMock() method found")
        } catch (e: Exception) {
            methodIsMock = null
        }

        // 方法4: mIsMock 字段 — Android 12+ 内部字段
        try {
            fieldIsMock = Location::class.java.getDeclaredField("mIsMock")
            fieldIsMock?.isAccessible = true
            Log.d(TAG, "mIsMock field found")
        } catch (e: Exception) {
            fieldIsMock = null
        }

        initialized = true
        Log.d(TAG, "Anti-detect init done. SDK=${Build.VERSION.SDK_INT}")
    }

    /**
     * Layer 1: 隐藏所有模拟标记
     * 尝试全部4种方式，确保兼容所有 Android 版本
     * v1.18: 返回是否至少有一种方式成功
     * v1.20: 增加 setIsMock 方法尝试 + 日志输出成功率
     */
    fun hideMockFlag(location: Location): Boolean {
        var anySuccess = false

        // 1. mIsFromMockProvider 字段
        try {
            fieldIsFromMockProvider?.setBoolean(location, false)
            anySuccess = true
        } catch (_: Exception) {}

        // 2. setIsFromMockProvider 方法
        try {
            methodSetIsFromMockProvider?.invoke(location, false)
            anySuccess = true
        } catch (_: Exception) {}

        // 3. mIsMock 字段 (Android 12+)
        try {
            fieldIsMock?.setBoolean(location, false)
            anySuccess = true
        } catch (_: Exception) {}

        // 4. setIsMock 方法 (如果有)
        try {
            val setIsMock = Location::class.java.getDeclaredMethod(
                "setIsMock", Boolean::class.javaPrimitiveType
            )
            setIsMock.isAccessible = true
            setIsMock.invoke(location, false)
            anySuccess = true
        } catch (_: Exception) {}

        // 5. v1.20: 尝试通过 Location.set() 复制到新对象清除标记
        // 某些Android版本上，新建的Location默认mock=false
        // 通过set()复制后，原始mock标记可能不被复制
        try {
            val cleanLoc = Location(location.provider)
            cleanLoc.set(location)
            // 检查是否成功（isFromMockProvider 在新对象上应为false）
            // 注意：这不是修改原始location，但可用于后续推送
        } catch (_: Exception) {}

        // v1.18: 追踪反检测状态 — 全部失败时标记
        if (!anySuccess) {
            hideMockFlagFailed = true
            Log.w(TAG, "All hideMockFlag methods failed! SDK=${Build.VERSION.SDK_INT}")
        }

        return anySuccess
    }

    /** v1.18: 重置反检测状态（每次启动模拟时调用） */
    fun resetMockFlagStatus() {
        hideMockFlagFailed = false
    }

    /**
     * Layer 2: 构建逼真的 Location 对象
     *
     * v1.17 增强：
     * - 平滑的 bearing 变化（避免突变）
     * - 更真实的精度变化模式
     * - 更丰富的卫星 extras
     * - 正确的 elapsedRealtimeNanos 时钟
     */
    fun buildRealisticLocation(provider: String, lat: Double, lng: Double): Location {
        stepCounter++

        val loc = Location(provider).apply {
            latitude = lat
            longitude = lng

            // 海拔：基准 + 正弦波动 + 微噪声（模拟气压计漂移）
            altitude = altitudeBase + sin(stepCounter * 0.05) * 12.0 + Random.nextDouble(-3.0, 3.0)

            // 精度：3-6米，缓慢变化（真实GPS精度不会跳变）
            val accuracyBase = 3.5 + sin(stepCounter * 0.02) * 1.5
            accuracy = (accuracyBase + Random.nextDouble(-0.5, 0.5)).toFloat()

            // 速度：0-3 m/s（模拟步行/静止）
            speed = Random.nextDouble(0.5, 2.5).toFloat()

            // 方向：平滑变化（真实GPS bearing 不会突变）
            if (lastLat != 0.0 && lastLng != 0.0 &&
                (abs(lastLat - lat) > 0.000001 || abs(lastLng - lng) > 0.000001)) {
                val calculated = calculateBearing(lastLat, lastLng, lat, lng)
                // 平滑过渡：最多变化30度
                val diff = normalizeAngleDiff(calculated - lastBearing)
                lastBearing = (lastBearing + diff.coerceIn(-30f, 30f) + 360f) % 360f
                bearing = lastBearing
            } else {
                // 静止时方向缓慢漂移
                lastBearing = (lastBearing + Random.nextDouble(-5.0, 5.0).toFloat() + 360f) % 360f
                bearing = lastBearing
            }

            // Android O+ 额外精度字段
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                bearingAccuracyDegrees = Random.nextDouble(2.0, 8.0).toFloat()
                speedAccuracyMetersPerSecond = Random.nextDouble(0.2, 1.0).toFloat()
                verticalAccuracyMeters = Random.nextDouble(2.0, 8.0).toFloat()
            }

            // === 核心修复：正确的系统时钟 ===
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        // 添加 extras：模拟真实 GPS 卫星信息
        try {
            val satelliteCount = 9 + Random.nextInt(0, 4)  // 9-12颗
            val extras = Bundle().apply {
                putInt("satellites", satelliteCount)
                // Android Q+ 使用的键
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    putInt("noOfSatellites", satelliteCount)
                }
                // 模拟 GPS 时间戳（某些APP会检查）
                putLong("gps_time", System.currentTimeMillis())

                // v1.20: 额外伪装字段 — 部分APP检查这些extras
                // 标记为非模拟位置
                putBoolean("mockLocation", false)
                // 模拟真实GPS质量指示
                putInt("quality", 1)  // 1 = GPS quality
                // 模拟GPS定位类型（1=GPS, 2=AGPS）
                putInt("locType", 1)
            }
            loc.extras = extras
        } catch (_: Exception) {}

        // 隐藏所有模拟标记
        hideMockFlag(loc)
        return loc
    }

    fun updateLastPosition(lat: Double, lng: Double) {
        lastLat = lat
        lastLng = lng
    }

    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLon = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /** 角度差归一化到 -180..180 */
    private fun normalizeAngleDiff(diff: Float): Float {
        var d = diff
        while (d > 180) d -= 360f
        while (d < -180) d += 360f
        return d
    }

    /**
     * 随机晃动坐标（模拟真实GPS漂移），偏移量约2-5米
     */
    fun applyDrift(lat: Double, lng: Double): Pair<Double, Double> {
        val latOffset = Random.nextDouble(-0.000045, 0.000045)
        val lngOffset = Random.nextDouble(-0.000045, 0.000045)
        return Pair(lat + latOffset, lng + lngOffset)
    }
}
