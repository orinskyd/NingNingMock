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
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.net.URLEncoder
import java.util.Locale

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

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val PERMISSION_REQUEST = 1001

    // 瓦片图层列表：中国可用优先
    private val tileLayers = arrayOf(
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

        prefs = getSharedPreferences("ningning_prefs", Context.MODE_PRIVATE)
        amapKey = prefs.getString("amap_key", DEFAULT_AMAP_KEY) ?: DEFAULT_AMAP_KEY

        Configuration.getInstance().apply {
            userAgentValue = "NingNingMock/1.10"
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        selectedLat = point.latitude
        selectedLng = point.longitude

        currentMarker?.let { binding.mapView.overlays.remove(it) }
        currentMarker = Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "已选择位置"
            snippet = "%.5f, %.5f".format(point.latitude, point.longitude)
            setOnMarkerClickListener { marker, _ ->
                marker.showInfoWindow()
                true
            }
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        updateSelectedUI(point.latitude, point.longitude)
    }

    private fun updateSelectedUI(lat: Double, lng: Double) {
        binding.tvCoords.text = "%.6f, %.6f".format(lat, lng)
        binding.cardSelected.visibility = View.VISIBLE
        binding.btnStartMock.isEnabled = true
        reverseGeocode(lat, lng)
    }

    private fun reverseGeocode(lat: Double, lng: Double) {
        Thread {
            var name = reverseGeocodeSystem(lat, lng)
            if (name == null) {
                name = reverseGeocodeAmap(lat, lng)
            }
            val displayName = name ?: "已选位置"
            runOnUiThread {
                binding.tvLocationName.text = displayName
            }
        }.start()
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
            val url = "https://restapi.amap.com/v3/geocode/regeo" +
                    "?key=$amapKey&location=$lng,$lat&extensions=base&output=json"
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

    // ==================== 定位历史记录 ====================

    private data class HistoryItem(val name: String, val lat: Double, val lng: Double, val time: Long)

    private fun saveToHistory(lat: Double, lng: Double, name: String) {
        try {
            val history = loadHistory().toMutableList()
            // 去重：如果同一位置（0.001度内）已存在则更新
            val existingIdx = history.indexOfFirst {
                kotlin.math.abs(it.lat - lat) < 0.001 && kotlin.math.abs(it.lng - lng) < 0.001
            }
            if (existingIdx >= 0) {
                history.removeAt(existingIdx)
            }
            history.add(0, HistoryItem(name, lat, lng, System.currentTimeMillis()))
            // 最多保留 20 条
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
            Log.d("History", "Saved ${history.size} items")
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

    private fun showHistoryDialog() {
        val history = loadHistory()
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无历史记录，模拟定位后自动保存", Toast.LENGTH_SHORT).show()
            return
        }

        val items = history.mapIndexed { i, h ->
            val dateStr = formatTime(h.time)
            val shortName = if (h.name.length > 18) h.name.substring(0, 18) + "..." else h.name
            "${i + 1}. $shortName\n   ${"%.5f, %.5f".format(h.lat, h.lng)}  $dateStr"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("定位历史 (${history.size}条)")
            .setItems(items) { _, which ->
                val h = history[which]
                val point = GeoPoint(h.lat, h.lng)

                // 跳转地图
                binding.mapView.controller.animateTo(point)
                binding.mapView.controller.setZoom(17.0)

                // 设置选点
                selectedLat = h.lat
                selectedLng = h.lng

                currentMarker?.let { binding.mapView.overlays.remove(it) }
                currentMarker = Marker(binding.mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = h.name
                    snippet = "%.5f, %.5f".format(h.lat, h.lng)
                    setOnMarkerClickListener { marker, _ ->
                        marker.showInfoWindow()
                        true
                    }
                }
                binding.mapView.overlays.add(currentMarker)
                binding.mapView.invalidate()

                updateSelectedUI(h.lat, h.lng)
                Toast.makeText(this, "已选择: ${h.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                prefs.edit().remove("location_history").apply()
                Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return sdf.format(java.util.Date(timestamp))
    }

    /** 启动时恢复上次模拟的位置 */
    private fun restoreLastLocation() {
        val lastLat = prefs.getFloat("last_lat", 0f).toDouble()
        val lastLng = prefs.getFloat("last_lng", 0f).toDouble()
        if (lastLat != 0.0 && lastLng != 0.0) {
            selectedLat = lastLat
            selectedLng = lastLng
            val point = GeoPoint(lastLat, lastLng)
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
            Log.d("Restore", "Restored last location: $lastLat, $lastLng")
        }
    }

    // ==================== 搜索功能 ====================

    private fun doSearch(query: String) {
        Toast.makeText(this, "正在搜索: $query ...", Toast.LENGTH_SHORT).show()
        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        Thread {
            try {
                var results: List<SearchResult>? = null
                var errorMsg = ""

                // 1. 先试 Android Geocoder
                results = searchViaGeocoder(query)
                if (results != null && results.isNotEmpty()) {
                    Log.d("Search", "Geocoder found ${results.size} results")
                } else {
                    Log.d("Search", "Geocoder returned empty: ${results?.size ?: "null"}")
                    errorMsg = "系统搜索无结果。"

                    // 2. 试高德 POI 搜索
                    try {
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        results = searchViaAmap(encodedQuery)
                        if (results != null && results.isNotEmpty()) {
                            Log.d("Search", "Amap POI found ${results.size} results")
                        } else {
                            Log.d("Search", "Amap POI returned empty")
                            errorMsg += " 高德搜索无结果。"

                            // 3. 试高德地理编码
                            results = searchViaAmapGeocode(encodedQuery)
                            if (results != null && results.isNotEmpty()) {
                                Log.d("Search", "Amap Geocode found ${results.size} results")
                            } else {
                                Log.d("Search", "Amap Geocode returned empty")
                                errorMsg += " 地理编码也无结果。"
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("Search", "Amap error: ${e.message}")
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
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun searchViaGeocoder(query: String): List<SearchResult>? {
        return try {
            val geocoder = Geocoder(this, Locale.CHINA)
            val addresses = geocoder.getFromLocationName(query, 10) ?: emptyList()
            if (addresses.isEmpty()) {
                Log.d("Search", "Geocoder returned 0 addresses")
                return null
            }

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

                results.add(SearchResult(displayName, lat, lng))
            }

            Log.d("Search", "Geocoder found ${results.size} valid results")
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
            conn.setRequestProperty("User-Agent", "NingNingMock/1.10")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                Log.e("Search", "Amap HTTP $responseCode")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            Log.d("Search", "Amap POI response length: ${body.length}")

            if (body.contains("\"status\":\"0\"")) {
                val infoCode = extractJsonStr(body, "\"infocode\":\"") ?: "unknown"
                Log.e("Search", "Amap status=0, infocode=$infoCode")
                return null
            }

            if (!body.contains("\"pois\"")) {
                Log.e("Search", "Amap no pois field")
                return null
            }

            val poisSection = body.substring(body.indexOf("\"pois\":"))
            val pois = poisSection.split("{\"id\":\"")
            Log.d("Search", "Found ${pois.size - 1} POIs from Amap")

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
                    result.add(SearchResult(fullName, lat, lng))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e("Search", "Amap timeout")
            return null
        } catch (e: Exception) {
            Log.e("Search", "Amap error: ${e.javaClass.simpleName} ${e.message}")
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
            conn.setRequestProperty("User-Agent", "NingNingMock/1.10")
            val body = conn.inputStream.bufferedReader().readText()
            Log.d("Search", "Amap geocode response: ${body.length} chars")

            if (!body.contains("\"status\":\"1\"") || !body.contains("\"geocodes\"")) {
                Log.e("Search", "Amap geocode failed: ${body.substring(0, minOf(200, body.length))}")
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
                    results.add(SearchResult(name, lat, lng))
                }
            }

            if (results.isEmpty()) null else results
        } catch (e: Exception) {
            Log.e("Search", "Amap geocode error: ${e.message}")
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
            "${i + 1}. ${r.name}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("搜索结果 (${results.size}条)")
            .setItems(names) { _, which ->
                val r = results[which]
                val targetPoint = GeoPoint(r.lat, r.lng)

                binding.mapView.controller.animateTo(targetPoint)
                binding.mapView.controller.setZoom(17.0)

                searchMarker?.let { binding.mapView.overlays.remove(it) }

                searchMarker = Marker(binding.mapView).apply {
                    position = targetPoint
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = r.name
                    snippet = "%.5f, %.5f".format(r.lat, r.lng)
                    setOnMarkerClickListener { marker, _ ->
                        marker.showInfoWindow()
                        true
                    }
                }
                binding.mapView.overlays.add(searchMarker)

                selectedLat = r.lat
                selectedLng = r.lng
                updateSelectedUI(r.lat, r.lng)

                binding.mapView.invalidate()

                binding.etSearch.setText("")
                Toast.makeText(this, "已定位: ${r.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 模拟位置 ====================

    /**
     * 用实际注册 test provider 来检测模拟位置权限
     * 比读取 Settings.Secure 更可靠（部分手机返回值格式不标准）
     */
    private fun isMockLocationAllowed(): Boolean {
        return try {
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
            Log.d("MockCheck", "isMockLocationAllowed: true")
            true
        } catch (e: SecurityException) {
            Log.d("MockCheck", "isMockLocationAllowed: false (SecurityException)")
            false
        } catch (e: Exception) {
            Log.d("MockCheck", "isMockLocationAllowed error: ${e.javaClass.simpleName}: ${e.message}")
            true
        }
    }

    /** 检查飞行模式是否开启 */
    private fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
    }

    private fun startMocking() {
        // 第1步：检查模拟位置权限
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
                    "4. 选择「宁宁模拟」\n\n" +
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

        // 第2步：检查飞行模式（比WiFi更彻底，关基站+WiFi）
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val wifiOn = wifiManager.isWifiEnabled
        val airplaneOn = isAirplaneModeOn()

        if (!airplaneOn) {
            // 未开启飞行模式，提示关闭WiFi和WiFi扫描
            val msg = if (wifiOn) {
                "检测到WiFi开启！\n\n" +
                "钉钉等APP会通过WiFi扫描+基站定位获取真实位置，\n" +
                "即使模拟了GPS也能识别真实地址！\n\n" +
                "请按以下步骤操作：\n\n" +
                "方案A（推荐，部分手机支持）：\n" +
                "1. 开启飞行模式\n" +
                "2. 飞行模式下单独打开移动数据\n" +
                "   （如不能单独开数据，用方案B）\n\n" +
                "方案B（所有手机通用）：\n" +
                "1. 关闭WiFi\n" +
                "2. 关闭WiFi扫描\n" +
                "   （设置→位置信息→Wi-Fi扫描→关闭）\n" +
                "3. 保持移动数据开启\n" +
                "4. 开始模拟\n\n" +
                "两种方案都能让钉钉只能用GPS定位，模拟才能生效。"
            } else {
                "WiFi已关闭，但基站仍能定位！\n\n" +
                "钉钉等APP会通过基站定位获取真实位置。\n\n" +
                "请按以下步骤操作：\n\n" +
                "方案A（推荐，部分手机支持）：\n" +
                "1. 开启飞行模式\n" +
                "2. 飞行模式下单独打开移动数据\n" +
                "   （如不能单独开数据，用方案B）\n\n" +
                "方案B（所有手机通用）：\n" +
                "1. 确保WiFi已关闭\n" +
                "2. 关闭WiFi扫描\n" +
                "   （设置→位置信息→Wi-Fi扫描→关闭）\n" +
                "3. 保持移动数据开启\n" +
                "4. 开始模拟"
            }

            AlertDialog.Builder(this)
                .setTitle("提升模拟效果")
                .setMessage(msg)
                .setPositiveButton("已完成，开始模拟") { _, _ ->
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
            // 飞行模式已开，直接模拟
            doStartMockService()
        }
    }

    private fun doStartMockService() {
        // 保存当前选择的位置到历史和偏好
        val locationName = binding.tvLocationName.text.toString()
        saveToHistory(selectedLat, selectedLng, locationName)
        prefs.edit()
            .putFloat("last_lat", selectedLat.toFloat())
            .putFloat("last_lng", selectedLng.toFloat())
            .apply()

        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LAT, selectedLat)
            putExtra(MockLocationService.EXTRA_LNG, selectedLng)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)

        isMocking = true
        updateUIState()
        startStatusUpdates()

        // 等待1.5秒后检查模拟是否成功
        handler.postDelayed({
            if (!serviceBound) {
                showMockErrorDialog("服务启动失败，请尝试重新打开APP")
                stopMocking()
                return@postDelayed
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
                    Log.e("MockCheck", "All providers failed: $msg")
                    showMockErrorDialog(msg)
                    stopMocking()
                } else {
                    val detail = registered.joinToString("+")
                    Log.d("MockCheck", "Providers OK: $detail")

                    var verifyOk = false
                    try {
                        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        if (status.gpsRegistered) {
                            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            if (loc != null) {
                                verifyOk = true
                                Log.d("MockCheck", "GPS verify OK: ${loc.latitude}, ${loc.longitude}")
                            }
                        }
                        if (!verifyOk && status.networkRegistered) {
                            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            if (loc != null) {
                                verifyOk = true
                                Log.d("MockCheck", "NET verify OK: ${loc.latitude}, ${loc.longitude}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MockCheck", "Verify error: ${e.message}")
                    }

                    val verifyMsg = if (verifyOk) "（已验证生效）" else "（验证中...）"
                    Toast.makeText(this,
                        "模拟已启动 [$detail] $verifyMsg",
                        Toast.LENGTH_LONG).show()

                    showMockTips()
                }
            } else {
                Log.e("MockCheck", "Status is null after 1.5s")
                Toast.makeText(this, "正在初始化模拟...", Toast.LENGTH_SHORT).show()
            }
        }, 1500)
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
                "3. 选择「宁宁模拟」\n" +
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
        if (prefs.getBoolean("mock_tips_v6", false)) return
        prefs.edit().putBoolean("mock_tips_v6", true).apply()
        AlertDialog.Builder(this)
            .setTitle("模拟已启动 - 重要提示")
            .setMessage(
                "让钉钉等APP识别模拟位置的关键步骤：\n\n" +
                "1. 关闭WiFi和WiFi扫描\n" +
                "   设置→位置信息→Wi-Fi扫描→关闭\n" +
                "   WiFi扫描会暴露真实位置！\n\n" +
                "2. 或者开启飞行模式\n" +
                "   飞行模式关闭WiFi和基站，\n" +
                "   如果手机支持，飞行模式下单独开移动数据。\n" +
                "   （OPPO/华为等部分手机不支持，用方案1即可）\n\n" +
                "3. 完全关闭钉钉后重新打开\n" +
                "   已运行的APP缓存了旧位置，\n" +
                "   必须从最近任务划掉后重新打开。\n\n" +
                "4. 保持本APP在后台运行\n" +
                "   不要从最近任务中划掉。\n\n" +
                "5. 打开目标APP后等待3-5秒\n" +
                "   新位置需要时间传播。"
            )
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun stopMocking() {
        if (serviceBound && mockService != null) {
            mockService?.stopMocking()
        }
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        val intent = Intent(this, MockLocationService::class.java)
        stopService(intent)
        isMocking = false
        serviceBound = false
        mockService = null
        updateUIState()
        stopStatusUpdates()
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
            val pushInfo = "推送: ${s.pushCount}次 [$detail]"
            binding.tvStatus.text = pushInfo
            if (s.wifiEnabled) {
                binding.tvWifiStatus.text = "WiFi开启! 目标APP可能检测到真实位置"
            } else {
                binding.tvWifiStatus.text = if (isAirplaneModeOn()) "飞行模式: 已开启 ✓" else "WiFi: 已关闭 ✓"
            }
        }
    }

    private fun updateUIState() {
        if (isMocking) {
            binding.btnStartMock.text = "停止模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.tvStatusBar.text = "模拟中"
            binding.tvStatusBar.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.cardSelected.visibility = View.VISIBLE
            binding.tvCoords.text = "%.6f, %.6f".format(selectedLat, selectedLng)
            binding.btnStartMock.setOnClickListener { stopMocking() }
        } else {
            binding.btnStartMock.text = "开始模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_green_dark))
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
        Toast.makeText(this, "地图: $name", Toast.LENGTH_SHORT).show()
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
        if (isMocking) startStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        stopStatusUpdates()
    }

    override fun onDestroy() {
        stopStatusUpdates()
        if (serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    data class SearchResult(val name: String, val lat: Double, val lng: Double)

    companion object {
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
