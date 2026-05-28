package com.ningning.mock

import android.location.Location
import kotlin.math.*
import kotlin.random.Random

/**
 * 多层反检测系统
 * Layer 1: 反射隐藏 mIsFromMockProvider
 * Layer 2: 真实GPS元数据模拟（海拔/精度/速度/方向动态变化）
 * Layer 3: GPS + NETWORK 双Provider同步推送
 */
object LocationHooks {

    private var fieldIsFromMockProvider: java.lang.reflect.Field? = null
    private var initialized = false
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var altitudeBase = Random.nextDouble(80.0, 250.0)
    private var stepCounter = 0

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
            // 某些ROM可能移除了这个字段，不影响核心功能
            initialized = false
        }
    }

    /**
     * Layer 1: 隐藏模拟标记
     */
    fun hideMockFlag(location: Location) {
        try {
            fieldIsFromMockProvider?.setBoolean(location, false)
        } catch (_: Exception) {
            // 反射失败静默处理
        }
    }

    /**
     * Layer 2: 构建逼真的Location对象
     * 模拟真实GPS信号的特征：
     * - 海拔在基准值附近波动（模拟气压计误差）
     * - 精度在3-6米范围内随机（民用GPS典型精度）
     * - 速度1-3m/s（模拟步行或慢速行车）
     * - 方向角根据前后坐标计算（模拟真实运动轨迹）
     */
    fun buildRealisticLocation(provider: String, lat: Double, lng: Double): Location {
        stepCounter++

        val loc = Location(provider).apply {
            latitude = lat
            longitude = lng

            // 海拔：基准值 + 正弦波动（模拟气压变化），范围 ±15m
            altitude = altitudeBase + sin(stepCounter * 0.05) * 12.0 + Random.nextDouble(-3.0, 3.0)

            // 精度：3-6米随机（民用GPS典型范围）
            accuracy = Random.nextDouble(3.0, 6.0).toFloat()

            // 速度：1-3m/s（模拟步行或低速行车）
            speed = Random.nextDouble(1.0, 3.0).toFloat()

            // 方向角：根据运动轨迹计算
            if (lastLat != 0.0 && lastLng != 0.0 &&
                (lastLat != lat || lastLng != lng)) {
                bearing = calculateBearing(lastLat, lastLng, lat, lng)
            } else {
                // 首次或静止时使用随机方向
                bearing = Random.nextDouble(0.0, 360.0).toFloat()
            }

            // Android O+ 额外精度字段
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                accuracy = (3.0 + Random.nextDouble(0.0, 2.0)).toFloat()
                bearingAccuracyDegrees = Random.nextDouble(1.0, 5.0).toFloat()
                speedAccuracyMetersPerSecond = Random.nextDouble(0.3, 1.0).toFloat()
                verticalAccuracyMeters = Random.nextDouble(2.0, 8.0).toFloat()
            }

            // 时间戳设为当前时间
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }

        // 隐藏模拟标记
        hideMockFlag(loc)

        return loc
    }

    /**
     * 更新上一个坐标（用于计算方向角）
     */
    fun updateLastPosition(lat: Double, lng: Double) {
        lastLat = lat
        lastLng = lng
    }

    /**
     * 计算两点之间的方位角（0=N, 90=E, 180=S, 270=W）
     */
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
        // 1度纬度 ≈ 111,000米
        // 偏移约2-5米
        val latOffset = Random.nextDouble(-0.000045, 0.000045)
        val lngOffset = Random.nextDouble(-0.000045, 0.000045)
        return Pair(lat + latOffset, lng + lngOffset)
    }
}
