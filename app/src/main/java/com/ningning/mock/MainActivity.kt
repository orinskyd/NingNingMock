package com.ningning.mock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
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
    private var mockService: MockLocationService? = null
    private var serviceBound = false
    private var isMocking = false

    // 地图相关
    private var currentMarker: Marker? = null
    private var selectedLat = 0.0
    private var selectedLng = 0.0
    private var searchMarker: Marker? = null

    // 状态刷新
    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    // 权限
    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    private val PERMISSION_REQUEST = 1001

    // Service连接
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

        // 初始化 osmdroid 配置
        Configuration.getInstance().apply {
            userAgentValue = packageName
            // 瓦片缓存目录
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupButtons()
        setupSearch()
        checkMockLocationEnabled()
    }

    /**
     * 配置 osmdroid 地图
     */
    private fun setupMap() {
        binding.mapView.apply {
            // 使用MAPNIK瓦片（OpenStreetMap标准风格，免费无需Key）
            setTileSource(TileSourceFactory.MAPNIK)

            // 启用多点触控
            setMultiTouchControls(true)

            // 设置默认视图（龙泉市中心）
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(28.0746, 119.1456))

            // 最小/最大缩放
            minZoomLevel = 4.0
            maxZoomLevel = 20.0

            // 地图点击事件
            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onMapClick(p)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            })
            overlays.add(0, eventsOverlay)

            // 指南针
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

        // 移除旧Marker
        currentMarker?.let { binding.mapView.overlays.remove(it) }

        // 放置新Marker
        currentMarker = Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "已选择位置"
            snippet = "经纬度: %.5f, %.5f".format(point.latitude, point.longitude)
            setOnMarkerClickListener { marker, _ ->
                marker.showInfoWindow()
                true
            }
        }
        binding.mapView.overlays.add(currentMarker)
        binding.mapView.invalidate()

        // 更新UI
        updateSelectedUI(point.latitude, point.longitude)
    }

    /**
     * 更新已选位置UI
     */
    private fun updateSelectedUI(lat: Double, lng: Double) {
        binding.tvCoords.text = "%.6f, %.6f".format(lat, lng)
        binding.cardSelected.visibility = View.VISIBLE
        binding.btnStartMock.isEnabled = true

        // 反向地理编码获取地址名称
        reverseGeocode(lat, lng)
    }

    /**
     * 反向地理编码（OSM Nominatim，免费）
     */
    private fun reverseGeocode(lat: Double, lng: Double) {
        Thread {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&accept-language=zh"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "NingNingMock/1.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val result = conn.inputStream.bufferedReader().readText()
                val displayName = extractDisplayName(result)

                runOnUiThread {
                    binding.tvLocationName.text = displayName ?: "已选位置"
                }
            } catch (_: Exception) {
                runOnUiThread {
                    binding.tvLocationName.text = "已选位置"
                }
            }
        }.start()
    }

    private fun extractDisplayName(json: String): String? {
        try {
            val displayNameKey = "\"display_name\":\""
            val idx = json.indexOf(displayNameKey)
            if (idx >= 0) {
                val start = idx + displayNameKey.length
                var end = start
                while (end < json.length && json[end] != '"') {
                    if (json[end] == '\\') end++ // 跳过转义
                    end++
                }
                return json.substring(start, end).replace("\\/", "/")
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 设置按钮事件
     */
    private fun setupButtons() {
        // 开始模拟
        binding.btnStartMock.setOnClickListener {
            if (selectedLat == 0.0 && selectedLng == 0.0) {
                Toast.makeText(this, "请先在地图上选择位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startMocking()
        }

        // 定位按钮（回到实际GPS位置）
        binding.fabMyLocation.setOnClickListener {
            goToMyLocation()
        }

        // 图层切换按钮
        binding.fabLayer.setOnClickListener {
            toggleMapLayer()
        }
    }

    /**
     * 设置搜索功能
     */
    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchLocation(query)
                }
                true
            } else {
                false
            }
        }

        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                searchLocation(query)
            }
        }
    }

    /**
     * 搜索地点（OSM Nominatim，免费）
     */
    private fun searchLocation(query: String) {
        binding.btnSearch.isEnabled = false
        binding.progressSearch.visibility = View.VISIBLE

        Thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedQuery&limit=5&accept-language=zh"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "NingNingMock/1.0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val result = conn.inputStream.bufferedReader().readText()
                val results = parseSearchResults(result)

                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE

                    if (results.isNotEmpty()) {
                        showSearchResults(results)
                    } else {
                        Toast.makeText(this, "未找到结果", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnSearch.isEnabled = true
                    binding.progressSearch.visibility = View.GONE
                    Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun parseSearchResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            // 简单JSON解析（避免引入额外依赖）
            val items = json.split("{\"place_id\"")
            for (i in 1 until items.size) {
                val item = items[i]
                val lat = extractJsonValue(item, "\"lat\":\"")?.toDoubleOrNull()
                val lng = extractJsonValue(item, "\"lon\":\"")?.toDoubleOrNull()
                val name = extractJsonValue(item, "\"display_name\":\"")
                if (lat != null && lng != null && name != null) {
                    results.add(SearchResult(name, lat, lng))
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun extractJsonValue(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        var end = start
        while (end < json.length && json[end] != '"') {
            if (json[end] == '\\') end++
            end++
        }
        return json.substring(start, end).replace("\\/", "/")
    }

    private fun showSearchResults(results: List<SearchResult>) {
        val names = results.map { "${it.name}\n(${it.lat}, ${it.lng})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("搜索结果")
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
     * 获取实际GPS位置
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

                // 放置蓝色圆点
                searchMarker?.let { binding.mapView.overlays.remove(it) }
                searchMarker = Marker(binding.mapView).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "我的位置"
                    icon = ContextCompat.getDrawable(
                        this@MainActivity,
                        android.R.drawable.presence_online
                    )
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
    private val layers = arrayOf(
        "标准" to TileSourceFactory.MAPNIK,
        "地形" to TileSourceFactory.HIKEBIKEMAP
    )

    /**
     * 切换地图图层
     */
    private fun toggleMapLayer() {
        currentLayer = (currentLayer + 1) % layers.size
        val (name, source) = layers[currentLayer]
        binding.mapView.setTileSource(source)
        binding.mapView.invalidate()
        Toast.makeText(this, "地图: $name", Toast.LENGTH_SHORT).show()
    }

    /**
     * 开始GPS模拟
     */
    private fun startMocking() {
        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LAT, selectedLat)
            putExtra(MockLocationService.EXTRA_LNG, selectedLng)
        }

        // 绑定 + 启动
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.startForegroundService(this, intent)

        isMocking = true
        updateUIState()

        // 开始状态刷新
        startStatusUpdates()
    }

    /**
     * 停止GPS模拟
     */
    private fun stopMocking() {
        if (serviceBound && mockService != null) {
            mockService?.stopMocking()
        }

        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}

        val intent = Intent(this, MockLocationService::class.java)
        stopService(intent)

        isMocking = false
        serviceBound = false
        mockService = null
        updateUIState()
        stopStatusUpdates()
    }

    /**
     * 开始状态定时刷新
     */
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

    /**
     * 更新状态显示
     */
    private fun updateStatus() {
        if (serviceBound && mockService != null) {
            val status = mockService!!.getStatus()
            binding.tvStatus.text = "推送: ${status.pushCount}次 | GPS:${if(status.gpsRegistered) "✓" else "✗"} NET:${if(status.networkRegistered) "✓" else "✗"}"
            binding.tvWifiStatus.text = if (status.wifiEnabled) "WiFi: 开启 ⚠" else "WiFi: 已关闭 ✓"
        }
    }

    /**
     * 更新UI状态
     */
    private fun updateUIState() {
        if (isMocking) {
            binding.btnStartMock.text = "停止模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            binding.tvStatusBar.text = "模拟中 ●"
            binding.tvStatusBar.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.cardSelected.visibility = View.VISIBLE
            binding.tvCoords.text = "%.6f, %.6f".format(selectedLat, selectedLng)

            // 切换按钮功能为停止
            binding.btnStartMock.setOnClickListener { stopMocking() }
        } else {
            binding.btnStartMock.text = "开始模拟"
            binding.btnStartMock.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            binding.tvStatusBar.text = "就绪"
            binding.tvStatusBar.setTextColor(getColor(android.R.color.darker_gray))
            binding.tvStatus.text = ""
            binding.tvWifiStatus.text = ""

            // 恢复按钮功能为开始
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
     * 检查是否开启了模拟位置
     */
    private fun checkMockLocationEnabled() {
        try {
            val mockApp = Settings.Secure.getString(
                contentResolver,
                "mock_location"
            )
            if (mockApp.isNullOrEmpty() || mockApp == "0") {
                AlertDialog.Builder(this)
                    .setTitle("需要开启模拟位置")
                    .setMessage("请在 设置 -> 开发者选项 -> 选择模拟位置信息应用 中选择「宁宁模拟」\n\n如果未开启开发者选项，请前往 设置 -> 关于手机 -> 连续点击「版本号」7次。")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        } catch (_: Exception) {}
    }

    /**
     * 权限检查
     */
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
            } else {
                Toast.makeText(this, "需要定位权限才能使用地图功能", Toast.LENGTH_LONG).show()
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
