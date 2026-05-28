package com.ningning.mock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ningning.mock.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var mockService: MockLocationService? = null
    private var serviceBound = false
    private var isMocking = false

    // 高德API Key（已内置，用户也可自行更换）
    private val DEFAULT_AMAP_KEY = "77307996c1d945194fdafea3c683ce5d"
    private var amapKey = DEFAULT_AMAP_KEY

    // 地图相关
    private var currentMarker: Marker? = null
    private var selectedLat = 0.0
    private var selectedLng = 0.0
    private var searchMarker: Marker? = null

    // 状态刷新
    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val PERMISSION_REQUEST = 1001

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

        // 初始化 osmdroid
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupButtons()
        setupSearch()
    }

    /**
     * 配置 osmdroid 地图
     */
    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(28.0, 120.67)) // 温州市中心
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
     */
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

    /**
     * 反向地理编码 — 优先用高德，降级用 Nominatim
     */
    private fun reverseGeocode(lat: Double, lng: Double) {
        Thread {
            var name: String? = null

            // 优先高德
            if (amapKey.isNotEmpty()) {
                name = reverseGeocodeAmap(lat, lng)
            }

            // 降级 Nominatim
            if (name == null) {
                name = reverseGeocodeNominatim(lat, lng)
            }

            val displayName = name ?: "已选位置"
            runOnUiThread {
                binding.tvLocationName.text = displayName
            }
        }.start()
    }

    private fun reverseGeocodeAmap(lat: Double, lng: Double): String? {
        return try {
            val url = "https://restapi.amap.com/v3/geocode/regeo" +
                    "?key=$amapKey&location=$lng,$lat&extensions=base&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val result = conn.inputStream.bufferedReader().readText()
            // 解析 formatted_address
            val key = "\"formatted_address\":\""
            val idx = result.indexOf(key)
            if (idx >= 0) {
                val start = idx + key.length
                val end = result.indexOf("\"", start)
                if (end > start) result.substring(start, end) else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun reverseGeocodeNominatim(lat: Double, lng: Double): String? {
        return try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&accept-language=zh"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "NingNingMock/1.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val result = conn.inputStream.bufferedReader().readText()
            val key = "\"display_name\":\""
            val idx = result.indexOf(key)
            if (idx >= 0) {
                val start = idx + key.length
                var end = start
                while (end < result.length && result[end] != '"') {
                    if (result[end] == '\\') end++
                    end++
                }
                result.substring(start, end).replace("\\/", "/")
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
    }

    /**
     * 设置搜索
     */
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

    private fun doSearch(query: String) {
        Toast.makeText(this, "正在搜索: $query", Toast.LENGTH_SHORT).show()
        performSearch(query)
    }

    /**
     * 搜索地址：优先高德，失败自动降级到 Nominatim
     */
    private fun performSearch(query: String) {
        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        Thread {
            var amapFailed = ""
            var nomFailed = ""
            try {
                // 1. 先试高德
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val amapResults = tryAmapSearch(encodedQuery)

                if (amapResults != null && amapResults.isNotEmpty()) {
                    runOnUiThread {
                        binding.btnSearch.isEnabled = true
                        binding.progressSearch.visibility = View.GONE
                        showSearchResults(amapResults, "高德地图")
                    }
                    return@Thread
                }
                amapFailed = "高德无结果"

                // 2. 高德没结果或失败，降级到 Nominatim
                val nomResults = tryNominatimSearch(query)

                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE
                    if (nomResults.isNotEmpty()) {
                        showSearchResults(nomResults, "OpenStreetMap")
                    } else {
                        nomFailed = "Nominatim无结果"
                        Toast.makeText(this, "未找到结果（$amapFailed，$nomFailed）\n请检查网络后重试", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE
                    Toast.makeText(this, "搜索出错: ${e.message}\n请检查网络连接", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 高德 POI 搜索（返回null表示失败）
     */
    private fun tryAmapSearch(encodedQuery: String): List<SearchResult>? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val url = "https://restapi.amap.com/v3/place/text" +
                    "?key=$amapKey&keywords=$encodedQuery" +
                    "&city=温州&citylimit=true" +
                    "&children=1&offset=10&page=1&extensions=all&output=json"
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "NingNingMock/1.6")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                // 高德不可用
                android.util.Log.w("Search", "高德返回HTTP $responseCode")
                return null
            }

            val result = conn.inputStream.bufferedReader().readText()
            android.util.Log.d("Search", "高德响应: ${result.take(200)}")

            // 检查返回状态
            if (result.contains("\"status\":\"0\"")) {
                val info = extractJsonStr(result, "\"info\":\"") ?: "未知"
                android.util.Log.w("Search", "高德搜索失败: $info")
                return null
            }

            parseAmapResults(result)
        } catch (e: Exception) {
            android.util.Log.e("Search", "高德搜索异常", e)
            null // 网络不通或超时，返回null让调用方降级
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Nominatim (OpenStreetMap) POI 搜索 — 免费，无需Key
     */
    private fun tryNominatimSearch(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        var conn: java.net.HttpURLConnection? = null
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            // 限定温州区域：viewbox格式 left,top,right,bottom
            val url = "https://nominatim.openstreetmap.org/search" +
                    "?q=$encodedQuery&format=json&limit=10" +
                    "&bounded=1&viewbox=119.5,28.8,121.5,27.0&accept-language=zh-CN"
            conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "NingNingMock/1.6")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                android.util.Log.w("Search", "Nominatim返回HTTP $responseCode")
                return results
            }

            val result = conn.inputStream.bufferedReader().readText()
            android.util.Log.d("Search", "Nominatim响应: ${result.take(200)}")
            parseNominatimResults(result)
        } catch (e: Exception) {
            android.util.Log.e("Search", "Nominatim搜索异常", e)
            results
        } finally {
            conn?.disconnect()
        }
    }
    }

    /**
     * 解析 Nominatim 搜索结果
     */
    private fun parseNominatimResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            // 简单解析 JSON 数组 [{...}, {...}]
            val items = json.split("\"type\":\"").toTypedArray()
            for (i in 1 until items.size) {
                val item = items[i]
                val displayName = extractJsonStr(item, "\"display_name\":\"") ?: continue
                val lat = extractJsonStr(item, "\"lat\":\"")?.toDoubleOrNull() ?: continue
                val lon = extractJsonStr(item, "\"lon\":\"")?.toDoubleOrNull() ?: continue
                // 取名称的第一部分作为简短名称
                val shortName = displayName.split(",").firstOrNull()?.trim() ?: displayName
                results.add(SearchResult(shortName, lat, lon))
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * 解析高德 POI 搜索结果
     */
    private fun parseAmapResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            // 检查状态
            if (!json.contains("\"status\":\"1\"")) return results

            // 解析每个 poi
            val pois = json.split("{\"id\":\"")
            for (i in 1 until pois.size) {
                val poi = pois[i]
                val name = extractJsonStr(poi, "\"name\":\"") ?: continue
                val location = extractJsonStr(poi, "\"location\":\"") ?: continue
                val parts = location.split(",")
                if (parts.size == 2) {
                    val lng = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    val address = extractJsonStr(poi, "\"address\":\"") ?: ""
                    results.add(SearchResult("$name $address".trim(), lat, lng))
                }
            }
        } catch (_: Exception) {}
        return results
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

    private fun showSearchResults(results: List<SearchResult>, source: String = "高德地图") {
        val names = results.mapIndexed { i, r ->
            "${i + 1}. ${r.name}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("搜索结果（$source）")
            .setItems(names) { _, which ->
                val r = results[which]
                binding.mapView.controller.setCenter(GeoPoint(r.lat, r.lng))
                binding.mapView.controller.setZoom(17.0)
                onMapClick(GeoPoint(r.lat, r.lng))
                binding.etSearch.setText("")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 高德 API Key 设置
     */
    private fun showAmapKeySetup() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "粘贴你的高德 Web服务 Key"
            setText(amapKey)
        }
        AlertDialog.Builder(this)
            .setTitle("配置高德地图 Key（免费）")
            .setMessage("搜索功能需要高德「Web服务」API Key，免费注册即可。\n\n" +
                    "1. 打开 https://lbs.amap.com/ → 控制台\n" +
                    "2. 应用管理 → 我的应用 → 创建应用\n" +
                    "3. 为应用添加 Key：\n" +
                    "   · 服务平台 选「Web服务」(不是Web端JS!)\n" +
                    "   · 提交\n" +
                    "4. 复制生成的 Key 粘贴到下方\n\n" +
                    "⚠️ Key类型必须是「Web服务」，选错会导致搜索无反应！\n\n" +
                    "不想注册？跳过后可直接在地图上点击选点。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    amapKey = key
                    prefs.edit().putString("amap_key", key).apply()
                    Toast.makeText(this, "Key 已保存！现在可以搜索了", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("跳过", null)
            .setNeutralButton("去注册") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.amap.com/dev/key/app")))
            }
            .show()
    }

    /**
     * 定位到我的位置
     */
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
                binding.mapView.controller.setCenter(point)
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

    private var currentLayer = 0
    private val layers = arrayOf("标准" to TileSourceFactory.MAPNIK)

    private fun toggleMapLayer() {
        currentLayer = (currentLayer + 1) % layers.size
        val (name, source) = layers[currentLayer]
        binding.mapView.setTileSource(source)
        binding.mapView.invalidate()
        Toast.makeText(this, "地图: $name", Toast.LENGTH_SHORT).show()
    }

    /**
     * 开始 GPS 模拟（带前置检查和WiFi警告）
     */
    private fun startMocking() {
        // 检查WiFi状态，提醒用户关闭
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (wifiManager.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ 请先关闭WiFi")
                .setMessage(
                    "检测到WiFi处于开启状态。\n\n" +
                    "钉钉、高德等APP会通过WiFi扫描获取真实位置，\n" +
                    "即使模拟了GPS，它们仍然能知道你的真实地址。\n\n" +
                    "请手动关闭WiFi后再开始模拟。\n\n" +
                    "操作路径：下拉通知栏 → 关闭WiFi\n\n" +
                    "关闭WiFi后点击「已关闭，开始模拟」"
                )
                .setPositiveButton("已关闭，开始模拟") { _, _ ->
                    doStartMockService()
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
        } else {
            doStartMockService()
        }
    }

    /**
     * 实际启动模拟服务
     */
    private fun doStartMockService() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LAT, selectedLat)
            putExtra(MockLocationService.EXTRA_LNG, selectedLng)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)

        isMocking = true
        updateUIState()
        startStatusUpdates()

        // 延迟1.5秒检查Service是否真正启动成功
        handler.postDelayed({
            if (!serviceBound) {
                // Service都没绑上
                showMockErrorDialog("服务启动失败，请尝试重新打开APP")
                stopMocking()
                return@postDelayed
            }
            val status = mockService?.getStatus()
            if (status != null) {
                if (!status.gpsRegistered && !status.networkRegistered) {
                    // Provider注册失败
                    val errMsg = status.error ?: "未知错误"
                    showMockErrorDialog(errMsg)
                    stopMocking()
                } else {
                    // 至少一个Provider成功了
                    Toast.makeText(this, "模拟已启动！GPS:${if(status.gpsRegistered) "✓" else "✗"} NET:${if(status.networkRegistered) "✓" else "✗"}", Toast.LENGTH_SHORT).show()
                    showMockTips()
                }
            }
        }, 1500)
    }

    /**
     * 显示模拟失败的详细错误对话框
     */
    private fun showMockErrorDialog(error: String) {
        val msg = when {
            error.contains("SecurityException") || error.contains("未设置模拟位置") ->
                "模拟位置权限未授予。\n\n请按以下步骤操作：\n" +
                "1. 打开 设置 → 系统 → 开发者选项\n" +
                "   （如果没有开发者选项：设置 → 关于手机 → 连续点击「版本号」7次）\n" +
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

    /**
     * 显示模拟使用提示（首次启动成功时）
     */
    private fun showMockTips() {
        if (prefs.getBoolean("mock_tips_shown_v2", false)) return
        prefs.edit().putBoolean("mock_tips_shown_v2", true).apply()
        AlertDialog.Builder(this)
            .setTitle("模拟已启动 ✓")
            .setMessage(
                "模拟正在运行中，请注意以下几点：\n\n" +
                "1. ⚠️ 必须关闭WiFi！\n" +
                "   钉钉、高德等APP通过WiFi扫描获取真实位置，\n" +
                "   WiFi开着的话模拟无效。\n\n" +
                "2. 关闭目标APP后重新打开\n" +
                "   已在运行的APP可能缓存了旧位置，\n" +
                "   切到后台杀掉再重新打开才能生效。\n\n" +
                "3. 部分APP可能仍检测到真实位置\n" +
                "   如钉钉使用阿里自研定位SDK，\n" +
                "   有自己的检测机制，不一定能完全模拟。\n\n" +
                "4. 模拟期间请保持本APP在后台运行\n" +
                "   不要从最近任务中划掉本APP。"
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
            var statusText = "推送: ${s.pushCount}次 | GPS:${if(s.gpsRegistered) "✓" else "✗"} NET:${if(s.networkRegistered) "✓" else "✗"}"
            binding.tvStatus.text = statusText

            // WiFi警告
            if (s.wifiEnabled) {
                binding.tvWifiStatus.text = "⚠️ WiFi开启中！目标APP可能检测到真实位置，请关闭WiFi"
                binding.tvWifiStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
            } else {
                binding.tvWifiStatus.text = "WiFi: 已关闭 ✓"
                binding.tvWifiStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            }
        }
    }

    private fun updateUIState() {
        if (isMocking) {
            binding.btnStartMock.text = "停止模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.tvStatusBar.text = "模拟中 ●"
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

    /**
     * 验证模拟位置应用是否已正确设置
     * Android 14+ 兼容：mock_location 可能返回 "0"（未设置）或包名
     * 某些ROM上返回的是应用UID数字字符串
     */
    private fun verifyMockLocationApp(): Boolean {
        try {
            val mockApp = Settings.Secure.getString(contentResolver, "mock_location")
            // 未设置
            if (mockApp.isNullOrEmpty() || mockApp == "0") {
                // 二次检查：尝试通过 AppOps 检查（Android 6+）
                if (checkMockAppOps()) return true
                showMockLocationGuide()
                return false
            }
            // 直接匹配包名
            if (mockApp == packageName) return true

            // 某些ROM返回的是应用UID，尝试比较
            try {
                val uid = mockApp.toIntOrNull()
                if (uid != null) {
                    val appUid = packageManager.getApplicationInfo(packageName, 0).uid
                    if (uid == appUid) return true
                }
            } catch (_: Exception) {}

            // 不匹配 → 弹窗提示，但只提示一次后不再弹窗（让用户手动选）
            if (!prefs.getBoolean("mock_guide_shown", false)) {
                prefs.edit().putBoolean("mock_guide_shown", true).apply()
                AlertDialog.Builder(this)
                    .setTitle("模拟位置应用不匹配")
                    .setMessage("检测到模拟位置应用设置为：$mockApp\n本应用包名：$packageName\n\n请确认在开发者选项中选择的是「宁宁模拟」。\n如果已正确选择，请直接点击「我已确认」继续。")
                    .setPositiveButton("去开发者选项") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                    .setNeutralButton("我已确认，继续") { _, _ ->
                        // 用户确认已设置好，跳过检查直接进入
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return false
            }

            // 用户已看过提示，允许继续尝试
            return true
        } catch (_: Exception) {
            return true // 无法读取，继续尝试
        }
    }

    /**
     * 通过 AppOpsManager 检查是否有 MOCK_LOCATION 权限（更可靠）
     */
    private fun checkMockAppOps(): Boolean {
        return try {
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
            result == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun showMockLocationGuide() {
        AlertDialog.Builder(this)
            .setTitle("需要开启模拟位置")
            .setMessage("请按以下步骤操作：\n\n" +
                    "1. 打开 设置\n" +
                    "2. 进入 开发者选项\n" +
                    "   （如果没有，前往 关于手机 → 连续点击「版本号」7次）\n" +
                    "3. 找到「选择模拟位置信息应用」\n" +
                    "4. 选择「宁宁模拟」\n\n" +
                    "设置完成后才能开始模拟定位。")
            .setPositiveButton("去开发者选项") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
            .setNegativeButton("稍后", null)
            .show()
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
}
