package com.ningning.mock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ningning.mock.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var mockService: MockLocationService? = null
    private var serviceBound = false
    private var isMocking = false

    private val DEFAULT_AMAP_KEY = "77307996c1d945194fdafea3c683ce5d"
    private var amapKey = DEFAULT_AMAP_KEY

    private var currentMarker: Marker? = null
    private var selectedLat = 0.0
    private var selectedLng = 0.0
    private var searchMarker: Marker? = null

    private var currentMockLocationName = ""

    // === v1.17: 后台线程池（替代裸Thread，避免线程堆积） ===
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    // === v1.17: 命名的 Runnable，可精确移除 ===
    private val autoBackgroundRunnable = Runnable {
        if (isMocking) {
            moveTaskToBack(true)
            Log.d("AutoBackground", "App moved to background")
        }
    }

    private val mockStartCheckRunnable = Runnable {
        if (!serviceBound) {
            showMockErrorDialog("服务启动失败，请尝试重新打开APP")
            stopMocking()
            return@Runnable
        }
        val status = mockService?.getStatus()
        if (status != null) {
            val registered = mutableListOf<String>()
            if (status.gpsRegistered) registered.add("GPS")
            if (status.networkRegistered) registered.add("NET")
            if (status.fusedRegistered) registered.add("FUSED")
            if (status.passiveRegistered) registered.add("PASSIVE")

            val errorMsg = status.error

            if (registered.isEmpty()) {
                val msg = errorMsg ?: "Provider注册失败"
                showMockErrorDialog(msg)
                stopMocking()
            } else {
                val detail = registered.joinToString("+")
                val gcjTag = if (status.useGcj02) "GCJ-02" else "WGS-84"
                Toast.makeText(this,
                    "模拟已启动 [$detail] 坐标:$gcjTag",
                    Toast.LENGTH_LONG).show()

                if (prefs.getBoolean("auto_background", true)) {
                    Toast.makeText(this, "2秒后自动进入后台...", Toast.LENGTH_SHORT).show()
                    handler.postDelayed(autoBackgroundRunnable, 2000)
                } else {
                    showMockTips()
                }
            }
        }
    }

    // === v1.17: 缓存模拟位置权限检查结果 ===
    @Volatile private var cachedMockPermission: Boolean? = null

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val PERMISSION_REQUEST = 1001

    // 瓦片图层
    private val tileLayers = arrayOf(
        "高德地图" to createAmapTileSource(),
        "CartoDB" to createCartoDBTileSource(),
        "OpenStreetMap" to TileSourceFactory.MAPNIK
    )
    private var currentLayerIndex = 0

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MockLocationService.LocalBinder
            mockService = binder.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mockService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("yiyi_prefs", Context.MODE_PRIVATE)
        amapKey = prefs.getString("amap_key", DEFAULT_AMAP_KEY) ?: DEFAULT_AMAP_KEY

        Configuration.getInstance().apply {
            userAgentValue = "YiYiMock/1.17"
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTitle.text = "依依模拟 v1.17"

        setupMap()
        setupButtons()
        setupSearch()
        restoreLastLocation()
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(tileLayers[0].second)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(28.0, 120.67))
            minZoomLevel = 4.0
            maxZoomLevel = 20.0

            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onMapClick(p)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            overlays.add(0, eventsOverlay)

            val compassOverlay = CompassOverlay(this@MainActivity, this)
            compassOverlay.enableCompass()
            overlays.add(compassOverlay)
        }
    }

    private fun onMapClick(point: GeoPoint) {
        if (isMocking) {
            Toast.makeText(this, "请先停止模拟再选点", Toast.LENGTH_SHORT).show()
            return
        }

        val isGcj02Map = currentLayerIndex == 0
        if (isGcj02Map) {
            val (wgsLat, wgsLng) = LocationHooks.gcj02ToWgs84(point.latitude, point.longitude)
            selectedLat = wgsLat
            selectedLng = wgsLng
        } else {
            selectedLat = point.latitude
            selectedLng = point.longitude
        }

        currentMarker?.let { binding.mapView.overlays.remove(it) }
        currentMarker = Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "已选择位置"
            snippet = "%.5f, %.5f".format(selectedLat, selectedLng)
            setOnMarkerClickListener { marker, _ ->
                marker.showInfoWindow()
                true
            }
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        updateSelectedUI(selectedLat, selectedLng)
    }

    private fun updateSelectedUI(lat: Double, lng: Double) {
        binding.tvCoords.text = "WGS-84: %.6f, %.6f".format(lat, lng)
        binding.cardSelected.visibility = View.VISIBLE
        binding.btnStartMock.isEnabled = true

        // v1.17: 更新收藏按钮状态
        updateFavoriteButton(lat, lng)

        // v1.17: 用 ExecutorService 替代裸 Thread
        backgroundExecutor.execute {
            var name = reverseGeocodeSystem(lat, lng)
            if (name == null) {
                name = reverseGeocodeAmap(lat, lng)
            }
            val displayName = name ?: "已选位置"
            runOnUiThread {
                binding.tvLocationName.text = displayName
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeSystem(lat: Double, lng: Double): String? {
        return try {
            val geocoder = Geocoder(this, Locale.CHINA)
            val addresses = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            addresses?.let { addr ->
                val parts = mutableListOf<String>()
                addr.locality?.let { parts.add(it) }
                addr.subLocality?.let { parts.add(it) }
                addr.thoroughfare?.let { parts.add(it) }
                addr.featureName?.let { parts.add(it) }
                if (parts.isNotEmpty()) parts.joinToString("") else addr.getAddressLine(0)
            }
        } catch (e: Exception) {
            Log.d("ReverseGeo", "Geocoder failed: ${e.message}")
            null
        }
    }

    private fun reverseGeocodeAmap(lat: Double, lng: Double): String? {
        return try {
            val (gcjLat, gcjLng) = LocationHooks.wgs84ToGcj02(lat, lng)
            val url = "https://restapi.amap.com/v3/geocode/regeo" +
                    "?key=$amapKey&location=$gcjLng,$gcjLat&extensions=base&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val result = conn.inputStream.bufferedReader().readText()
            val key = "\"formatted_address\":\""
            val idx = result.indexOf(key)
            if (idx >= 0) {
                val start = idx + key.length
                val end = result.indexOf("\"", start)
                if (end > start) result.substring(start, end) else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun setupButtons() {
        binding.btnStartMock.setOnClickListener {
            if (selectedLat == 0.0 && selectedLng == 0.0) {
                Toast.makeText(this, "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startMocking()
        }
        binding.fabMyLocation.setOnClickListener { goToMyLocation() }
        binding.fabLayer.setOnClickListener { toggleMapLayer() }
        binding.fabHistory.setOnClickListener { showHistoryDialog() }

        // v1.17: 收藏按钮
        binding.btnFavorite.setOnClickListener {
            toggleFavorite()
        }

        binding.switchAutoBackground.isChecked = prefs.getBoolean("auto_background", true)
        binding.switchAutoBackground.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_background", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "已开启: 启动模拟后自动进入后台", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) doSearch(query)
                true
            } else false
        }
        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) doSearch(query)
        }
    }

    // ==================== 收藏夹功能 (v1.17 新增) ====================

    private data class FavoriteItem(val name: String, val lat: Double, val lng: Double)

    private fun loadFavorites(): List<FavoriteItem> {
        val result = mutableListOf<FavoriteItem>()
        try {
            val json = prefs.getString("favorites", null) ?: return result
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(FavoriteItem(
                    obj.getString("name"),
                    obj.getDouble("lat"),
                    obj.getDouble("lng")
                ))
            }
        } catch (e: Exception) {
            Log.e("Favorites", "Load failed: ${e.message}")
        }
        return result
    }

    private fun saveFavorites(favorites: List<FavoriteItem>) {
        try {
            val arr = JSONArray()
            for (item in favorites) {
                val obj = JSONObject()
                obj.put("name", item.name)
                obj.put("lat", item.lat)
                obj.put("lng", item.lng)
                arr.put(obj)
            }
            prefs.edit().putString("favorites", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e("Favorites", "Save failed: ${e.message}")
        }
    }

    private fun isFavorite(lat: Double, lng: Double): Boolean {
        return loadFavorites().any {
            kotlin.math.abs(it.lat - lat) < 0.0001 && kotlin.math.abs(it.lng - lng) < 0.0001
        }
    }

    private fun toggleFavorite() {
        if (selectedLat == 0.0 && selectedLng == 0.0) return

        val favorites = loadFavorites().toMutableList()
        val existingIdx = favorites.indexOfFirst {
            kotlin.math.abs(it.lat - selectedLat) < 0.0001 && kotlin.math.abs(it.lng - selectedLng) < 0.0001
        }

        if (existingIdx >= 0) {
            favorites.removeAt(existingIdx)
            saveFavorites(favorites)
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
        } else {
            val name = binding.tvLocationName.text.toString().ifEmpty { "已选位置" }
            favorites.add(0, FavoriteItem(name, selectedLat, selectedLng))
            saveFavorites(favorites)
            Toast.makeText(this, "已收藏: $name", Toast.LENGTH_SHORT).show()
        }
        updateFavoriteButton(selectedLat, selectedLng)
    }

    private fun updateFavoriteButton(lat: Double, lng: Double) {
        if (isFavorite(lat, lng)) {
            binding.btnFavorite.setImageResource(android.R.drawable.btn_star_big_on)
            binding.btnFavorite.imageTintList = ContextCompat.getColorStateList(this, android.R.color.holo_orange_light)
        } else {
            binding.btnFavorite.setImageResource(android.R.drawable.btn_star_big_off)
            binding.btnFavorite.imageTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
        }
    }

    // ==================== 定位历史记录 ====================

    private data class HistoryItem(val name: String, val lat: Double, val lng: Double, val time: Long)

    private fun saveToHistory(lat: Double, lng: Double, name: String) {
        try {
            val history = loadHistory().toMutableList()
            val existingIdx = history.indexOfFirst {
                kotlin.math.abs(it.lat - lat) < 0.001 && kotlin.math.abs(it.lng - lng) < 0.001
            }
            if (existingIdx >= 0) {
                history.removeAt(existingIdx)
            }
            history.add(0, HistoryItem(name, lat, lng, System.currentTimeMillis()))
            while (history.size > 20) {
                history.removeAt(history.size - 1)
            }
            val arr = JSONArray()
            for (item in history) {
                val obj = JSONObject()
                obj.put("name", item.name)
                obj.put("lat", item.lat)
                obj.put("lng", item.lng)
                obj.put("time", item.time)
                arr.put(obj)
            }
            prefs.edit().putString("location_history", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e("History", "Save failed: ${e.message}")
        }
    }

    private fun loadHistory(): List<HistoryItem> {
        val result = mutableListOf<HistoryItem>()
        try {
            val json = prefs.getString("location_history", null) ?: return result
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(HistoryItem(
                    obj.getString("name"),
                    obj.getDouble("lat"),
                    obj.getDouble("lng"),
                    obj.getLong("time")
                ))
            }
        } catch (e: Exception) {
            Log.e("History", "Load failed: ${e.message}")
        }
        return result
    }

    /**
     * v1.17: 历史对话框整合收藏夹
     */
    private fun showHistoryDialog() {
        val favorites = loadFavorites()
        val history = loadHistory()

        if (favorites.isEmpty() && history.isEmpty()) {
            Toast.makeText(this, "暂无记录，模拟定位后自动保存", Toast.LENGTH_SHORT).show()
            return
        }

        // 构建合并列表：收藏 + 历史
        val items = mutableListOf<String>()
        val itemData = mutableListOf<Triple<String, Double, Double>>() // name, lat, lng

        if (favorites.isNotEmpty()) {
            items.add("--- 收藏 (${favorites.size}条) ---")
            itemData.add(Triple("", 0.0, 0.0)) // header, 不可点击
            for ((i, f) in favorites.withIndex()) {
                val shortName = if (f.name.length > 18) f.name.substring(0, 18) + "..." else f.name
                items.add("  ${i + 1}. $shortName")
                itemData.add(Triple(f.name, f.lat, f.lng))
            }
        }

        if (history.isNotEmpty()) {
            if (favorites.isNotEmpty()) items.add("")
            items.add("--- 历史 (${history.size}条) ---")
            itemData.add(Triple("", 0.0, 0.0)) // header
            for ((i, h) in history.withIndex()) {
                val dateStr = formatTime(h.time)
                val shortName = if (h.name.length > 18) h.name.substring(0, 18) + "..." else h.name
                items.add("  ${i + 1}. $shortName\n     ${"%.5f, %.5f".format(h.lat, h.lng)}  $dateStr")
                itemData.add(Triple(h.name, h.lat, h.lng))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("记录 (${favorites.size}收藏 + ${history.size}历史)")
            .setItems(items.toTypedArray()) { _, which ->
                val data = itemData[which]
                if (data.first.isEmpty()) return@setItems // 跳过分隔标题

                navigateToLocation(data.first, data.second, data.third)
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空历史") { _, _ ->
                prefs.edit().remove("location_history").apply()
                Toast.makeText(this, "历史已清空（收藏保留）", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun navigateToLocation(name: String, lat: Double, lng: Double) {
        selectedLat = lat
        selectedLng = lng

        val isGcj02Map = currentLayerIndex == 0
        val displayLat: Double
        val displayLng: Double
        if (isGcj02Map) {
            val (gcjLat, gcjLng) = LocationHooks.wgs84ToGcj02(lat, lng)
            displayLat = gcjLat
            displayLng = gcjLng
        } else {
            displayLat = lat
            displayLng = lng
        }

        val point = GeoPoint(displayLat, displayLng)
        binding.mapView.controller.animateTo(point)
        binding.mapView.controller.setZoom(17.0)

        currentMarker?.let { binding.mapView.overlays.remove(it) }
        currentMarker = Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
            snippet = "%.5f, %.5f".format(lat, lng)
            setOnMarkerClickListener { marker, _ ->
                marker.showInfoWindow()
                true
            }
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        updateSelectedUI(lat, lng)
        Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return sdf.format(java.util.Date(timestamp))
    }

    private fun restoreLastLocation() {
        val lastLat = prefs.getFloat("last_lat", 0f).toDouble()
        val lastLng = prefs.getFloat("last_lng", 0f).toDouble()
        if (lastLat != 0.0 && lastLng != 0.0) {
            selectedLat = lastLat
            selectedLng = lastLng

            val isGcj02Map = currentLayerIndex == 0
            val displayLat: Double
            val displayLng: Double
            if (isGcj02Map) {
                val (gcjLat, gcjLng) = LocationHooks.wgs84ToGcj02(lastLat, lastLng)
                displayLat = gcjLat
                displayLng = gcjLng
            } else {
                displayLat = lastLat
                displayLng = lastLng
            }

            val point = GeoPoint(displayLat, displayLng)
            binding.mapView.controller.setCenter(point)

            currentMarker = Marker(binding.mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "上次模拟位置"
                snippet = "%.5f, %.5f".format(lastLat, lastLng)
            }
            binding.mapView.overlays.add(currentMarker)
            binding.mapView.invalidate()

            updateSelectedUI(lastLat, lastLng)
        }
    }

    // ==================== 搜索功能 ====================

    private fun doSearch(query: String) {
        Toast.makeText(this, "正在搜索: $query ...", Toast.LENGTH_SHORT).show()
        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        // v1.17: 用 ExecutorService 替代裸 Thread
        backgroundExecutor.execute {
            try {
                var results: List<SearchResult>? = null
                var errorMsg = ""

                results = searchViaGeocoder(query)
                if (results != null && results.isNotEmpty()) {
                    Log.d("Search", "Geocoder found ${results.size} results")
                } else {
                    Log.d("Search", "Geocoder returned empty")
                    errorMsg = "系统搜索无结果。"

                    try {
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        results = searchViaAmap(encodedQuery)
                        if (results != null && results.isNotEmpty()) {
                            Log.d("Search", "Amap POI found ${results.size} results")
                        } else {
                            errorMsg += " 高德搜索无结果。"

                            results = searchViaAmapGeocode(encodedQuery)
                            if (results != null && results.isNotEmpty()) {
                                Log.d("Search", "Amap Geocode found ${results.size} results")
                            } else {
                                errorMsg += " 地理编码也无结果。"
                            }
                        }
                    } catch (e: Exception) {
                        errorMsg += " 高德API异常: ${e.message}"
                    }
                }

                val finalResults = results
                val finalError = errorMsg

                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE

                    if (finalResults != null && finalResults.isNotEmpty()) {
                        showSearchResults(finalResults)
                    } else {
                        Toast.makeText(this,
                            "未找到: $query\n$finalError", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE
                    Toast.makeText(this,
                        "搜索出错: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun searchViaGeocoder(query: String): List<SearchResult>? {
        return try {
            val geocoder = Geocoder(this, Locale.CHINA)
            val addresses = geocoder.getFromLocationName(query, 10) ?: emptyList()
            if (addresses.isEmpty()) return null

            val results = mutableListOf<SearchResult>()
            for (addr in addresses) {
                val lat = addr.latitude
                val lng = addr.longitude
                if (lat == 0.0 && lng == 0.0) continue

                val name = StringBuilder()
                addr.locality?.let { name.append(it) }
                addr.subLocality?.let { name.append(it) }
                addr.thoroughfare?.let { name.append(it) }
                addr.featureName?.let {
                    if (name.isNotEmpty()) name.append(" ")
                    name.append(it)
                }
                val displayName = if (name.isNotEmpty()) name.toString() else addr.getAddressLine(0) ?: "未知地点"

                results.add(SearchResult(displayName, lat, lng, isGcj02 = false))
            }

            if (results.isEmpty()) null else results
        } catch (e: Exception) {
            Log.d("Search", "Geocoder error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun searchViaAmap(encodedQuery: String): List<SearchResult>? {
        val result = mutableListOf<SearchResult>()
        try {
            val url = "https://restapi.amap.com/v3/place/text" +
                    "?key=$amapKey&keywords=$encodedQuery" +
                    "&offset=10&page=1&extensions=base&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "YiYiMock/1.17")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                Log.e("Search", "Amap HTTP $responseCode")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()

            if (body.contains("\"status\":\"0\"")) {
                return null
            }

            if (!body.contains("\"pois\"")) return null

            val poisSection = body.substring(body.indexOf("\"pois\":"))
            val pois = poisSection.split("{\"id\":\"")

            for (i in 1 until pois.size) {
                val poi = pois[i]
                val name = extractJsonStr(poi, "\"name\":\"") ?: continue
                val location = extractJsonStr(poi, "\"location\":\"") ?: continue
                val parts = location.split(",")
                if (parts.size == 2) {
                    val lng = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    val address = extractJsonStr(poi, "\"address\":\"") ?: ""
                    val city = extractJsonStr(poi, "\"cityname\":\"") ?: ""
                    val adname = extractJsonStr(poi, "\"adname\":\"") ?: ""
                    val fullName = if (address.isNotEmpty()) "$name ($adname$address)" else "$name ($city$adname)"
                    result.add(SearchResult(fullName, lat, lng, isGcj02 = true))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            return null
        } catch (e: Exception) {
            return null
        }
        return if (result.isEmpty()) null else result
    }

    private fun searchViaAmapGeocode(encodedQuery: String): List<SearchResult>? {
        return try {
            val url = "https://restapi.amap.com/v3/geocode/geo" +
                    "?key=$amapKey&address=$encodedQuery&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "YiYiMock/1.17")
            val body = conn.inputStream.bufferedReader().readText()

            if (!body.contains("\"status\":\"1\"") || !body.contains("\"geocodes\"")) {
                return null
            }

            val geocodesSection = body.substring(body.indexOf("\"geocodes\":"))
            val geocodes = geocodesSection.split("{\"formatted_address\":\"")
            val results = mutableListOf<SearchResult>()

            for (i in 1 until geocodes.size) {
                val gc = geocodes[i]
                val addr = extractJsonStr(gc, "\"formatted_address\":\"") ?: continue
                val location = extractJsonStr(gc, "\"location\":\"") ?: continue
                val district = extractJsonStr(gc, "\"district\":\"") ?: ""
                val city = extractJsonStr(gc, "\"city\":\"") ?: ""
                val province = extractJsonStr(gc, "\"province\":\"") ?: ""
                val parts = location.split(",")
                if (parts.size == 2) {
                    val lng = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    val name = "$addr ($province$city$district)"
                    results.add(SearchResult(name, lat, lng, isGcj02 = true))
                }
            }

            if (results.isEmpty()) null else results
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonStr(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        var end = start
        while (end < json.length && json[end] != '"') {
            if (json[end] == '\\') end++
            end++
        }
        return json.substring(start, end).replace("\\/", "/").replace("\\\\", "")
    }

    private fun showSearchResults(results: List<SearchResult>) {
        val names = results.mapIndexed { i, r ->
            val coordTag = if (r.isGcj02) "[高德]" else "[GPS]"
            "${i + 1}. ${r.name} $coordTag"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("搜索结果 (${results.size}条)")
            .setItems(names) { _, which ->
                val r = results[which]

                if (r.isGcj02) {
                    val (wgsLat, wgsLng) = LocationHooks.gcj02ToWgs84(r.lat, r.lng)
                    selectedLat = wgsLat
                    selectedLng = wgsLng
                } else {
                    selectedLat = r.lat
                    selectedLng = r.lng
                }

                val isGcj02Map = currentLayerIndex == 0
                val displayLat: Double
                val displayLng: Double
                if (isGcj02Map) {
                    val (gcjLat, gcjLng) = LocationHooks.wgs84ToGcj02(selectedLat, selectedLng)
                    displayLat = gcjLat
                    displayLng = gcjLng
                } else {
                    displayLat = selectedLat
                    displayLng = selectedLng
                }

                val targetPoint = GeoPoint(displayLat, displayLng)
                binding.mapView.controller.animateTo(targetPoint)
                binding.mapView.controller.setZoom(17.0)

                searchMarker?.let { binding.mapView.overlays.remove(it) }
                searchMarker = Marker(binding.mapView).apply {
                    position = targetPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = r.name
                    snippet = "%.5f, %.5f".format(selectedLat, selectedLng)
                    setOnMarkerClickListener { marker, _ ->
                        marker.showInfoWindow()
                        true
                    }
                }
                binding.mapView.overlays.add(searchMarker)

                updateSelectedUI(selectedLat, selectedLng)
                binding.mapView.invalidate()
                binding.etSearch.setText("")

                Toast.makeText(this, "已定位: ${r.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 模拟位置 ====================

    /**
     * v1.17 修复冻结：缓存权限检查结果
     * 原先每次点击按钮都做3次IPC（addTestProvider+removeTestProvider x2）
     * 现在只在首次检查，onResume时失效重查
     */
    private fun isMockLocationAllowed(): Boolean {
        // 使用缓存结果
        cachedMockPermission?.let { return it }

        val result = try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = LocationManager.GPS_PROVIDER
            try { lm.removeTestProvider(provider) } catch (_: Exception) {}

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
                method.invoke(lm, provider, props)
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(provider,
                    false, true, false, false, true, true, true,
                    android.location.Criteria.POWER_MEDIUM, android.location.Criteria.ACCURACY_FINE)
            }

            try { lm.removeTestProvider(provider) } catch (_: Exception) {}
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            true
        }

        cachedMockPermission = result
        Log.d("MockPermission", "Checked (cached): $result")
        return result
    }

    private fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) { false }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "请手动在设置中关闭电池优化", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getBrandSurvivalTips(): String {
        val brand = android.os.Build.BRAND.lowercase()
        return when {
            brand.contains("huawei") || brand.contains("honor") ->
                "【华为/荣耀手机】\n" +
                "1. 设置→应用→应用管理→依依模拟\n" +
                "   →电池→选择\"不受限制\"\n" +
                "2. 设置→应用→应用启动管理\n" +
                "   →依依模拟→关闭\"自动管理\"\n" +
                "   →开启全部三个开关\n" +
                "3. 在最近任务列表中，下拉依依模拟加锁"

            brand.contains("xiaomi") || brand.contains("redmi") ->
                "【小米/红米手机】\n" +
                "1. 设置→应用设置→应用管理→依依模拟\n" +
                "   →省电策略→选择\"无限制\"\n" +
                "2. 安全中心→应用管理→权限\n" +
                "   →依依模拟→自启动→允许\n" +
                "3. 在最近任务列表中，长按依依模拟→加锁"

            brand.contains("oppo") || brand.contains("realme") ->
                "【OPPO/真我手机】\n" +
                "1. 设置→电池→应用耗电管理→依依模拟\n" +
                "   →允许后台运行\n" +
                "2. 设置→应用管理→依依模拟\n" +
                "   →自启动→允许自启动\n" +
                "3. 在最近任务列表中，下拉依依模拟加锁"

            brand.contains("vivo") ->
                "【vivo手机】\n" +
                "1. 设置→电池→后台耗电管理→依依模拟\n" +
                "   →允许后台高耗电\n" +
                "2. i管家→应用管理→权限管理\n" +
                "   →依依模拟→自启动→允许\n" +
                "3. 在最近任务列表中，下拉依依模拟加锁"

            brand.contains("samsung") ->
                "【三星手机】\n" +
                "1. 设置→应用程序→依依模拟→电池\n" +
                "   →选择\"不受限制\"\n" +
                "2. 设置→电池和设备维护→自动优化\n" +
                "   →关闭（或添加依依模拟到排除列表）"

            else ->
                "【通用设置】\n" +
                "1. 设置→电池→依依模拟→不受限制\n" +
                "2. 设置→应用→依依模拟→自启动→允许\n" +
                "3. 在最近任务列表中，给依依模拟加锁\n" +
                "（不同品牌设置路径可能不同）"
        }
    }

    private fun startMocking() {
        if (!isMockLocationAllowed()) {
            AlertDialog.Builder(this)
                .setTitle("请先设置模拟位置应用")
                .setMessage(
                    "模拟位置权限未授予。\n\n" +
                    "请按以下步骤操作：\n\n" +
                    "1. 打开「设置」\n" +
                    "2. 找到「开发者选项」\n" +
                    "   （如果没有：设置-关于手机-连续点击「版本号」7次）\n" +
                    "3. 找到「选择模拟位置信息应用」\n" +
                    "4. 选择「依依模拟」\n\n" +
                    "设置完成后返回本APP重新操作。"
                )
                .setPositiveButton("去开发者选项") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
            return
        }

        if (!isBatteryOptimizationIgnored() && !prefs.getBoolean("battery_tips_shown", false)) {
            AlertDialog.Builder(this)
                .setTitle("重要：后台保活设置")
                .setMessage(
                    "v1.17 后台保活设置\n\n" +
                    "模拟定位在后台被覆盖的原因：\n" +
                    "Android系统会冻结后台Service的CPU，\n" +
                    "导致位置推送停止，被真实GPS覆盖。\n\n" +
                    "请完成以下设置确保模拟稳定：\n\n" +
                    getBrandSurvivalTips() + "\n\n" +
                    "设置完成后点击\"已完成，继续\""
                )
                .setPositiveButton("已完成，继续") { _, _ ->
                    requestBatteryOptimizationExemption()
                    prefs.edit().putBoolean("battery_tips_shown", true).apply()
                    handler.postDelayed({ checkWifiAndStart() }, 1000)
                }
                .setNegativeButton("稍后设置") { _, _ ->
                    checkWifiAndStart()
                }
                .setCancelable(false)
                .show()
            return
        }

        checkWifiAndStart()
    }

    private fun checkWifiAndStart() {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val wifiOn = wifiManager.isWifiEnabled

        if (wifiOn) {
            AlertDialog.Builder(this)
                .setTitle("建议关闭WiFi")
                .setMessage(
                    "检测到WiFi开启！\n\n" +
                    "钉钉等APP会通过WiFi扫描获取真实位置，\n" +
                    "即使模拟了GPS也能识别真实地址！\n\n" +
                    "请关闭WiFi和WiFi扫描：\n" +
                    "1. 关闭WiFi\n" +
                    "2. 关闭WiFi扫描\n" +
                    "   (设置-位置信息-Wi-Fi扫描-关闭)\n\n" +
                    "v1.17已修正坐标系偏移(GCJ-02)，\n" +
                    "关闭WiFi后定位应更准确。"
                )
                .setPositiveButton("已关闭，开始模拟") { _, _ ->
                    doStartMockService()
                }
                .setNeutralButton("去WiFi设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("不管，直接模拟") { _, _ ->
                    doStartMockService()
                }
                .setCancelable(false)
                .show()
        } else {
            doStartMockService()
        }
    }

    private fun doStartMockService() {
        val locationName = binding.tvLocationName.text.toString()
        currentMockLocationName = locationName
        saveToHistory(selectedLat, selectedLng, locationName)
        prefs.edit()
            .putFloat("last_lat", selectedLat.toFloat())
            .putFloat("last_lng", selectedLng.toFloat())
            .putString("last_name", locationName)
            .apply()

        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LAT, selectedLat)
            putExtra(MockLocationService.EXTRA_LNG, selectedLng)
            putExtra(MockLocationService.EXTRA_USE_GCJ02, true)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)

        isMocking = true
        updateUIState()
        startStatusUpdates()

        val autoBackground = prefs.getBoolean("auto_background", true)

        // v1.17: 使用命名 Runnable，可精确移除
        handler.postDelayed(mockStartCheckRunnable, 1500)
    }

    private fun buildProviderDetail(s: MockLocationService.MockStatus): String {
        val parts = mutableListOf<String>()
        if (s.gpsRegistered) parts.add("GPS")
        if (s.networkRegistered) parts.add("NET")
        if (s.fusedRegistered) parts.add("FUSED")
        if (s.passiveRegistered) parts.add("PASSIVE")
        return if (parts.isEmpty()) "无Provider" else parts.joinToString("+")
    }

    private fun showMockErrorDialog(error: String) {
        val msg = when {
            error.contains("SecurityException") || error.contains("未设置模拟位置") ->
                "模拟位置权限未授予。\n\n请按以下步骤操作：\n" +
                "1. 打开 设置 - 系统 - 开发者选项\n" +
                "   （如果没有：设置 - 关于手机 - 连续点击版本号7次）\n" +
                "2. 找到「选择模拟位置信息应用」\n" +
                "3. 选择「依依模拟」\n" +
                "4. 返回本APP，重新点击「开始模拟」"
            error.contains("Provider注册异常") ->
                "Provider注册出错：\n$error\n\n建议重启手机后再试。"
            else -> "启动失败：$error"
        }
        AlertDialog.Builder(this)
            .setTitle("模拟定位启动失败")
            .setMessage(msg)
            .setPositiveButton("去开发者选项") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("知道了", null)
            .setCancelable(false)
            .show()
    }

    private fun showMockTips() {
        if (prefs.getBoolean("mock_tips_v17", false)) return
        prefs.edit().putBoolean("mock_tips_v17", true).apply()
        AlertDialog.Builder(this)
            .setTitle("模拟已启动 - v1.17")
            .setMessage(
                "v1.17 核心改进：\n\n" +
                "1. 修复卡顿（关键！）\n" +
                "   权限检查结果缓存，不再每次IPC\n" +
                "   停止模拟时IPC移至后台线程\n" +
                "   搜索用线程池替代裸线程\n" +
                "   停止按钮秒响应\n\n" +
                "2. 反检测增强\n" +
                "   新增 isMock() 方法反射隐藏\n" +
                "   平滑的bearing变化（避免突变）\n" +
                "   更丰富的卫星数据\n\n" +
                "3. 后台保活优化\n" +
                "   WakeLock带30分钟超时+自动续期\n" +
                "   通知栏新增\"停止\"按钮\n" +
                "   无需打开APP即可停止\n\n" +
                "4. 收藏夹功能\n" +
                "   点击星标收藏常用位置\n" +
                "   历史记录中查看收藏\n\n" +
                "5. R8代码混淆\n" +
                "   Release版启用混淆，防反编译\n\n" +
                "使用建议：\n" +
                "1. 开始模拟后APP会自动进入后台\n" +
                "2. 等2-3秒再打开钉钉签到\n" +
                "3. 通知栏可直接点\"停止\"结束模拟"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }

    /**
     * v1.17 修复冻结：停止模拟
     *
     * 关键改动：
     * 1. UI 立即更新（isMocking=false + updateUIState）
     * 2. 不再调用 removeCallbacksAndMessages(null)（会误删所有UI回调）
     * 3. 只移除特定的命名回调
     * 4. Service的stopMocking()已在内部将IPC移至后台线程
     */
    private fun stopMocking() {
        // 1. 立即更新UI状态
        isMocking = false
        updateUIState()

        // 2. 精确移除特定回调（不再用 removeCallbacksAndMessages(null)）
        stopStatusUpdates()
        handler.removeCallbacks(mockStartCheckRunnable)
        handler.removeCallbacks(autoBackgroundRunnable)

        // 3. 调用 service 停止（service内部已将IPC移至后台线程，主线程不阻塞）
        if (serviceBound && mockService != null) {
            try { mockService?.stopMocking() } catch (_: Exception) {}
        }

        // 4. 解绑和停止服务
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        stopService(Intent(this, MockLocationService::class.java))

        serviceBound = false
        mockService = null
        currentMockLocationName = ""
    }

    private fun startStatusUpdates() {
        statusRunnable = object : Runnable {
            override fun run() {
                updateStatus()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(statusRunnable!!)
    }

    private fun stopStatusUpdates() {
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
    }

    private fun updateStatus() {
        if (serviceBound && mockService != null) {
            val s = mockService!!.getStatus()
            val detail = buildProviderDetail(s)
            val gcjTag = if (s.useGcj02) "GCJ-02" else "WGS-84"
            val pushInfo = "推送: ${s.pushCount}次 [$detail] 坐标:$gcjTag"
            binding.tvStatus.text = pushInfo
            if (s.wifiEnabled) {
                binding.tvWifiStatus.text = "WiFi开启! 目标APP可能检测到真实位置"
            } else {
                binding.tvWifiStatus.text = "WiFi: 已关闭 ✓"
            }
        }
    }

    private fun updateUIState() {
        if (isMocking) {
            binding.btnStartMock.text = "停止模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.tvStatusBar.text = "模拟中"
            binding.tvStatusBar.setTextColor(0xFF9C27B0.toInt())
            binding.cardSelected.visibility = View.VISIBLE

            val coordSys = "GCJ-02已修正"
            val mockName = currentMockLocationName.ifEmpty { "已选位置" }
            binding.tvCoords.text = "📍 $mockName\nWGS-84: %.6f, %.6f\n$coordSys".format(selectedLat, selectedLng)

            binding.btnStartMock.setOnClickListener { stopMocking() }
        } else {
            binding.btnStartMock.text = "开始模拟"
            binding.btnStartMock.setBackgroundColor(0xFF9C27B0.toInt())
            binding.tvStatusBar.text = "就绪"
            binding.tvStatusBar.setTextColor(getColor(android.R.color.darker_gray))
            binding.tvStatus.text = ""
            binding.tvWifiStatus.text = ""
            binding.btnStartMock.setOnClickListener {
                if (selectedLat == 0.0 && selectedLng == 0.0) {
                    Toast.makeText(this, "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startMocking()
            }
        }
    }

    // ==================== 辅助功能 ====================

    private fun goToMyLocation() {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                val point = GeoPoint(location.latitude, location.longitude)
                binding.mapView.controller.animateTo(point)
                binding.mapView.controller.setZoom(17.0)
                searchMarker?.let { binding.mapView.overlays.remove(it) }
                searchMarker = Marker(binding.mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "我的位置"
                    icon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.presence_online)
                }
                binding.mapView.overlays.add(searchMarker)
                binding.mapView.invalidate()
                Toast.makeText(this, "已定位到当前位置", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "无法获取当前位置，请检查定位权限", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            requestPermissions()
        }
    }

    private fun toggleMapLayer() {
        currentLayerIndex = (currentLayerIndex + 1) % tileLayers.size
        val (name, source) = tileLayers[currentLayerIndex]
        binding.mapView.setTileSource(source)
        binding.mapView.invalidate()
        val coordNote = when (name) {
            "高德地图" -> "（GCJ-02，与中国APP坐标一致）"
            else -> "（WGS-84，GPS标准坐标）"
        }
        Toast.makeText(this, "地图: $name $coordNote", Toast.LENGTH_LONG).show()
    }

    private fun hasPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "定位权限已获取", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // v1.17: 失效权限缓存（用户可能改了开发者选项）
        cachedMockPermission = null
        if (isMocking) startStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        // v1.17: 暂停状态轮询，减少IPC
        stopStatusUpdates()
    }

    override fun onDestroy() {
        stopStatusUpdates()
        handler.removeCallbacks(mockStartCheckRunnable)
        handler.removeCallbacks(autoBackgroundRunnable)
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
        }
        // v1.17: 关闭线程池
        backgroundExecutor.shutdown()
        super.onDestroy()
    }

    /** 搜索结果，标记坐标系统 */
    data class SearchResult(val name: String, val lat: Double, val lng: Double, val isGcj02: Boolean = false)

    companion object {
        /** 高德地图瓦片源（GCJ-02坐标系，与中国APP一致） */
        private fun createAmapTileSource(): XYTileSource {
            return object : XYTileSource("Amap", 0, 19, 256, ".png",
                arrayOf(
                    "https://wprd01.is.autonavi.com",
                    "https://wprd02.is.autonavi.com",
                    "https://wprd03.is.autonavi.com",
                    "https://wprd04.is.autonavi.com"
                )) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    val s = (x % 4) + 1
                    return "https://wprd0${s}.is.autonavi.com/appmaptile" +
                            "?x=${x}&y=${y}&z=${z}" +
                            "&lang=zh_cn&size=1&scl=1&style=7"
                }
            }
        }

        private fun createCartoDBTileSource(): XYTileSource {
            return XYTileSource(
                "CartoDBVoyager",
                0, 19,
                256, ".png",
                arrayOf(
                    "https://a.basemaps.cartocdn.com/rastertiles/voyager",
                    "https://b.basemaps.cartocdn.com/rastertiles/voyager",
                    "https://c.basemaps.cartocdn.com/rastertiles/voyager"
                )
            )
        }
    }
}
