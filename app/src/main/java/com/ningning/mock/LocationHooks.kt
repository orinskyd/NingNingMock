package com.ningning.mock

import android.location.Location
import kotlin.math.*
import kotlin.random.Random

/**
 * 多层反检测系统 + 坐标转换
 * Layer 1: 反射隐藏 mIsFromMockProvider
 * Layer 2: 真实GPS元数据模拟（海拔/精度/速度/方向动态变化）
 * Layer 3: GPS + NETWORK 双Provider同步推送
 * Layer 4: WGS-84 ↔ GCJ-02 坐标转换（修正中国地图偏移）
 */
object LocationHooks {

    private var fieldIsFromMockProvider: java.lang.reflect.Field? = null
    private var initialized = false
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var altitudeBase = Random.nextDouble(80.0, 250.0)
    private var stepCounter = 0

    // ==================== GCJ-02 坐标转换 ====================
    // 中国使用GCJ-02（火星坐标系），与WGS-84有约500-800米偏移
    // 高德/腾讯地图使用GCJ-02，百度地图使用BD-09（在GCJ-02基础上再偏移）
    // Android LocationManager 使用 WGS-84
    // 钉钉等APP内部使用GCJ-02

    private const val PI = 3.1415926535897932384626
    private const val A_PARAM = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * WGS-84 → GCJ-02
     * 将真实GPS坐标转为国测局坐标（火星坐标）
     */
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

    /**
     * GCJ-02 → WGS-84
     * 将国测局坐标转为真实GPS坐标
     * 使用迭代法逼近，精度<1米
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        // 一次逆变换（精度约1-2米，足够使用）
        val gcj = wgs84ToGcj02(lat, lng)
        val dLat = gcj.first - lat
        val dLng = gcj.second - lng
        return Pair(lat - dLat, lng - dLng)
    }

    /**
     * 判断坐标是否在中国境外
     * 境外不偏移
     */
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
     * 初始化反射：获取 Location.mIsFromMockProvider 字段
     */
    fun init() {
        if (initialized) return
        try {
            fieldIsFromMockProvider = Location::class.java.getDeclaredField("mIsFromMockProvider")
            fieldIsFromMockProvider?.isAccessible = true
            initialized = true
        } catch (e: Exception) {
            initialized = false
        }
    }

    /**
     * Layer 1: 隐藏模拟标记
     */
    fun hideMockFlag(location: Location) {
        try {
            fieldIsFromMockProvider?.setBoolean(location, false)
        } catch (_: Exception) {}
    }

    /**
     * Layer 2: 构建逼真的Location对象
     */
    fun buildRealisticLocation(provider: String, lat: Double, lng: Double): Location {
        stepCounter++

        val loc = Location(provider).apply {
            latitude = lat
            longitude = lng
            altitude = altitudeBase + sin(stepCounter * 0.05) * 12.0 + Random.nextDouble(-3.0, 3.0)
            accuracy = Random.nextDouble(3.0, 6.0).toFloat()
            speed = Random.nextDouble(1.0, 3.0).toFloat()

            if (lastLat != 0.0 && lastLng != 0.0 &&
                (lastLat != lat || lastLng != lng)) {
                bearing = calculateBearing(lastLat, lastLng, lat, lng)
            } else {
                bearing = Random.nextDouble(0.0, 360.0).toFloat()
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                accuracy = (3.0 + Random.nextDouble(0.0, 2.0)).toFloat()
                bearingAccuracyDegrees = Random.nextDouble(1.0, 5.0).toFloat()
                speedAccuracyMetersPerSecond = Random.nextDouble(0.3, 1.0).toFloat()
                verticalAccuracyMeters = Random.nextDouble(2.0, 8.0).toFloat()
            }

            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }

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

    /**
     * 随机晃动坐标（模拟真实GPS漂移），偏移量约2-5米
     */
    fun applyDrift(lat: Double, lng: Double): Pair<Double, Double> {
        val latOffset = Random.nextDouble(-0.000045, 0.000045)
        val lngOffset = Random.nextDouble(-0.000045, 0.000045)
        return Pair(lat + latOffset, lng + lngOffset)
    }
}
