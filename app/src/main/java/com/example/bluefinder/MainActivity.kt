package com.example.bluefinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

// --- 数据模型 ---
data class BleDevice(val device: BluetoothDevice, var rssi: Int, val name: String)
data class CalibrationPoint(val distanceMeter: Float, val samples: List<Int>, val averageRssi: Float)
data class CalibrationProfile(
    val id: Long,
    val name: String,
    val attenuationFactor: Float,
    val txPowerAtOneMeter: Float,
    val points: List<CalibrationPoint>
)
enum class CalibrationStep { PREPARE, STEP_1M, STEP_2M, STEP_3M, RESULT }

// --- ViewModel ---
@SuppressLint("MissingPermission")
class BleViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("calibration_profiles", Context.MODE_PRIVATE)

    private val rawDevicesMap = mutableMapOf<String, BleDevice>()
    val devices: StateFlow<List<BleDevice>> = MutableStateFlow(emptyList())
    private var lastSortTime = 0L

    private val _targetDevice = MutableStateFlow<BleDevice?>(null)
    val targetDevice = _targetDevice.asStateFlow()

    private val _smoothedRssi = MutableStateFlow(-100)
    val smoothedRssi = _smoothedRssi.asStateFlow()

    var attenuationFactor by mutableFloatStateOf(4.5f)
    var txPowerAtOneMeter by mutableFloatStateOf(-59f)
    var closeThreshold by mutableIntStateOf(-35)
    var showDeviceType by mutableStateOf(false)
    var calibrationProfiles by mutableStateOf(emptyList<CalibrationProfile>())
    var selectedCalibrationId by mutableStateOf<Long?>(null)
    var calibrationDistances by mutableStateOf(listOf(1f, 2f, 3f))
    var calibrationSamplesPerPoint by mutableIntStateOf(8)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false
    private val rssiWindow = mutableListOf<Int>()
    private val WINDOW_SIZE = 5
    private val rssiSecondBuffer = mutableListOf<Int>()
    private var latestRssi: Int? = null
    private val deviceLastSeenMap = mutableMapOf<String, Long>()
    private var targetLastSeenAt: Long = 0L
    private val _isCalibrating = MutableStateFlow(false)
    val isCalibrating = _isCalibrating.asStateFlow()

    init {
        loadCalibrationProfiles()
        startRssiRefreshLoop()
    }

    fun initBluetooth(adapter: BluetoothAdapter) {
        bluetoothAdapter = adapter
        startPruneLoop()
    }

    // 🔥 新增：利用 Java 反射黑科技，强制一键取消配对！
    fun unpairDevice(device: BluetoothDevice) {
        try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device)
            // 解绑后稍微延迟，重新刷新列表
            viewModelScope.launch {
                delay(1000)
                clearAndRefresh()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleFoundDevice(device: BluetoothDevice, rssi: Int) {
        latestRssi = rssi
        val rawName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias ?: device.name else device.name
        } catch (e: Exception) { device.name }
        val name = if (rawName.isNullOrBlank()) "未知设备" else rawName

        if (name == "未知设备" && rssi < -90) return

        val address = device.address
        rawDevicesMap[address] = BleDevice(device, rssi, name)
        deviceLastSeenMap[address] = System.currentTimeMillis()

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSortTime > 2000) {
            val sortedList = rawDevicesMap.values.toList().sortedWith(
                compareByDescending<BleDevice> { it.device.bondState == BluetoothDevice.BOND_BONDED }
                    .thenByDescending { it.rssi }
            )
            (devices as MutableStateFlow).value = sortedList
            lastSortTime = currentTime
        }

        if (_targetDevice.value?.device?.address == address) {
            targetLastSeenAt = System.currentTimeMillis()
            applyRssiFilter(rssi)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleFoundDevice(result.device, result.rssi)
        }
    }

    private val classicBtReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else { @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    if (device != null) {
                        handleFoundDevice(device, rssi)
                        val target = _targetDevice.value
                        if (target != null && target.device.address == device.address) {
                            if (device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                                bluetoothAdapter?.cancelDiscovery()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    val target = _targetDevice.value
                    if (target == null) {
                        if (isScanning) bluetoothAdapter?.startDiscovery()
                    } else {
                        if (target.device.type != BluetoothDevice.DEVICE_TYPE_LE) {
                            viewModelScope.launch {
                                delay(1000)
                                if (_targetDevice.value?.device?.type != BluetoothDevice.DEVICE_TYPE_LE) {
                                    bluetoothAdapter?.startDiscovery()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun startScan() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, leScanCallback)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        getApplication<Application>().registerReceiver(classicBtReceiver, filter)
        bluetoothAdapter?.startDiscovery()
        isScanning = true
    }

    fun stopScan() {
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        bluetoothAdapter?.cancelDiscovery()
        try { getApplication<Application>().unregisterReceiver(classicBtReceiver) } catch (e: Exception) { }
    }

    fun forceManualRefresh() {
        viewModelScope.launch {
            bluetoothAdapter?.cancelDiscovery()
            delay(300)
            bluetoothAdapter?.startDiscovery()
        }
    }

    fun clearAndRefresh() {
        rawDevicesMap.clear()
        (devices as MutableStateFlow).value = emptyList()
        bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    fun selectDevice(device: BleDevice) {
        _targetDevice.value = device
        targetLastSeenAt = System.currentTimeMillis()
        rssiWindow.clear()
        applyRssiFilter(device.rssi)

        if (device.device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            bluetoothAdapter?.cancelDiscovery()
        } else {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    fun disconnect() {
        _targetDevice.value = null
        bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    fun isTargetSignalTimedOut(timeoutMs: Long = 4500L): Boolean {
        val target = _targetDevice.value ?: return false
        if (!rawDevicesMap.containsKey(target.device.address)) return true
        return System.currentTimeMillis() - targetLastSeenAt > timeoutMs
    }

    private fun startPruneLoop() {
        viewModelScope.launch {
            while (true) {
                delay(2000)
                val now = System.currentTimeMillis()
                val staleAddresses = deviceLastSeenMap.filterValues { now - it > 7000 }.keys
                if (staleAddresses.isNotEmpty()) {
                    staleAddresses.forEach {
                        rawDevicesMap.remove(it)
                        deviceLastSeenMap.remove(it)
                    }
                    (devices as MutableStateFlow).value = rawDevicesMap.values.toList().sortedByDescending { it.rssi }
                }
            }
        }
    }

    private fun applyRssiFilter(newRssi: Int) {
        rssiSecondBuffer.add(newRssi)
    }

    private fun startRssiRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (rssiSecondBuffer.isNotEmpty()) {
                    val secondTrimmed = trimmedAverage(rssiSecondBuffer).toInt()
                    rssiSecondBuffer.clear()
                    rssiWindow.add(secondTrimmed)
                    if (rssiWindow.size > WINDOW_SIZE) rssiWindow.removeAt(0)
                    _smoothedRssi.value = trimmedAverage(rssiWindow).toInt()
                }
            }
        }
    }

    suspend fun collectSamplesForDistance(distanceMeter: Float, sampleCount: Int): CalibrationPoint {
        _isCalibrating.value = true
        val values = mutableListOf<Int>()
        repeat(sampleCount) {
            delay(350)
            latestRssi?.let(values::add)
        }
        _isCalibrating.value = false
        val cleaned = trimExtremes(values)
        val avg = if (cleaned.isEmpty()) -100f else cleaned.average().toFloat()
        return CalibrationPoint(distanceMeter, values, avg)
    }

    private fun trimExtremes(values: List<Int>): List<Int> {
        if (values.size < 3) return values
        val sorted = values.sorted()
        return sorted.subList(1, sorted.size - 1)
    }
    fun trimmedAverage(values: List<Int>): Float {
        val cleaned = trimExtremes(values)
        return if (cleaned.isEmpty()) -100f else cleaned.average().toFloat()
    }

    fun buildProfileFromPoints(name: String, points: List<CalibrationPoint>): CalibrationProfile {
        val regression = fitPathLoss(points)
        return CalibrationProfile(
            id = System.currentTimeMillis(),
            name = name,
            attenuationFactor = regression.second,
            txPowerAtOneMeter = regression.first,
            points = points
        )
    }

    private fun fitPathLoss(points: List<CalibrationPoint>): Pair<Float, Float> {
        val xs = points.map { kotlin.math.log10(it.distanceMeter.toDouble()) }
        val ys = points.map { it.averageRssi.toDouble() }
        val xMean = xs.average()
        val yMean = ys.average()
        var num = 0.0
        var den = 0.0
        xs.indices.forEach { i ->
            num += (xs[i] - xMean) * (ys[i] - yMean)
            den += (xs[i] - xMean) * (xs[i] - xMean)
        }
        val slope = if (den == 0.0) -40.0 else num / den
        val intercept = yMean - slope * xMean
        val n = (-slope / 10.0).coerceIn(1.0, 20.0)
        return intercept.toFloat() to n.toFloat()
    }

    fun saveCalibrationProfile(profile: CalibrationProfile) {
        calibrationProfiles = listOf(profile) + calibrationProfiles
        persistCalibrationProfiles()
    }
    fun deleteCalibrationProfile(profileId: Long) {
        calibrationProfiles = calibrationProfiles.filterNot { it.id == profileId }
        if (selectedCalibrationId == profileId) selectedCalibrationId = null
        persistCalibrationProfiles()
    }

    fun applyCalibration(profile: CalibrationProfile) {
        attenuationFactor = profile.attenuationFactor
        txPowerAtOneMeter = profile.txPowerAtOneMeter
        selectedCalibrationId = profile.id
    }
    fun readCurrentRssi(): Int? = latestRssi

    private fun loadCalibrationProfiles() {
        val raw = prefs.getString("profiles_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val loaded = mutableListOf<CalibrationProfile>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pointsArray = obj.getJSONArray("points")
            val points = mutableListOf<CalibrationPoint>()
            for (j in 0 until pointsArray.length()) {
                val p = pointsArray.getJSONObject(j)
                val samplesArray = p.getJSONArray("samples")
                val samples = mutableListOf<Int>()
                for (k in 0 until samplesArray.length()) samples.add(samplesArray.getInt(k))
                points.add(CalibrationPoint(p.getDouble("distance").toFloat(), samples, p.getDouble("avg").toFloat()))
            }
            loaded.add(
                CalibrationProfile(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    attenuationFactor = obj.getDouble("n").toFloat(),
                    txPowerAtOneMeter = obj.getDouble("tx").toFloat(),
                    points = points
                )
            )
        }
        calibrationProfiles = loaded
    }

    private fun persistCalibrationProfiles() {
        val arr = JSONArray()
        calibrationProfiles.forEach { profile ->
            val points = JSONArray()
            profile.points.forEach { point ->
                points.put(
                    JSONObject()
                        .put("distance", point.distanceMeter)
                        .put("avg", point.averageRssi)
                        .put("samples", JSONArray(point.samples))
                )
            }
            arr.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("n", profile.attenuationFactor)
                    .put("tx", profile.txPowerAtOneMeter)
                    .put("points", points)
            )
        }
        prefs.edit().putString("profiles_json", arr.toString()).apply()
    }
}

fun calculateDistance(rssi: Int, n: Float, txPowerAtOneMeter: Float): Double {
    return 10.0.pow((txPowerAtOneMeter - rssi) / (10.0 * n))
}

// --- 页面路由枚举 ---
enum class AppScreen { SCANNER, FINDING, SETTINGS, CALIBRATION_LIST, CALIBRATION_RUN }

// --- MainActivity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissionLauncher.launch(requiredPermissions)

        setContent {
            BlueFinderTheme {
                val viewModel: BleViewModel = viewModel()

                // 🔥 新增：蓝牙开启状态检查与系统一键开启弹窗回调
                val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        viewModel.startScan() // 用户点击允许后，立刻开始扫描
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.initBluetooth(bluetoothManager.adapter)
                    // 如果蓝牙没开，直接弹出系统底层的“一键开启蓝牙”对话框
                    if (bluetoothManager.adapter != null && !bluetoothManager.adapter.isEnabled) {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } else {
                        viewModel.startScan()
                    }
                }

                var currentScreen by remember { mutableStateOf(AppScreen.SCANNER) }
                val target = viewModel.targetDevice.collectAsState().value
                LaunchedEffect(target, currentScreen) {
                    if (target == null && currentScreen == AppScreen.FINDING) currentScreen = AppScreen.SCANNER
                }

                Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                    when (screen) {
                        AppScreen.SCANNER -> ScannerScreen(
                            viewModel,
                            onNavigateToSettings = { currentScreen = AppScreen.SETTINGS },
                            onStartFinding = { currentScreen = AppScreen.FINDING }
                        )
                        AppScreen.FINDING -> FindingScreen(viewModel)
                        AppScreen.SETTINGS -> SettingsScreen(
                            viewModel,
                            onBack = { currentScreen = AppScreen.SCANNER },
                            onOpenCalibration = { currentScreen = AppScreen.CALIBRATION_LIST }
                        )
                        AppScreen.CALIBRATION_LIST -> CalibrationListScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = AppScreen.SETTINGS },
                            onNewCalibration = { currentScreen = AppScreen.CALIBRATION_RUN }
                        )
                        AppScreen.CALIBRATION_RUN -> CalibrationRunScreen(
                            viewModel = viewModel,
                            onBack = { currentScreen = AppScreen.CALIBRATION_LIST }
                        )
                    }
                }
            }
        }
    }
}

// --- UI: Scanner ---
@SuppressLint("MissingPermission")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(viewModel: BleViewModel, onNavigateToSettings: () -> Unit, onStartFinding: () -> Unit) {
    val devices by viewModel.devices.collectAsState()
    var showDialog by remember { mutableStateOf<BleDevice?>(null) }
    var showUnnamed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val filteredDevices = if (showUnnamed) devices else devices.filter { it.name != "未知设备" }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("BlueFinder", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showUnnamed, onCheckedChange = { showUnnamed = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0A84FF), uncheckedColor = Color.Gray))
                Text("未知设备", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }
        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = {
            isRefreshing = true
            coroutineScope.launch { viewModel.clearAndRefresh(); delay(1000); isRefreshing = false }
        }, modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = filteredDevices, key = { it.device.address }) { device ->
                    DeviceCard(device = device, showType = viewModel.showDeviceType, modifier = Modifier.animateItem()) {
                        val isBonded = device.device.bondState == BluetoothDevice.BOND_BONDED
                        val isGattConnected = bluetoothManager.getConnectionState(device.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED

                        if (isBonded || isGattConnected) {
                            showDialog = device
                        } else {
                            viewModel.selectDevice(device)
                            onStartFinding()
                        }
                    }
                }
            }
        }
    }

    // 🔥 全新的智能拦截与解绑弹窗
    showDialog?.let { device ->
        AlertDialog(onDismissRequest = { showDialog = null }, containerColor = Color(0xFF2C2C2E),
            title = { Text("⚠️ 查找已配对设备", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("如果设备正与手机保持连接，它将停止发送广播，导致雷达卡死。\n\n如需强制测距，您可以选择【一键取消配对】强行逼迫设备发出广播；如果您已确认它处于断开状态，请点击【直接查找】。", color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.selectDevice(device)
                    onStartFinding()
                    showDialog = null
                }) { Text("🎯 直接查找", color = Color(0xFF0A84FF), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // 🔥 黑科技：调用反序列化一键解绑，无需跳转设置！
                    viewModel.unpairDevice(device.device)
                    showDialog = null
                }) { Text("💥 一键取消配对", color = Color(0xFFFF453A)) }
            }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceCard(device: BleDevice, showType: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val isBonded = device.device.bondState == BluetoothDevice.BOND_BONDED
    val typeStr = when(device.device.type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
        BluetoothDevice.DEVICE_TYPE_LE -> "LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
        else -> "Unknown"
    }

    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1C1C1E)).clickable(onClick = onClick).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = device.name,
                        color = if (isBonded) Color(0xFFFFD60A) else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showType) {
                        Surface(color = Color(0xFF0A84FF).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = typeStr, color = Color(0xFF0A84FF), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(device.device.address, color = Color.Gray, fontSize = 12.sp)
            }
            Text("${device.rssi} dBm", color = Color(0xFF0A84FF), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun BackTitleButton(title: String, onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
        Spacer(Modifier.width(6.dp))
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CalibrationListScreen(viewModel: BleViewModel, onBack: () -> Unit, onNewCalibration: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackTitleButton("校准管理", onBack)
        }
        Button(onClick = onNewCalibration, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))) {
            Text("进行新校准")
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(viewModel.calibrationProfiles, key = { it.id }) { profile ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C1E)).padding(14.dp)
                ) {
                    Column {
                        Text(profile.name, color = Color.White, fontWeight = FontWeight.Medium)
                        Text("n=${"%.2f".format(profile.attenuationFactor)} / Tx=${profile.txPowerAtOneMeter.toInt()} dBm", color = Color(0xFF8E8E93), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.applyCalibration(profile) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))) { Text("应用") }
                            Button(onClick = { viewModel.deleteCalibrationProfile(profile.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))) { Text("删除") }
                        }
                    }
                }
            }
        }
        if (viewModel.calibrationProfiles.isEmpty()) {
            Text("当前没有历史校准", color = Color.Gray, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
fun CalibrationRunScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val device = viewModel.targetDevice.collectAsState().value
    val devices = viewModel.devices.collectAsState().value
    var points by remember { mutableStateOf(listOf<CalibrationPoint>()) }
    var step by remember { mutableStateOf(CalibrationStep.PREPARE) }
    var resultProfile by remember { mutableStateOf<CalibrationProfile?>(null) }
    var currentSamples by remember { mutableStateOf(listOf<Int>()) }
    var isRecording by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("校准_${System.currentTimeMillis() % 100000}") }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) { BackTitleButton("自动校准", onBack) }
        if (step == CalibrationStep.PREPARE) {
            Text("请选择目标设备，并按引导将手机放在 1m / 2m / 3m。", color = Color.Gray, fontSize = 13.sp)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.device.address }) { d ->
                    val selected = device?.device?.address == d.device.address
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Color(0xFF34C759) else Color(0xFF1C1C1E))
                            .clickable { viewModel.selectDevice(d) }
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(d.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(d.device.address, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Text("${d.rssi} dBm", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text("当前目标: ${device?.name ?: "未选择"}", color = Color.White, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(value = profileName, onValueChange = { profileName = it }, label = { Text("校准名称") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = device != null && profileName.isNotBlank(),
                onClick = { points = emptyList(); step = CalibrationStep.STEP_1M },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) { Text("开始校准引导") }
        } else if (step == CalibrationStep.STEP_1M || step == CalibrationStep.STEP_2M || step == CalibrationStep.STEP_3M) {
            val distance = when (step) {
                CalibrationStep.STEP_1M -> 1f
                CalibrationStep.STEP_2M -> 2f
                else -> 3f
            }
            val prompt = when (step) {
                CalibrationStep.STEP_1M -> "请将手机放置在距离蓝牙设备 1米 的位置"
                CalibrationStep.STEP_2M -> "请移动到距离设备 2米 的位置"
                else -> "请移动到距离设备 3米 的位置"
            }
            Text(prompt, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            if (currentSamples.isNotEmpty()) {
                Text("采样进度：${currentSamples.size}/${viewModel.calibrationSamplesPerPoint}", color = Color(0xFF8E8E93), fontSize = 13.sp)
                currentSamples.forEachIndexed { index, value ->
                    Text("第${index + 1}次：$value dBm", color = Color(0xFF8E8E93), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !isRecording,
                onClick = {
                    scope.launch {
                        isRecording = true
                        currentSamples = emptyList()
                        repeat(viewModel.calibrationSamplesPerPoint) {
                            delay(350)
                            viewModel.readCurrentRssi()?.let { rssi -> currentSamples = currentSamples + rssi }
                        }
                        val avg = viewModel.trimmedAverage(currentSamples)
                        val point = CalibrationPoint(distance, currentSamples, avg)
                        points = points + point
                        currentSamples = emptyList()
                        isRecording = false
                        step = when (step) {
                            CalibrationStep.STEP_1M -> CalibrationStep.STEP_2M
                            CalibrationStep.STEP_2M -> CalibrationStep.STEP_3M
                            else -> {
                                resultProfile = viewModel.buildProfileFromPoints(profileName, points + point)
                                CalibrationStep.RESULT
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) { Text("我已放好，记录 ${distance.toInt()} 米数据") }
        } else {
            val profile = resultProfile
            Text("计算完成", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (profile != null) {
                Text("衰减因子 n = ${"%.3f".format(profile.attenuationFactor)}", color = Color.White)
                Text("1米基准值 TxPower = ${"%.1f".format(profile.txPowerAtOneMeter)} dBm", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.saveCalibrationProfile(profile)
                        viewModel.applyCalibration(profile)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                ) { Text("保存并完成") }
            }
        }
        Spacer(Modifier.height(14.dp))
        points.forEach { p ->
            Text("${p.distanceMeter.toInt()}m: 原始${p.samples} -> 均值 ${"%.1f".format(p.averageRssi)} dBm", color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
    }
}

// --- UI: Settings Page ---
@Composable
fun SettingsScreen(viewModel: BleViewModel, onBack: () -> Unit, onOpenCalibration: () -> Unit) {
    BackHandler(onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            BackTitleButton("设置", onBack)
        }

        Text("衰减因子 (n) - 当前: ${String.format("%.1f", viewModel.attenuationFactor)}", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text("决定距离预估的敏感度。数值越大，算出的距离越长。", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.attenuationFactor = 2.0f },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) {
                Text("开阔(2.0)", color = if(viewModel.attenuationFactor == 2.0f) Color(0xFF0A84FF) else Color.White, fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = { viewModel.attenuationFactor = 3.5f },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) {
                Text("常规(3.5)", color = if(viewModel.attenuationFactor == 3.5f) Color(0xFF0A84FF) else Color.White, fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = { viewModel.attenuationFactor = 4.5f },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) {
                Text("复杂(4.5)", color = if(viewModel.attenuationFactor == 4.5f) Color(0xFF0A84FF) else Color.White, fontSize = 13.sp, maxLines = 1)
            }
        }

        Slider(
            value = viewModel.attenuationFactor,
            onValueChange = { viewModel.attenuationFactor = it },
            valueRange = 1.0f..20.0f,
            colors = SliderDefaults.colors(thumbColor = Color(0xFF0A84FF), activeTrackColor = Color(0xFF0A84FF))
        )
        Text("1米强度基准值 (Tx @1m): ${viewModel.txPowerAtOneMeter.toInt()} dBm", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        Slider(
            value = viewModel.txPowerAtOneMeter,
            onValueChange = { viewModel.txPowerAtOneMeter = it },
            valueRange = -90f..-20f,
            colors = SliderDefaults.colors(thumbColor = Color(0xFF64D2FF), activeTrackColor = Color(0xFF64D2FF))
        )
        Button(
            onClick = onOpenCalibration,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Text("进入自动校准", color = Color.White)
        }

        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("“十分接近” 触发阈值: ${viewModel.closeThreshold} dBm", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text("大于此信号强度时，屏幕将变绿。靠零越近越难触发。", color = Color.Gray, fontSize = 12.sp)
            }
            TextButton(onClick = { viewModel.closeThreshold = -35 }) {
                Text("恢复默认", color = Color(0xFF0A84FF), fontSize = 14.sp)
            }
        }

        Slider(
            value = viewModel.closeThreshold.toFloat(),
            onValueChange = { viewModel.closeThreshold = it.toInt() },
            valueRange = -60f..-20f,
            steps = 39,
            colors = SliderDefaults.colors(thumbColor = Color(0xFF34C759), activeTrackColor = Color(0xFF34C759))
        )

        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("显示蓝牙类型", color = Color.White, fontSize = 16.sp)
                Text("在列表中显示 LE 或 Classic 标签", color = Color.Gray, fontSize = 12.sp)
            }
            Switch(
                checked = viewModel.showDeviceType,
                onCheckedChange = { viewModel.showDeviceType = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF0A84FF))
            )
        }

        Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))

        Text("关于", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        Text("@苏不拉细 / @subulaxi", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        val uriHandler = LocalUriHandler.current
        Text(
            "项目地址: https://github.com/Subulaxi/BlueFinder",
            color = Color(0xFF0A84FF),
            fontSize = 14.sp,
            modifier = Modifier.clickable { uriHandler.openUri("https://github.com/Subulaxi/BlueFinder") }
        )
    }
}

// --- UI: Finding Page ---
@Composable
fun FindingScreen(viewModel: BleViewModel) {
    BackHandler { viewModel.disconnect() }
    val rssi by viewModel.smoothedRssi.collectAsState()
    val device = viewModel.targetDevice.collectAsState().value
    val context = LocalContext.current

    val isVeryClose = rssi > viewModel.closeThreshold
    val distance = calculateDistance(rssi, viewModel.attenuationFactor, viewModel.txPowerAtOneMeter)
    val isSignalTimeout = viewModel.isTargetSignalTimedOut()

    val bgColor by animateColorAsState(
        targetValue = if (isVeryClose) Color(0xFF34C759) else Color(0xFF121212),
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing), label = "bg_color"
    )

    LaunchedEffect(rssi) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (rssi > -60) {
            val delayTime = maxOf(50L, (100L + (rssi * 2L)))
            vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            delay(delayTime)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { viewModel.disconnect() }) {
                Text("← 返回", color = Color.White, fontSize = 18.sp)
            }
            IconButton(onClick = { viewModel.forceManualRefresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新信号", tint = Color.White)
            }
        }

        val infiniteTransition = rememberInfiniteTransition(label = "particles")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart),
            label = "particle_scale"
        )

        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart),
            label = "particle_alpha"
        )

        if (!isVeryClose) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val currentRadius = (size.minDimension / 4) * scale
                val glowBrush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = alpha),
                        Color.Transparent
                    ),
                    center = center,
                    radius = currentRadius.coerceAtLeast(1f)
                )
                drawCircle(brush = glowBrush, radius = currentRadius, center = center)
            }
        }

        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(targetState = isVeryClose, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) }, label = "TextTransition") { close ->
                if (close) {
                    Text("十分接近", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$rssi", color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Light)
                        Text("dBm", color = Color.White.copy(alpha = 0.5f), fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 16.dp, start = 8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            val subText = if (isSignalTimeout) "连接超时：未读取到最新信号" else if (isVeryClose) "当前信号: $rssi dBm" else "预估距离: 约 ${String.format("%.1f", distance)} 米"
            Text(text = subText, color = Color.White.copy(alpha = 0.7f), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.animateContentSize())
        }
        Text(text = "正在寻找: ${device?.name}", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
    }
}

@Composable
fun BlueFinderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF121212), surface = Color(0xFF1C1C1E)), content = content)
}
