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

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
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
            val name = reverseGeocodeAmap(lat, lng)
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

    private fun doSearch(query: String) {
        Toast.makeText(this, "正在搜索: $query ...", Toast.LENGTH_SHORT).show()
        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        Thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val result = searchViaAmap(encodedQuery, query)

                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE

                    if (result != null && result.isNotEmpty()) {
                        showSearchResults(result)
                    } else {
                        Toast.makeText(this,
                            "未找到: $query\n请尝试更短的搜索词", Toast.LENGTH_LONG).show()
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

    /**
     * 高德 POI 搜索
     * 去掉 citylimit，搜索范围更广
     * extensions=base 减少数据量
     * 超时10秒
     */
    private fun searchViaAmap(encodedQuery: String, rawQuery: String): List<SearchResult>? {
        val result = mutableListOf<SearchResult>()
        try {
            val url = "https://restapi.amap.com/v3/place/text" +
                    "?key=$amapKey&keywords=$encodedQuery" +
                    "&offset=10&page=1&extensions=base&output=json"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "NingNingMock/1.7")
            val responseCode = conn.responseCode

            if (responseCode != 200) {
                Log.e("Search", "Amap HTTP $responseCode")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            Log.d("Search", "Amap response length: ${body.length}")

            // Check status
            if (body.contains("\"status\":\"0\"")) {
                // Extract infocode for error detail
                val infoCode = extractJsonStr(body, "\"infocode\":\"") ?: "unknown"
                Log.e("Search", "Amap status=0, infocode=$infoCode")
                return null
            }

            // Parse pois
            if (!body.contains("\"pois\"")) {
                Log.e("Search", "Amap response has no pois field")
                return null
            }

            val poisSection = body.substring(body.indexOf("\"pois\":"))
            val pois = poisSection.split("{\"id\":\"")
            Log.d("Search", "Found ${pois.size - 1} POIs")

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
        return result
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
                binding.mapView.controller.setCenter(GeoPoint(r.lat, r.lng))
                binding.mapView.controller.setZoom(17.0)
                onMapClick(GeoPoint(r.lat, r.lng))
                binding.etSearch.setText("")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAmapKeySetup() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "粘贴你的高德 Web服务 Key"
            setText(amapKey)
        }
        AlertDialog.Builder(this)
            .setTitle("配置高德地图 Key")
            .setMessage("搜索需要高德 Web服务 API Key。\n\n" +
                    "1. 打开 https://lbs.amap.com/\n" +
                    "2. 控制台 - 应用管理 - 创建应用\n" +
                    "3. 添加 Key，服务平台选 Web服务\n" +
                    "4. 复制 Key 粘贴到下方")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    amapKey = key
                    prefs.edit().putString("amap_key", key).apply()
                    Toast.makeText(this, "Key 已保存", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("跳过", null)
            .show()
    }

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

    private fun startMocking() {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (wifiManager.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("请先关闭WiFi")
                .setMessage(
                    "检测到WiFi开启。\n" +
                    "钉钉、高德等APP会通过WiFi扫描获取真实位置，\n" +
                    "即使模拟了GPS也能识别真实地址。\n\n" +
                    "请关闭WiFi后点击「已关闭，开始模拟」。"
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

        handler.postDelayed({
            if (!serviceBound) {
                showMockErrorDialog("服务启动失败，请尝试重新打开APP")
                stopMocking()
                return@postDelayed
            }
            val status = mockService?.getStatus()
            if (status != null) {
                if (!status.gpsRegistered && !status.networkRegistered) {
                    val errMsg = status.error ?: "未知错误"
                    showMockErrorDialog(errMsg)
                    stopMocking()
                } else {
                    val detail = buildProviderDetail(status)
                    Toast.makeText(this, "模拟已启动 $detail", Toast.LENGTH_LONG).show()
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
        if (prefs.getBoolean("mock_tips_v3", false)) return
        prefs.edit().putBoolean("mock_tips_v3", true).apply()
        AlertDialog.Builder(this)
            .setTitle("模拟已启动")
            .setMessage(
                "使用提示：\n\n" +
                "1. 必须关闭WiFi\n" +
                "   钉钉等APP通过WiFi扫描获取真实位置。\n\n" +
                "2. 关闭目标APP后重新打开\n" +
                "   已运行的APP缓存了旧位置。\n\n" +
                "3. 确认开发者选项中已选择「宁宁模拟」\n" +
                "   设置-开发者选项-选择模拟位置信息应用。\n\n" +
                "4. 保持本APP在后台运行\n" +
                "   不要从最近任务中划掉。"
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
            binding.tvStatus.text = "推送: ${s.pushCount}次 | $detail"
            if (s.wifiEnabled) {
                binding.tvWifiStatus.text = "WiFi开启! 目标APP可能检测到真实位置"
            } else {
                binding.tvWifiStatus.text = "WiFi: 已关闭"
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
