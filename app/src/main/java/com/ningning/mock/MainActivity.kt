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
import org.osmdroid.util.MapTileIndex
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

    // 当前模拟位置名称（用于显示）
    private var currentMockLocationName = ""

    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val PERMISSION_REQUEST = 1001

    // 瓦片图层：CartoDB(WGS-84) + 高德(GCJ-02) + OSM(WGS-84)
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

        prefs = getSharedPreferences("ningning_prefs", Context.MODE_PRIVATE)
        amapKey = prefs.getString("amap_key", DEFAULT_AMAP_KEY) ?: DEFAULT_AMAP_KEY

        Configuration.getInstance().apply {
            userAgentValue = "NingNingMock/1.12"
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 标题显示版本号
        binding.tvTitle.text = "宁宁模拟 v1.12"

        setupMap()
        setupButtons()
        setupSearch()
        restoreLastLocation()
    }

    private fun setupMap() {
        binding.mapView.apply {
            // 默认使用高德地图（GCJ-02），与中国APP坐标系一致
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

    /**
     * 地图点击选点
     * 注意：当前图层可能是高德(GCJ-02)或CartoDB(WGS-84)
     * 高德图层：点击坐标是GCJ-02，需转换为WGS-84存储
     * CartoDB/OSM图层：点击坐标是WGS-84，直接存储
     */
    private fun onMapClick(point: GeoPoint) {
        if (isMocking) {
            Toast.makeText(this, "请先停止模拟再选点", Toast.LENGTH_SHORT).show()
            return
        }

        // 判断当前图层是否为高德（GCJ-02）
        val isGcj02Map = currentLayerIndex == 0  // 0=高德地图
        if (isGcj02Map) {
            // 高德地图点击得到GCJ-02坐标，转WGS-84
            val (wgsLat, wgsLng) = LocationHooks.gcj02ToWgs84(point.latitude, point.longitude)
            selectedLat = wgsLat
            selectedLng = wgsLng
            Log.d("MapClick", "GCJ-02(${point.latitude}, ${point.longitude}) -> WGS-84($wgsLat, $wgsLng)")
        } else {
            selectedLat = point.latitude
            selectedLng = point.longitude
        }

        currentMarker?.let { binding.mapView.overlays.remove(it) }
        currentMarker = Marker(binding.mapView).apply {
            position = point  // 使用原始坐标（与当前图层一致）
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

    /**
     * 高德逆地理编码：需要传入GCJ-02坐标
     * 所以先 WGS-84 → GCJ-02
     */
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
                // 历史记录存的是WGS-84，需要在当前图层上正确显示
                val isGcj02Map = currentLayerIndex == 0
                val displayLat: Double
                val displayLng: Double
                if (isGcj02Map) {
                    val (gcjLat, gcjLng) = LocationHooks.wgs84ToGcj02(h.lat, h.lng)
                    displayLat = gcjLat
                    displayLng = gcjLng
                } else {
                    displayLat = h.lat
                    displayLng = h.lng
                }

                val point = GeoPoint(displayLat, displayLng)
                binding.mapView.controller.animateTo(point)
                binding.mapView.controller.setZoom(17.0)

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

            // 根据当前图层决定显示坐标
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

                // 1. 先试 Android Geocoder（返回WGS-84坐标）
                results = searchViaGeocoder(query)
                if (results != null && results.isNotEmpty()) {
                    Log.d("Search", "Geocoder found ${results.size} results")
                } else {
                    Log.d("Search", "Geocoder returned empty")
                    errorMsg = "系统搜索无结果。"

                    // 2. 试高德 POI 搜索（返回GCJ-02坐标）
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

                // Geocoder返回WGS-84坐标
                results.add(SearchResult(displayName, lat, lng, isGcj02 = false))
            }

            if (results.isEmpty()) null else results
        } catch (e: Exception) {
            Log.d("Search", "Geocoder error: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 高德POI搜索：返回GCJ-02坐标
     * 需要标记isGcj02=true，后续转WGS-84使用
     */
    private fun searchViaAmap(encodedQuery: String): List<SearchResult>? {
        val result = mutableListOf<SearchResult>()
        try {
            val url = "https://restapi.amap.com/v3/place/text" +
                    "?key=$amapKey&keywords=$encodedQuery" +
                    "&offset=10&page=1&extensions=base&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "NingNingMock/1.12")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                Log.e("Search", "Amap HTTP $responseCode")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()

            if (body.contains("\"status\":\"0\"")) {
                val infoCode = extractJsonStr(body, "\"infocode\":\"") ?: "unknown"
                Log.e("Search", "Amap status=0, infocode=$infoCode")
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
                    // 高德返回GCJ-02坐标
                    result.add(SearchResult(fullName, lat, lng, isGcj02 = true))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            return null
        } catch (e: Exception) {
            Log.e("Search", "Amap error: ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        return if (result.isEmpty()) null else result
    }

    /**
     * 高德地理编码：返回GCJ-02坐标
     */
    private fun searchViaAmapGeocode(encodedQuery: String): List<SearchResult>? {
        return try {
            val url = "https://restapi.amap.com/v3/geocode/geo" +
                    "?key=$amapKey&address=$encodedQuery&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NingNingMock/1.12")
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
                    // 高德返回GCJ-02坐标
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

    /**
     * 显示搜索结果
     * 关键：高德(GCJ-02)结果需转WGS-84存储，地图显示按当前图层转换
     */
    private fun showSearchResults(results: List<SearchResult>) {
        val names = results.mapIndexed { i, r ->
            val coordTag = if (r.isGcj02) "[高德]" else "[GPS]"
            "${i + 1}. ${r.name} $coordTag"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("搜索结果 (${results.size}条)")
            .setItems(names) { _, which ->
                val r = results[which]

                // 统一转为WGS-84存储
                if (r.isGcj02) {
                    val (wgsLat, wgsLng) = LocationHooks.gcj02ToWgs84(r.lat, r.lng)
                    selectedLat = wgsLat
                    selectedLng = wgsLng
                    Log.d("Search", "GCJ-02(${r.lat}, ${r.lng}) -> WGS-84($wgsLat, $wgsLng)")
                } else {
                    selectedLat = r.lat
                    selectedLng = r.lng
                }

                // 根据当前图层决定显示坐标
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
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            true
        }
    }

    private fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
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

        // 检查WiFi状态
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
                    "   （设置→位置信息→Wi-Fi扫描→关闭）\n\n" +
                    "v1.12已修正坐标系偏移（GCJ-02），\n" +
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
            putExtra(MockLocationService.EXTRA_USE_GCJ02, true)  // 启用GCJ-02坐标修正
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)

        isMocking = true
        updateUIState()
        startStatusUpdates()

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
                    showMockErrorDialog(msg)
                    stopMocking()
                } else {
                    val detail = registered.joinToString("+")
                    val gcjTag = if (status.useGcj02) "GCJ-02" else "WGS-84"
                    Toast.makeText(this,
                        "模拟已启动 [$detail] 坐标:$gcjTag",
                        Toast.LENGTH_LONG).show()
                    showMockTips()
                }
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
        if (prefs.getBoolean("mock_tips_v7", false)) return
        prefs.edit().putBoolean("mock_tips_v7", true).apply()
        AlertDialog.Builder(this)
            .setTitle("模拟已启动 - v1.12 提示")
            .setMessage(
                "v1.12 已自动修正坐标偏移（GCJ-02）！\n\n" +
                "坐标说明：\n" +
                "• 中国APP（钉钉等）使用GCJ-02坐标系\n" +
                "• 本APP已自动将WGS-84转为GCJ-02推送\n" +
                "• 搜索和地图点击的位置应该与钉钉一致\n\n" +
                "如果钉钉仍然偏移：\n" +
                "1. 确认已关闭WiFi和WiFi扫描\n" +
                "2. 完全关闭钉钉后重新打开\n" +
                "3. 等待3-5秒\n\n" +
                "关于紫星APP：\n" +
                "紫星使用native hook技术（需root），\n" +
                "可以在不关WiFi的情况下模拟定位。\n" +
                "非root方案需要关闭WiFi扫描。"
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
        currentMockLocationName = ""
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
            binding.tvStatusBar.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.cardSelected.visibility = View.VISIBLE

            // 显示当前模拟位置（醒目）
            val coordSys = "GCJ-02已修正"
            val mockName = currentMockLocationName.ifEmpty { "已选位置" }
            binding.tvCoords.text = "📍 $mockName\nWGS-84: %.6f, %.6f\n$coordSys".format(selectedLat, selectedLng)

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
