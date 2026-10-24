package com.performance.gpstimer

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

enum class AppScreen { HUB_HOME, ACTIVE_TIMER, DASHBOARDS, TRACK_MANAGER, RUN_LOGS, SETTINGS }

private val satelliteTileCache = ConcurrentHashMap<String, ImageBitmap>()

class MainActivity : ComponentActivity() {
    private var telemetryService by mutableStateOf<GpsTelemetryService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            telemetryService = (service as GpsTelemetryService.LocalBinder).getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { telemetryService = null; isBound = false }
    }

    fun startAndBindTelemetryService() {
        if (!isBound) {
            val intent = Intent(this, GpsTelemetryService::class.java)
            ContextCompat.startForegroundService(this, intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } else telemetryService?.startRawGpsUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getInstance(this)
        val fallbackSettingsRepo = SettingsRepository(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startAndBindTelemetryService()
        }
        setContent {
            val settingsRepo = telemetryService?.settingsRepo ?: fallbackSettingsRepo
            val settings by settingsRepo.settings.collectAsState()
            val scheme = when (settings.themeMode) {
                ThemeMode.OLED_DARK -> darkColorScheme(background = Color(0xFF07090D), surface = Color(0xFF12161F), onSurface = Color.White, primary = Color(0xFF00E676), secondary = Color(0xFF00B0FF))
                ThemeMode.SLATE_DARK -> darkColorScheme(background = Color(0xFF111827), surface = Color(0xFF1F2937), onSurface = Color.White, primary = Color(0xFF10B981), secondary = Color(0xFF38BDF8))
                ThemeMode.DAYLIGHT_LIGHT -> lightColorScheme(background = Color(0xFFF1F5F9), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0F172A), primary = Color(0xFF059669), secondary = Color(0xFF0284C7))
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PerformanceTimerApp(telemetryService, settingsRepo, database.runDao()) { startAndBindTelemetryService() }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) { unbindService(connection); isBound = false }
        super.onDestroy()
    }
}

@Composable
fun PerformanceTimerApp(
    service: GpsTelemetryService?,
    settingsRepo: SettingsRepository,
    runDao: RunDao,
    onPermissionGranted: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val settings by settingsRepo.settings.collectAsState()
    val uiState by service?.uiState?.collectAsState() ?: remember { mutableStateOf(TelemetryUiState()) }
    val allRuns by runDao.getAllRuns().collectAsState(initial = emptyList())
    val allVehicles by runDao.getAllVehicles().collectAsState(initial = emptyList())
    val allTracks by runDao.getAllTracks().collectAsState(initial = emptyList())
    val allLaps by runDao.getAllLaps().collectAsState(initial = emptyList())

    var currentScreen by remember { mutableStateOf(AppScreen.HUB_HOME) }
    var enlargedPhotoUri by remember { mutableStateOf<String?>(null) }

    // Vehicle Photo Picker (Used only in Garage!)
    var photoTargetVehicle by remember { mutableStateOf<VehicleProfile?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && photoTargetVehicle != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            scope.launch { runDao.insertVehicle(photoTargetVehicle!!.copy(photoUri = uri.toString())) }
        }
    }

    BackHandler(enabled = enlargedPhotoUri != null || currentScreen != AppScreen.HUB_HOME) {
        if (enlargedPhotoUri != null) enlargedPhotoUri = null
        else { service?.disarmRun(); currentScreen = AppScreen.HUB_HOME }
    }

    // Orientation Control (Default is AUTO so Drag Tree never forces landscape unless chosen in Settings)
    LaunchedEffect(settings.orientationPref, currentScreen, uiState.mode) {
        activity?.requestedOrientation = when (settings.orientationPref) {
            OrientationPref.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPref.ALWAYS_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationPref.ALWAYS_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK -> {
                if (currentScreen == AppScreen.ACTIVE_TIMER && (uiState.mode == TestMode.DRAG_TREE || uiState.mode == TestMode.TRACK_CIRCUIT))
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) onPermissionGranted()
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT); perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    val activeTrack = allTracks.find { it.id == settings.activeTrackId } ?: allTracks.firstOrNull()
    val activeVehicle = allVehicles.find { it.id == settings.activeVehicleId } ?: allVehicles.firstOrNull()

    LaunchedEffect(activeTrack) { service?.updateActiveTrack(activeTrack) }

    if (!settings.onboardingCompleted && allVehicles.isEmpty()) {
        FirstLaunchVehicleOnboardingDialog { name, makeModel, weightKg, drivetrain ->
            scope.launch {
                val newId = runDao.insertVehicle(VehicleProfile(name = name, makeModel = makeModel, weightKg = weightKg, drivetrain = drivetrain))
                settingsRepo.update { it.copy(onboardingCompleted = true, activeVehicleId = newId) }
            }
        }
    }

    // Enlarged Car Image Lightbox Overlay when tapping Car Logo on Main Screen
    if (enlargedPhotoUri != null) {
        Dialog(onDismissRequest = { enlargedPhotoUri = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0D1118))
                    .border(2.dp, Color(0xFF00E676), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(activeVehicle?.name?.uppercase() ?: "VEHICLE PHOTO", color = Color(0xFF00E676), fontWeight = FontWeight.Black, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(10.dp))
                VehicleAvatarBadge(photoUri = enlargedPhotoUri!!, sizeDp = 260.dp)
                Spacer(Modifier.height(10.dp))
                Text("${activeVehicle?.makeModel ?: ""} • ${activeVehicle?.weightKg?.toInt() ?: 0} kg • ${activeVehicle?.drivetrain ?: ""}", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { enlargedPhotoUri = null }, modifier = Modifier.fillMaxWidth()) { Text("CLOSE PREVIEW") }
            }
        }
    }

    val dropPinAction: (Int) -> Unit = { pinType ->
        val lat = uiState.currentLat; val lon = uiState.currentLon
        scope.launch {
            val baseTrack = activeTrack ?: TrackLayout(name = "Custom Track 1", startLat = lat, startLon = lon)
            val updated = when (pinType) {
                0 -> baseTrack.copy(startLat = lat, startLon = lon)
                1 -> baseTrack.copy(sector1Lat = lat, sector1Lon = lon)
                2 -> baseTrack.copy(sector2Lat = lat, sector2Lon = lon)
                else -> baseTrack.copy(finishLat = lat, finishLon = lon)
            }
            val savedId = runDao.insertTrack(updated)
            val finalId = if (baseTrack.id == 0L) savedId else baseTrack.id
            settingsRepo.update { it.copy(activeTrackId = finalId) }
            Toast.makeText(context, "GPS Pin Saved (${String.format(Locale.US, "%.5f, %.5f", lat, lon)})", Toast.LENGTH_SHORT).show()
        }
    }

    // Check if Drag Tree is currently in Fullscreen Tree Stage (before acceleration launch)
    val isFullScreenTreeActive = currentScreen == AppScreen.ACTIVE_TIMER &&
            uiState.mode == TestMode.DRAG_TREE &&
            uiState.status != RunStatus.RUNNING &&
            uiState.status != RunStatus.COMPLETED

    Scaffold(
        bottomBar = {
            if (!isFullScreenTreeActive && !isLandscape) {
                MotorsportBottomNavBar(currentScreen) { currentScreen = it }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (!isFullScreenTreeActive) {
                GpsTopTelemetryBar(
                    uiState = uiState,
                    currentScreen = currentScreen,
                    showNavIconsInTopBar = isLandscape,
                    onBack = { service?.disarmRun(); currentScreen = AppScreen.HUB_HOME },
                    onNavigate = { currentScreen = it }
                )
                Spacer(Modifier.height(8.dp))
            }

            when (currentScreen) {
                AppScreen.HUB_HOME -> {
                    ModeHubScreen(
                        settings = settings,
                        activeVehicle = activeVehicle,
                        weather = uiState.weather,
                        isLandscape = isLandscape,
                        onRefreshWeather = { service?.refreshWeather() },
                        onTapCarPhoto = {
                            if (!activeVehicle?.photoUri.isNullOrBlank()) {
                                enlargedPhotoUri = activeVehicle?.photoUri
                            } else {
                                Toast.makeText(context, "Upload a car photo in the Garage tab!", Toast.LENGTH_SHORT).show()
                                currentScreen = AppScreen.SETTINGS
                            }
                        },
                        onSelectMode = { mode ->
                            service?.setTestMode(mode, activeTrack)
                            currentScreen = AppScreen.ACTIVE_TIMER
                        }
                    )
                }

                AppScreen.ACTIVE_TIMER -> {
                    ActiveTimerScreen(
                        uiState = uiState,
                        settings = settings,
                        activeTrack = activeTrack,
                        isLandscape = isLandscape,
                        isFullScreenTreeActive = isFullScreenTreeActive,
                        onExitTreeToHub = { service?.disarmRun(); currentScreen = AppScreen.HUB_HOME },
                        onUpdateCustomSpeeds = { s, e -> settingsRepo.update { it.copy(customStartSpeedKmh = s, customEndSpeedKmh = e) } },
                        onArmToggle = {
                            if (uiState.mode == TestMode.TOP_SPEED_LOG && uiState.status == RunStatus.TOP_SPEED_ACTIVE) {
                                service?.stopAndSaveTopSpeedLog()
                            } else if (uiState.status == RunStatus.IDLE || uiState.status == RunStatus.COMPLETED) {
                                service?.armSystem(activeTrack)
                            } else {
                                service?.disarmRun()
                            }
                        },
                        onDropTrackPin = dropPinAction,
                        onShareTimeslip = { run -> ExportEngine.shareTimeslipPdf(context, run, settings.useMph) }
                    )
                }

                AppScreen.DASHBOARDS -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MotorsportDashboardView(
                            uiState = uiState,
                            settings = settings,
                            onSelectStyle = { st -> settingsRepo.update { it.copy(dashStyle = st) } },
                            onUpdateSlotMetric = { idx, m ->
                                val list = settings.customDashSlots.toMutableList()
                                if (idx in list.indices) { list[idx] = m; settingsRepo.update { it.copy(customDashSlots = list) } }
                            }
                        )
                    }
                }

                AppScreen.TRACK_MANAGER -> {
                    TrackManagerScreen(
                        tracks = allTracks,
                        activeTrack = activeTrack,
                        uiState = uiState,
                        onSelectTrack = { t ->
                            settingsRepo.update { it.copy(activeTrackId = t.id) }
                            service?.setTestMode(TestMode.TRACK_CIRCUIT, t)
                            currentScreen = AppScreen.ACTIVE_TIMER
                        },
                        onActivateTrackForEditing = { t -> settingsRepo.update { it.copy(activeTrackId = t.id) } },
                        onCreateTrack = { name ->
                            scope.launch {
                                val newId = runDao.insertTrack(TrackLayout(name = name, startLat = uiState.currentLat, startLon = uiState.currentLon, isCircuit = true))
                                settingsRepo.update { it.copy(activeTrackId = newId) }
                            }
                        },
                        onDropPinOnActive = dropPinAction,
                        onDeleteTrack = { t -> scope.launch { runDao.deleteTrack(t) } }
                    )
                }

                AppScreen.RUN_LOGS -> {
                    RunHistoryScreen(
                        runs = allRuns,
                        useMph = settings.useMph,
                        onDeleteRun = { r -> scope.launch { runDao.deleteRun(r) } },
                        onSavePng = { r -> ExportEngine.saveTimeslipPngToGallery(context, r, settings.useMph) },
                        onShareSlip = { r -> ExportEngine.shareTimeslipPdf(context, r, settings.useMph) },
                        onExportCsv = { ExportEngine.shareRefinedCsv(context, allRuns, settings.useMph) }
                    )
                }

                AppScreen.SETTINGS -> {
                    SettingsScreen(
                        settings = settings,
                        vehicles = allVehicles,
                        onUpdateSettings = { transform -> settingsRepo.update(transform) },
                        onSelectObd = { mac, name -> service?.connectObdDevice(mac, name) },
                        onSelectGnss = { srcType, id, name -> service?.connectGnssDevice(srcType, id, name) },
                        onSaveVehicle = { v ->
                            scope.launch {
                                val id = runDao.insertVehicle(v)
                                if (v.id == 0L) settingsRepo.update { it.copy(activeVehicleId = id, onboardingCompleted = true) }
                            }
                        },
                        onDeleteVehicle = { v -> scope.launch { runDao.deleteVehicle(v) } },
                        onPickVehiclePhoto = { v ->
                            photoTargetVehicle = v
                            photoPickerLauncher.launch(arrayOf("image/*"))
                        },
                        onBackupDatabase = {
                            scope.launch {
                                val json = ExportEngine.exportFullBackupJson(runDao)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"; putExtra(Intent.EXTRA_TEXT, json)
                                }, "Export Backup JSON"))
                            }
                        },
                        onRestoreDatabase = { jsonStr ->
                            scope.launch {
                                val count = ExportEngine.importBackupJson(runDao, jsonStr)
                                Toast.makeText(context, "Restored $count runs!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Native Kotlin Esri World Satellite Slippy-Tile Map Renderer (Zero WebView dependencies!)
 */
@Composable
fun NativeSatelliteTrackMap(
    currentLat: Double,
    currentLon: Double,
    activeTrack: TrackLayout?,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableIntStateOf(17) }
    var tileUpdateCounter by remember { mutableIntStateOf(0) }

    val safeLat = if (currentLat != 0.0) currentLat else (activeTrack?.startLat ?: -31.67432)
    val safeLon = if (currentLon != 0.0) currentLon else (activeTrack?.startLon ?: 115.78685)

    val n = (1 shl zoom).toDouble()
    val exactX = ((safeLon + 180.0) / 360.0) * n
    val latRad = Math.toRadians(safeLat.coerceIn(-85.0, 85.0))
    val exactY = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0) * n
    val centerTileX = exactX.toInt()
    val centerTileY = exactY.toInt()

    // Fetch 3x3 surrounding satellite imagery tiles asynchronously
    LaunchedEffect(centerTileX, centerTileY, zoom) {
        withContext(Dispatchers.IO) {
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val tx = centerTileX + dx
                    val ty = centerTileY + dy
                    val key = "$zoom/$ty/$tx"
                    if (!satelliteTileCache.containsKey(key)) {
                        try {
                            val url = URL("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$ty/$tx")
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                setRequestProperty("User-Agent", "ApexTelemetry/4.0")
                                connectTimeout = 5000; readTimeout = 5000
                            }
                            if (conn.responseCode == 200) {
                                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                                if (bmp != null) {
                                    satelliteTileCache[key] = bmp.asImageBitmap()
                                    withContext(Dispatchers.Main) { tileUpdateCounter++ }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF080D14))
            .border(1.5.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
    ) {
        val trigger = tileUpdateCounter
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (trigger >= 0) {
                val tilePx = 320f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val fracX = (exactX - centerTileX).toFloat()
                val fracY = (exactY - centerTileY).toFloat()

                // Draw 3x3 Satellite Imagery Tiles
                for (dx in -2..2) {
                    for (dy in -2..2) {
                        val tx = centerTileX + dx
                        val ty = centerTileY + dy
                        val img = satelliteTileCache["$zoom/$ty/$tx"]
                        val left = cx + (dx - fracX) * tilePx
                        val top = cy + (dy - fracY) * tilePx
                        if (img != null) {
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(tilePx.roundToInt() + 2, tilePx.roundToInt() + 2)
                            )
                        } else {
                            // Radar grid fallback while tile downloads
                            drawRect(Color(0xFF0D1520), Offset(left, top), Size(tilePx, tilePx))
                            drawRect(Color(0xFF1E293B), Offset(left, top), Size(tilePx, tilePx), style = Stroke(1f))
                        }
                    }
                }

                fun latLonToOffset(lat: Double, lon: Double): Offset? {
                    if (lat == 0.0 && lon == 0.0) return null
                    val pxExact = ((lon + 180.0) / 360.0) * n
                    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                    val pyExact = ((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0) * n
                    val sx = cx + ((pxExact - exactX).toFloat() * tilePx)
                    val sy = cy + ((pyExact - exactY).toFloat() * tilePx)
                    return Offset(sx, sy)
                }

                // Draw Track Sector Pins & Connecting Line
                activeTrack?.let { t ->
                    val pts = listOfNotNull(
                        latLonToOffset(t.startLat, t.startLon)?.let { it to Color(0xFF00E676) },
                        latLonToOffset(t.sector1Lat, t.sector1Lon)?.let { it to Color(0xFF00B0FF) },
                        latLonToOffset(t.sector2Lat, t.sector2Lon)?.let { it to Color(0xFFFFC400) },
                        latLonToOffset(t.finishLat, t.finishLon)?.let { it to Color(0xFFFF5252) }
                    )
                    for (i in 0 until pts.size - 1) {
                        drawLine(Color(0xFF00B0FF), pts[i].first, pts[i + 1].first, strokeWidth = 5f)
                    }
                    pts.forEach { (pos, col) ->
                        drawCircle(Color.Black, radius = 11.dp.toPx(), center = pos)
                        drawCircle(col, radius = 8.dp.toPx(), center = pos)
                    }
                }

                // Draw Live Car Marker at Center
                drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(cx, cy))
                drawCircle(Color(0xFF00E676), radius = 8.dp.toPx(), center = Offset(cx, cy))
            }
        }

        // Satellite Map Overlay Controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("ESRI WORLD SATELLITE • Z$zoom", color = Color(0xFF00E676), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallFloatingActionButton(onClick = { zoom = (zoom - 1).coerceAtLeast(13) }, containerColor = Color(0xFF141B26), contentColor = Color.White, modifier = Modifier.size(32.dp)) {
                    Text("-", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                SmallFloatingActionButton(onClick = { zoom = (zoom + 1).coerceAtMost(18) }, containerColor = Color(0xFF141B26), contentColor = Color.White, modifier = Modifier.size(32.dp)) {
                    Text("+", fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun VehicleAvatarBadge(photoUri: String, sizeDp: Dp = 48.dp, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val bitmap by remember(photoUri) {
        mutableStateOf(
            if (photoUri.isNotBlank()) {
                try { context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            } else null
        )
    }
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Car Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.DirectionsCar, contentDescription = "Car", tint = Color(0xFF00E676), modifier = Modifier.size(sizeDp * 0.58f))
        }
    }
}

/**
 * Rich Graphical Dashboards with Realistic Tick Marks, Bezels, Needles & Overlays
 */
@Composable
fun MotorsportDashboardView(
    uiState: TelemetryUiState,
    settings: AppSettings,
    onSelectStyle: (DashStyle) -> Unit,
    onUpdateSlotMetric: (Int, DashMetricType) -> Unit
) {
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }
    val speedVal = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)
    val obd = uiState.obd
    val effectiveRpm = if (obd.connected && obd.rpm > 0) obd.rpm else (uiState.currentSpeedKmh * 38).toInt().coerceAtMost(settings.redlineRpm)
    val rpmRatio = (effectiveRpm.toFloat() / settings.redlineRpm.toFloat()).coerceIn(0f, 1f)
    val gear = when {
        uiState.currentSpeedKmh < 2.0 -> "N"
        uiState.currentSpeedKmh < 38.0 -> "1"
        uiState.currentSpeedKmh < 72.0 -> "2"
        uiState.currentSpeedKmh < 112.0 -> "3"
        uiState.currentSpeedKmh < 155.0 -> "4"
        uiState.currentSpeedKmh < 205.0 -> "5"
        else -> "6"
    }

    if (editingSlotIndex != null) {
        AlertDialog(
            onDismissRequest = { editingSlotIndex = null },
            title = { Text("ASSIGN SENSOR TO SLOT #${editingSlotIndex!! + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashMetricType.entries.forEach { m ->
                        Button(onClick = { onUpdateSlotMetric(editingSlotIndex!!, m); editingSlotIndex = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) {
                            Text(m.label, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DashStyle.entries.forEach { st ->
                val active = settings.dashStyle == st
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (active) Color(0xFF00E676) else Color(0xFF1E293B)).clickable { onSelectStyle(st) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(st.label, color = if (active) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 14-LED Shift Light Bezel Overlay
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF080B10)).border(1.5.dp, Color(0xFF263238), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0 until 14) {
                val lit = rpmRatio >= (i + 1) / 14f
                val col = if (i < 6) Color(0xFF00E676) else if (i < 10) Color(0xFFFFC400) else Color(0xFFFF1744)
                Box(modifier = Modifier.size(15.dp).clip(CircleShape).background(if (lit) col else Color(0xFF181F29)).border(1.dp, if (lit) Color.White else Color(0xFF2D3748), CircleShape))
            }
        }

        Spacer(Modifier.height(8.dp))

        when (settings.dashStyle) {
            DashStyle.GT3_MOTEC -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF141C27), Color(0xFF090C12))))
                        .border(2.5.dp, Color(0xFF2E3A4E), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("MOTEC C127 PRO TELEMETRY", color = Color(0xFF94A3B8), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("$effectiveRpm / ${settings.redlineRpm} RPM", color = Color(0xFF00E676), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { rpmRatio }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)), color = if (rpmRatio > 0.88f) Color(0xFFFF1744) else Color(0xFF00E676), trackColor = Color(0xFF1C222B))
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashBezelCell("ECT TEMP", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph), Color(0xFF00B0FF))
                            DashBezelCell("IAT TEMP", UnitConverter.formatTemp(obd.intakeTempC.toDouble(), settings.useMph), Color(0xFF00E676))
                            DashBezelCell("LONG G", String.format(Locale.US, "%+.2f G", uiState.currentG), Color(0xFFFFC400))
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.3f)
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF070A0F))
                                .border(2.dp, Color(0xFF00E676).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("GEAR", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(gear, color = Color(0xFFFFC400), fontSize = 56.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text(String.format(Locale.US, "%.0f %s", speedVal, speedUnit), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("LAP ${formatMsToSec(uiState.currentLapMs)}", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashBezelCell("MAP/BOOST", String.format(Locale.US, "%+.1f PSI", obd.boostPsi), Color(0xFFFF5252))
                            DashBezelCell("THROTTLE", String.format(Locale.US, "%.0f %%", obd.throttlePct), Color(0xFF00E676))
                            DashBezelCell("BATTERY", String.format(Locale.US, "%.1f V", obd.batteryVolts), Color(0xFF00B0FF))
                        }
                    }
                }
            }

            DashStyle.ANALOG_TWIN -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF141C27), Color(0xFF080A0F))))
                        .border(2.dp, Color(0xFF2E3A4E), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        GraphicalAnalogDial(title = "RPM x1000", valueText = "$effectiveRpm", unitText = "RPM", fraction = rpmRatio, needleColor = Color(0xFFFF1744))
                        GForceBallWidget(longG = uiState.currentG, latG = uiState.lateralG)
                        GraphicalAnalogDial(title = "VELOCITY", valueText = String.format(Locale.US, "%.0f", speedVal), unitText = speedUnit, fraction = (uiState.currentSpeedKmh.toFloat() / 260f).coerceIn(0f, 1f), needleColor = Color(0xFF00E676))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        MetricPill("BOOST", String.format(Locale.US, "%+.1f PSI", obd.boostPsi))
                        MetricPill("COOLANT", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph))
                        MetricPill("THROTTLE", String.format(Locale.US, "%.0f%%", obd.throttlePct))
                        MetricPill("PEAK G", String.format(Locale.US, "%.2fG", uiState.peakG))
                    }
                }
            }

            DashStyle.CYBER_STREET -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF090D14))
                        .border(2.dp, Color(0xFF00E676).copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Column {
                            Text("APEX DIGITAL CLUSTER", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(String.format(Locale.US, "%.1f", speedVal), color = Color(0xFF00E676), fontSize = 54.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("GEAR $gear • $speedUnit", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                            Text("$effectiveRpm RPM", color = Color(0xFFFFC400), fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashBezelCell("60 FT", formatNullableMs(uiState.live60ftMs ?: uiState.latestRun?.time60ftMs), Color(0xFF00E676), Modifier.weight(1f))
                        DashBezelCell("0-100", formatNullableMs(uiState.live0to100Ms ?: uiState.latestRun?.time0to100Ms), Color(0xFF00B0FF), Modifier.weight(1f))
                        DashBezelCell("1/4 MILE", formatNullableMs(uiState.latestRun?.primaryTimeMs), Color(0xFFFFC400), Modifier.weight(1f))
                    }
                }
            }

            DashStyle.CUSTOM_6_SLOT -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0D1118))
                        .border(2.dp, Color(0xFF263238), RoundedCornerShape(18.dp))
                        .padding(12.dp)
                ) {
                    Text("TAP ANY TILE TO CHANGE TELEMETRY CHANNEL:", color = Color(0xFF00B0FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    for (r in 0..1) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in 0..2) {
                                val idx = r * 3 + c
                                val m = settings.customDashSlots.getOrElse(idx) { DashMetricType.GPS_SPEED }
                                val vStr = when (m) {
                                    DashMetricType.GPS_SPEED -> String.format(Locale.US, "%.1f", speedVal)
                                    DashMetricType.OBD_RPM -> "$effectiveRpm"
                                    DashMetricType.BOOST_PSI -> String.format(Locale.US, "%+.1f", obd.boostPsi)
                                    DashMetricType.COOLANT_TEMP -> UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph)
                                    DashMetricType.INTAKE_TEMP -> UnitConverter.formatTemp(obd.intakeTempC.toDouble(), settings.useMph)
                                    DashMetricType.THROTTLE_PCT -> String.format(Locale.US, "%.0f%%", obd.throttlePct)
                                    DashMetricType.BATTERY_VOLTS -> String.format(Locale.US, "%.1fV", obd.batteryVolts)
                                    DashMetricType.LONG_G -> String.format(Locale.US, "%+.2fG", uiState.currentG)
                                    DashMetricType.LAT_G -> String.format(Locale.US, "%+.2fG", uiState.lateralG)
                                    DashMetricType.DENSITY_ALT -> "${uiState.weather.densityAltitudeFt}ft"
                                }
                                Column(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF141C27)).border(1.dp, Color(0xFF2E3A4E), RoundedCornerShape(10.dp)).clickable { editingSlotIndex = idx }.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(m.label, color = Color.Gray, fontSize = 9.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                                    Spacer(Modifier.height(4.dp))
                                    Text(vStr, color = Color(0xFF00E676), fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
                                }
                            }
                        }
                        if (r == 0) Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Realistic Analog Dial with Outer Metallic Bezel, 28 Tick Marks, Redline Arc, and Rotating Needle
 */
@Composable
fun GraphicalAnalogDial(title: String, valueText: String, unitText: String, fraction: Float, needleColor: Color) {
    Box(modifier = Modifier.size(124.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            // Metallic bezel + dark face
            drawCircle(Color(0xFF2E3A4E), radius = r, center = center)
            drawCircle(Color(0xFF080B10), radius = r - 4.dp.toPx(), center = center)

            val arcInset = 10.dp.toPx()
            val arcSize = Size(size.width - arcInset * 2, size.height - arcInset * 2)
            drawArc(Color(0xFF1E293B), 135f, 270f, false, topLeft = Offset(arcInset, arcInset), size = arcSize, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color(0xFFFF1744).copy(alpha = 0.45f), 350f, 55f, false, topLeft = Offset(arcInset, arcInset), size = arcSize, style = Stroke(8.dp.toPx(), cap = StrokeCap.Butt))
            drawArc(needleColor, 135f, 270f * fraction.coerceIn(0f, 1f), false, topLeft = Offset(arcInset, arcInset), size = arcSize, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))

            // 24 radial tick marks around dial
            for (i in 0..24) {
                val deg = 135f + (i / 24f) * 270f
                val rad = Math.toRadians(deg.toDouble())
                val isMajor = i % 3 == 0
                val outerR = r - 6.dp.toPx()
                val innerR = outerR - if (isMajor) 9.dp.toPx() else 5.dp.toPx()
                val sx = center.x + (innerR * cos(rad)).toFloat()
                val sy = center.y + (innerR * sin(rad)).toFloat()
                val ex = center.x + (outerR * cos(rad)).toFloat()
                val ey = center.y + (outerR * sin(rad)).toFloat()
                drawLine(if (i >= 20) Color(0xFFFF1744) else Color.White.copy(alpha = if (isMajor) 0.85f else 0.4f), Offset(sx, sy), Offset(ex, ey), strokeWidth = if (isMajor) 2.5f else 1.2f)
            }

            // Glowing physical needle
            val needleDeg = 135f + 270f * fraction.coerceIn(0f, 1f)
            val needleRad = Math.toRadians(needleDeg.toDouble())
            val nx = center.x + ((r - 16.dp.toPx()) * cos(needleRad)).toFloat()
            val ny = center.y + ((r - 16.dp.toPx()) * sin(needleRad)).toFloat()
            drawLine(needleColor, center, Offset(nx, ny), strokeWidth = 3.5.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 34.dp)) {
            Text(valueText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text("$title • $unitText", color = needleColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DashBezelCell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0E15)).border(1.dp, Color(0xFF263238), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
fun GForceBallWidget(longG: Double, latG: Double) {
    Box(modifier = Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFF080B10), radius = r, center = center)
            drawCircle(Color(0xFF2E3A4E), radius = r, center = center, style = Stroke(2.5f))
            drawCircle(Color(0xFF263238), radius = r * 0.5f, center = center, style = Stroke(1.5f))
            drawLine(Color(0xFF263238), Offset(0f, center.y), Offset(size.width, center.y))
            drawLine(Color(0xFF263238), Offset(center.x, 0f), Offset(center.x, size.height))
            val maxG = 1.5f
            val bx = (center.x + (latG.toFloat() / maxG) * r).coerceIn(6f, size.width - 6f)
            val by = (center.y - (longG.toFloat() / maxG) * r).coerceIn(6f, size.height - 6f)
            drawCircle(Color(0xFFFF1744), radius = 6.dp.toPx(), center = Offset(bx, by))
        }
    }
}

@Composable
fun ActiveTimerScreen(
    uiState: TelemetryUiState,
    settings: AppSettings,
    activeTrack: TrackLayout?,
    isLandscape: Boolean,
    isFullScreenTreeActive: Boolean,
    onExitTreeToHub: () -> Unit,
    onUpdateCustomSpeeds: (Double, Double) -> Unit,
    onArmToggle: () -> Unit,
    onDropTrackPin: (Int) -> Unit,
    onShareTimeslip: (RunRecord) -> Unit
) {
    val isArmedOrRunning = uiState.status != RunStatus.IDLE && uiState.status != RunStatus.COMPLETED
    val dispSpeed = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)

    // 1. FULLSCREEN DRAG TREE PRIOR TO LAUNCH -> Automatically transitions to Live Timeslip once acceleration is detected!
    if (isFullScreenTreeActive) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF080B10))
                .border(2.dp, Color(0xFF263238), RoundedCornerShape(16.dp))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(settings.treeType.label, color = Color(0xFFFFC400), fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                TextButton(onClick = onExitTreeToHub) { Text("BACK TO HUB", color = Color(0xFF00B0FF), fontSize = 11.sp) }
            }

            NhraDragLightingTree(
                stageState = uiState.treeStage,
                treeType = settings.treeType,
                reactionMs = uiState.reactionTimeMs,
                redLight = uiState.redLightJump,
                compactLandscape = isLandscape,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onArmToggle,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = if (isArmedOrRunning) "WATCH LIGHTS — LAUNCH ON GREEN (TAP TO ABORT)" else "STAGE & START FULLSCREEN TREE (WITH AUDIO)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        return
    }

    // 2. LIVE TELEMETRY & LIVE TIMESLIP OUTPUT (Also shown immediately after Drag Tree launch!)
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF1E293B)).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${uiState.mode.title} • ${uiState.status.label}", color = Color(0xFFFFC400), fontWeight = FontWeight.Black, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.height(8.dp))

        if (uiState.mode == TestMode.CUSTOM_SPEED) {
            InlineCustomSpeedSelector(settings = settings, onUpdateCustomSpeeds = onUpdateCustomSpeeds)
            Spacer(Modifier.height(8.dp))
        }

        if (uiState.mode == TestMode.TRACK_CIRCUIT) {
            NativeSatelliteTrackMap(
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                activeTrack = activeTrack,
                modifier = Modifier.fillMaxWidth().height(if (isLandscape) 190.dp else 235.dp)
            )
            Spacer(Modifier.height(8.dp))
            TrackPinControlsCard(activeTrack, uiState.currentLat, uiState.currentLon, onDropTrackPin)
            Spacer(Modifier.height(8.dp))
            MotorsportDashboardView(uiState, settings, {}, { _, _ -> })
        } else {
            PrimarySpeedAndTimerCard(
                dispSpeed = dispSpeed,
                speedUnit = speedUnit,
                uiState = uiState,
                useMph = settings.useMph,
                compact = isLandscape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TimeSlipCard(
                mode = uiState.mode,
                uiState = uiState,
                run = uiState.latestRun,
                useMph = settings.useMph,
                onShareTimeslip = onShareTimeslip
            )
        }

        Spacer(Modifier.height(10.dp))

        val btnLabel = when {
            uiState.mode == TestMode.TOP_SPEED_LOG && uiState.status == RunStatus.TOP_SPEED_ACTIVE -> "STOP & SAVE TOP SPEED LOG"
            uiState.mode == TestMode.TOP_SPEED_LOG -> "START TOP SPEED RECORDING"
            isArmedOrRunning -> "ABORT / RESET PASS"
            uiState.mode == TestMode.DRAG_TREE -> "RETURN TO FULLSCREEN DRAG TREE"
            else -> "ARM TIMING SYSTEM"
        }

        Button(
            onClick = onArmToggle,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
                contentColor = Color.Black
            )
        ) {
            Text(btnLabel, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun NhraDragLightingTree(
    stageState: TreeStageState,
    treeType: TreeType,
    reactionMs: Long?,
    redLight: Boolean,
    compactLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bulbSize = if (compactLandscape) 36.dp else 48.dp
    val spacing = if (compactLandscape) 6.dp else 10.dp

    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0D1017)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        StagingBeamRow("PRE-STAGE", stageState != TreeStageState.OFF)
        Spacer(Modifier.height(6.dp))
        StagingBeamRow("STAGE", stageState.ordinal >= TreeStageState.STAGE.ordinal)
        Spacer(Modifier.height(spacing))

        val a1 = stageState == TreeStageState.AMBER_1 || (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val a2 = stageState == TreeStageState.AMBER_2 || (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val a3 = stageState == TreeStageState.AMBER_3

        TreeBulb(Color(0xFFFFB300), a1, bulbSize)
        Spacer(Modifier.height(spacing))
        TreeBulb(Color(0xFFFFB300), a2, bulbSize)
        Spacer(Modifier.height(spacing))
        TreeBulb(Color(0xFFFFB300), a3, bulbSize)
        Spacer(Modifier.height(spacing))
        TreeBulb(Color(0xFF00E676), stageState == TreeStageState.GREEN, bulbSize)
        Spacer(Modifier.height(spacing))
        TreeBulb(Color(0xFFFF1744), stageState == TreeStageState.RED_FOUL || redLight, bulbSize)
    }
}

@Composable
private fun StagingBeamRow(label: String, isLit: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) { Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(if (isLit) Color(0xFFFFEA00) else Color(0xFF263238))) }
        }
    }
}

@Composable
private fun TreeBulb(color: Color, isLit: Boolean, sizeDp: Dp) {
    Box(modifier = Modifier.size(sizeDp).clip(CircleShape).background(if (isLit) color else Color(0xFF1C222B)).border(2.5.dp, if (isLit) Color.White else Color(0xFF2D3748), CircleShape))
}

@Composable
fun TimeSlipCard(
    mode: TestMode,
    uiState: TelemetryUiState,
    run: RunRecord?,
    useMph: Boolean,
    onShareTimeslip: (RunRecord) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE DIGITAL TIMESLIP", color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (run != null) {
                TextButton(onClick = { onShareTimeslip(run) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share Slip", tint = Color(0xFF00E676))
                    Spacer(Modifier.width(4.dp))
                    Text("EXPORT SLIP", color = Color(0xFF00E676), fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF263238))
        Spacer(Modifier.height(6.dp))

        val rt = uiState.reactionTimeMs ?: run?.reactionTimeMs
        val red = uiState.redLightJump || (run?.redLightJump == true)
        if (rt != null || mode == TestMode.DRAG_TREE) {
            SlipRow("REACTION TIME (R/T)", if (rt != null) "${formatMsToSec(rt)} ${if (red) "(RED JUMP)" else "(GREEN)"}" else "--.---", highlight = true)
        }

        if (mode == TestMode.TOP_SPEED_LOG) {
            val peakSpd = if (uiState.status == RunStatus.TOP_SPEED_ACTIVE) uiState.sessionMaxSpeedKmh else (run?.trapSpeedKmh ?: uiState.sessionMaxSpeedKmh)
            SlipRow("PEAK TOP SPEED", UnitConverter.formatSpeed(peakSpd, useMph), highlight = true)
            SlipRow("DURATION", formatMsToSec(if (uiState.status == RunStatus.TOP_SPEED_ACTIVE) uiState.liveElapsedMs else (run?.primaryTimeMs ?: 0L)))
            SlipRow("DISTANCE COVERED", UnitConverter.formatDist(if (uiState.status == RunStatus.TOP_SPEED_ACTIVE) uiState.liveDistanceM else (run?.totalDistanceM ?: 0.0), useMph))
        } else if (mode == TestMode.BRAKE_100_0) {
            SlipRow("START SPEED", UnitConverter.formatSpeed(run?.brakingStartSpeedKmh ?: 100.0, useMph))
            SlipRow("STOP TIME", formatNullableMs(run?.primaryTimeMs), highlight = true)
            SlipRow("STOP DISTANCE", run?.brakingDistanceM?.let { UnitConverter.formatDist(it, useMph) } ?: "---", highlight = true)
        } else {
            SlipRow("60 FT", formatNullableMs(uiState.live60ftMs ?: run?.time60ftMs))
            SlipRow("330 FT", formatNullableMs(uiState.live330ftMs ?: run?.time330ftMs))
            val eMs = uiState.liveEighthMs ?: run?.timeEighthMileMs
            val eSpd = uiState.liveEighthSpeedKmh ?: run?.speedEighthMileKmh
            SlipRow("1/8 MILE", if (eMs != null) "${formatMsToSec(eMs)} @ ${UnitConverter.formatSpeed(eSpd ?: 0.0, useMph)}" else "--.---")
            SlipRow("1000 FT", formatNullableMs(uiState.live1000ftMs ?: run?.time1000ftMs))
            SlipRow("PRIMARY ET", if (run != null) "${formatMsToSec(run.primaryTimeMs)} @ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}" else "--.---", highlight = true)
            SlipRow(if (useMph) "0 - 60 MPH" else "0 - 100 KM/H", formatNullableMs(uiState.live0to100Ms ?: run?.time0to100Ms))
        }
    }
}

/**
 * Garage & Settings Screen with Vehicle Edit, Delete, and Photo Upload
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectObd: (String, String) -> Unit,
    onSelectGnss: (GnssSourceType, String, String) -> Unit,
    onSaveVehicle: (VehicleProfile) -> Unit,
    onDeleteVehicle: (VehicleProfile) -> Unit,
    onPickVehiclePhoto: (VehicleProfile) -> Unit,
    onBackupDatabase: () -> Unit,
    onRestoreDatabase: (String) -> Unit
) {
    val context = LocalContext.current
    var showPreviousObd by remember { mutableStateOf(false) }
    var obdDropdownExpanded by remember { mutableStateOf(false) }
    var showPreviousGnss by remember { mutableStateOf(false) }
    var gnssDropdownExpanded by remember { mutableStateOf(false) }

    var editingVehicle by remember { mutableStateOf<VehicleProfile?>(null) }

    val allBtDevices = remember(settings.btObdAddress, showPreviousObd, showPreviousGnss) {
        HardwareDiscoveryHelper.getBluetoothDevices(context, settings.btObdAddress)
    }
    val usbGnssDevices = remember(settings.gnssSourceType, gnssDropdownExpanded) {
        HardwareDiscoveryHelper.getUsbGnssDevices(context)
    }
    val obdOptions = remember(allBtDevices, showPreviousObd) {
        if (showPreviousObd) allBtDevices else allBtDevices.filter { it.isCurrentlyConnected }
    }
    val gnssOptions = remember(usbGnssDevices, allBtDevices, showPreviousGnss, settings.gnssSourceType) {
        when (settings.gnssSourceType) {
            GnssSourceType.INTERNAL_GPS -> emptyList()
            GnssSourceType.USB_CABLE_GNSS -> usbGnssDevices
            GnssSourceType.BLUETOOTH_GNSS -> if (showPreviousGnss) allBtDevices else allBtDevices.filter { it.isCurrentlyConnected }
        }
    }

    var newVehName by remember { mutableStateOf("") }
    var newVehModel by remember { mutableStateOf("") }
    var newVehWeight by remember { mutableStateOf("1420") }
    var restoreJsonInput by remember { mutableStateOf("") }

    if (editingVehicle != null) {
        var eName by remember(editingVehicle) { mutableStateOf(editingVehicle!!.name) }
        var eModel by remember(editingVehicle) { mutableStateOf(editingVehicle!!.makeModel) }
        var eWeight by remember(editingVehicle) { mutableStateOf(editingVehicle!!.weightKg.toInt().toString()) }
        var eDrive by remember(editingVehicle) { mutableStateOf(editingVehicle!!.drivetrain) }

        AlertDialog(
            onDismissRequest = { editingVehicle = null },
            title = { Text("EDIT VEHICLE PROFILE", fontWeight = FontWeight.Black, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = eName, onValueChange = { eName = it }, label = { Text("Nickname") }, singleLine = true)
                    OutlinedTextField(value = eModel, onValueChange = { eModel = it }, label = { Text("Make & Model") }, singleLine = true)
                    OutlinedTextField(value = eWeight, onValueChange = { eWeight = it }, label = { Text("Weight (kg)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("RWD", "AWD", "FWD").forEach { dt ->
                            Button(
                                onClick = { eDrive = dt },
                                colors = ButtonDefaults.buttonColors(containerColor = if (eDrive == dt) Color(0xFF00E676) else Color(0xFF1E293B), contentColor = if (eDrive == dt) Color.Black else Color.White),
                                modifier = Modifier.weight(1f)
                            ) { Text(dt, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveVehicle(editingVehicle!!.copy(
                        name = if (eName.isBlank()) editingVehicle!!.name else eName.trim(),
                        makeModel = eModel.trim(),
                        weightKg = eWeight.toDoubleOrNull() ?: editingVehicle!!.weightKg,
                        drivetrain = eDrive
                    ))
                    editingVehicle = null
                }) { Text("SAVE CHANGES") }
            },
            dismissButton = {
                TextButton(onClick = { editingVehicle = null }) { Text("CANCEL") }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingsCard("GARAGE — VEHICLES (UPLOAD PHOTO, EDIT OR DELETE)") {
                Text("Tap a car's photo icon in the Garage to upload/change its picture:", color = Color.Gray, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                vehicles.forEach { v ->
                    val isSelected = settings.activeVehicleId == v.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0D1118))
                            .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) Color(0xFF00E676) else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onUpdateSettings { it.copy(activeVehicleId = v.id) } }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            VehicleAvatarBadge(photoUri = v.photoUri, sizeDp = 46.dp, onClick = { onPickVehiclePhoto(v) })
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (isSelected) "${v.name} (ACTIVE)" else v.name, color = if (isSelected) Color(0xFF00E676) else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${v.makeModel} • ${v.weightKg.toInt()}kg • ${v.drivetrain}", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Row {
                            IconButton(onClick = { editingVehicle = v }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Vehicle", tint = Color(0xFF00B0FF))
                            }
                            IconButton(
                                onClick = {
                                    if (vehicles.size > 1) onDeleteVehicle(v)
                                    else Toast.makeText(context, "Keep at least 1 vehicle profile", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Vehicle", tint = Color(0xFFFF5252))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newVehName, onValueChange = { newVehName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = newVehModel, onValueChange = { newVehModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = newVehWeight, onValueChange = { newVehWeight = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.width(68.dp))
                    Button(onClick = {
                        if (newVehName.isNotBlank()) {
                            onSaveVehicle(VehicleProfile(name = newVehName.trim(), makeModel = newVehModel.ifBlank { "Custom" }, weightKg = newVehWeight.toDoubleOrNull() ?: 1420.0, drivetrain = "RWD"))
                            newVehName = ""; newVehModel = ""
                        }
                    }) { Text("ADD") }
                }
            }
        }

        item {
            SettingsCard("OBD-II & EXTERNAL GNSS HARDWARE") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Show Previously Connected OBD", color = Color.Gray, fontSize = 12.sp)
                    Switch(checked = showPreviousObd, onCheckedChange = { showPreviousObd = it })
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { obdDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (settings.btObdName.isNotBlank()) "OBD: ${settings.btObdName}" else "Select OBD-II Adapter (${obdOptions.size}) ▼", color = Color(0xFF00E676))
                    }
                    DropdownMenu(expanded = obdDropdownExpanded, onDismissRequest = { obdDropdownExpanded = false }) {
                        DropdownMenuItem(text = { Text("Disconnect") }, onClick = { onSelectObd("", ""); obdDropdownExpanded = false })
                        obdOptions.forEach { dev ->
                            DropdownMenuItem(text = { Text(dev.name) }, onClick = { onSelectObd(dev.id, dev.name); obdDropdownExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GnssSourceType.entries.forEach { src ->
                        val sel = settings.gnssSourceType == src
                        Button(
                            onClick = { onSelectGnss(src, settings.gnssDeviceId, settings.gnssDeviceName) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (sel) Color(0xFF00B0FF) else Color(0xFF1E293B), contentColor = if (sel) Color.Black else Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (src == GnssSourceType.INTERNAL_GPS) "PHONE" else if (src == GnssSourceType.USB_CABLE_GNSS) "USB OTG" else "BT GNSS", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (settings.gnssSourceType != GnssSourceType.INTERNAL_GPS) {
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Show Previously Connected GNSS", color = Color.Gray, fontSize = 12.sp)
                        Switch(checked = showPreviousGnss, onCheckedChange = { showPreviousGnss = it })
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { gnssDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (settings.gnssDeviceName.isNotBlank()) "GNSS: ${settings.gnssDeviceName}" else "Select GNSS Receiver (${gnssOptions.size}) ▼", color = Color(0xFF00B0FF))
                        }
                        DropdownMenu(expanded = gnssDropdownExpanded, onDismissRequest = { gnssDropdownExpanded = false }) {
                            gnssOptions.forEach { dev ->
                                DropdownMenuItem(text = { Text(dev.name) }, onClick = { onSelectGnss(settings.gnssSourceType, dev.id, dev.name); gnssDropdownExpanded = false })
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard("DISPLAY, ORIENTATION & DRAG TREE") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("UNITS (KPH / MPH)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = { onUpdateSettings { it.copy(useMph = !it.useMph) } }) { Text(if (settings.useMph) "MPH / FT" else "KM/H / M") }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("ORIENTATION", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = {
                        val next = OrientationPref.entries[(settings.orientationPref.ordinal + 1) % OrientationPref.entries.size]
                        onUpdateSettings { it.copy(orientationPref = next) }
                    }) { Text(settings.orientationPref.label, fontSize = 11.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("THEME", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = {
                        val next = ThemeMode.entries[(settings.themeMode.ordinal + 1) % ThemeMode.entries.size]
                        onUpdateSettings { it.copy(themeMode = next) }
                    }) { Text(settings.themeMode.label) }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DRAG TREE MODE", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = {
                        val next = TreeType.entries[(settings.treeType.ordinal + 1) % TreeType.entries.size]
                        onUpdateSettings { it.copy(treeType = next) }
                    }) { Text(settings.treeType.label) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("1-FT DRAG ROLLOUT", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Switch(checked = settings.rolloutEnabled, onCheckedChange = { onUpdateSettings { s -> s.copy(rolloutEnabled = it) } })
                }
            }
        }

        item {
            SettingsCard("DATABASE BACKUP & RESTORE") {
                Button(onClick = onBackupDatabase, modifier = Modifier.fillMaxWidth()) { Text("EXPORT FULL JSON BACKUP") }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = restoreJsonInput, onValueChange = { restoreJsonInput = it }, label = { Text("Paste Backup JSON Here") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Button(onClick = { if (restoreJsonInput.isNotBlank()) { onRestoreDatabase(restoreJsonInput); restoreJsonInput = "" } }, enabled = restoreJsonInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("RESTORE RUNS")
                }
            }
        }
    }
}

@Composable
fun ModeHubScreen(
    settings: AppSettings,
    activeVehicle: VehicleProfile?,
    weather: WeatherSnapshot,
    isLandscape: Boolean,
    onRefreshWeather: () -> Unit,
    onTapCarPhoto: () -> Unit,
    onSelectMode: (TestMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF141B26), Color(0xFF0F141D))))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VehicleAvatarBadge(photoUri = activeVehicle?.photoUri ?: "", sizeDp = 46.dp, onClick = onTapCarPhoto)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(activeVehicle?.name?.uppercase() ?: "VEHICLE", color = Color(0xFF00E676), fontWeight = FontWeight.Black, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    Text(activeVehicle?.drivetrain ?: "RWD", color = Color(0xFF00B0FF), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(2.dp))
                Text("${UnitConverter.formatTemp(weather.tempC, settings.useMph)} • ${weather.pressureHpa.toInt()} hPa • DA ${weather.densityAltitudeFt} ft", color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRefreshWeather, modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF1E293B))) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh DA", tint = Color(0xFF00B0FF), modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isLandscape) 3 else 2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(TestMode.entries) { mode ->
                val accent = when (mode) {
                    TestMode.DRAG_TREE -> Color(0xFFFFC400)
                    TestMode.TOP_SPEED_LOG -> Color(0xFFE040FB)
                    TestMode.BRAKE_100_0 -> Color(0xFFFF5252)
                    TestMode.TRACK_CIRCUIT -> Color(0xFF00B0FF)
                    else -> Color(0xFF00E676)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .clickable { onSelectMode(mode) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(mode.badge, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(mode.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(mode.subtitle, color = Color.Gray, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun TrackManagerScreen(
    tracks: List<TrackLayout>,
    activeTrack: TrackLayout?,
    uiState: TelemetryUiState,
    onSelectTrack: (TrackLayout) -> Unit,
    onActivateTrackForEditing: (TrackLayout) -> Unit,
    onCreateTrack: (String) -> Unit,
    onDropPinOnActive: (Int) -> Unit,
    onDeleteTrack: (TrackLayout) -> Unit
) {
    var newTrackName by remember { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            NativeSatelliteTrackMap(
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                activeTrack = activeTrack,
                modifier = Modifier.fillMaxWidth().height(235.dp)
            )
        }
        item { TrackPinControlsCard(activeTrack, uiState.currentLat, uiState.currentLon, onDropPinOnActive) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newTrackName, onValueChange = { newTrackName = it }, label = { Text("New Circuit Name") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = {
                    onCreateTrack(if (newTrackName.isBlank()) "Custom Circuit ${tracks.size + 1}" else newTrackName.trim())
                    newTrackName = ""
                }) { Text("ADD TRACK") }
            }
        }
        items(tracks, key = { it.id }) { track ->
            val isCurrent = track.id == activeTrack?.id
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).border(if (isCurrent) 1.5.dp else 0.dp, if (isCurrent) Color(0xFF00E676) else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onActivateTrackForEditing(track) }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isCurrent) "${track.name} ✓" else track.name, color = if (isCurrent) Color(0xFF00E676) else Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Best Lap: ${formatNullableMs(track.bestLapMs)}", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Button(onClick = { onSelectTrack(track) }) { Text("RACE") }
                IconButton(onClick = { onDeleteTrack(track) }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252)) }
            }
        }
    }
}

@Composable
fun RunHistoryScreen(
    runs: List<RunRecord>,
    useMph: Boolean,
    onDeleteRun: (RunRecord) -> Unit,
    onSavePng: (RunRecord) -> Unit,
    onShareSlip: (RunRecord) -> Unit,
    onExportCsv: () -> Unit
) {
    var selectedPrimaryId by remember { mutableStateOf<Long?>(null) }
    var selectedCompareId by remember { mutableStateOf<Long?>(null) }
    val primaryRun = runs.find { it.id == selectedPrimaryId } ?: runs.firstOrNull()
    val compareRun = runs.find { it.id == selectedCompareId && it.id != primaryRun?.id }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF141B26), Color(0xFF1E293B)))).padding(14.dp)
            ) {
                Text("TIMESLIP & TELEMETRY EXPORT STUDIO", color = Color(0xFF00E676), fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { primaryRun?.let { onSavePng(it) } }, enabled = primaryRun != null, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black), modifier = Modifier.weight(1f)) {
                        Text("SAVE SLIP PNG", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(onClick = { primaryRun?.let { onShareSlip(it) } }, enabled = primaryRun != null, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF), contentColor = Color.Black), modifier = Modifier.weight(1f)) {
                        Text("SHARE PDF SLIP", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(onClick = onExportCsv, enabled = runs.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC400), contentColor = Color.Black), modifier = Modifier.weight(1f)) {
                        Text("EXPORT CSV", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        if (primaryRun != null) {
            item { RunComparisonGraph(primaryRun, compareRun, useMph) }
        }
        items(runs, key = { it.id }) { run ->
            val isPrimary = run.id == primaryRun?.id
            val isCompare = run.id == compareRun?.id
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).border(if (isPrimary || isCompare) 1.5.dp else 0.dp, if (isPrimary) Color(0xFF00E676) else if (isCompare) Color(0xFF00B0FF) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { selectedPrimaryId = run.id }.padding(14.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("PASS #${run.id} • ${run.testMode} • ${run.vehicleName}", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(run.timestampEpochMs)), color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row {
                        TextButton(onClick = { selectedCompareId = if (selectedCompareId == run.id) null else run.id }) {
                            Text(if (isCompare) "OVERLAY ON" else "COMPARE", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { onDeleteRun(run) }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252)) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(formatMsToSec(run.primaryTimeMs), color = MaterialTheme.colorScheme.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text("@ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}", color = Color(0xFF00B0FF), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun RunComparisonGraph(primaryRun: RunRecord, comparisonRun: RunRecord?, useMph: Boolean) {
    val traceA = remember(primaryRun.id) { TelemetrySerializer.decodeTrace(primaryRun.telemetryJson) }
    val traceB = remember(comparisonRun?.id) { comparisonRun?.let { TelemetrySerializer.decodeTrace(it.telemetryJson) } ?: emptyList() }
    var cursorFraction by remember { mutableFloatStateOf(0.5f) }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Text(
            text = if (comparisonRun != null) "TELEMETRY OVERLAY: #${primaryRun.id} (GREEN) vs #${comparisonRun.id} (CYAN)" else "VELOCITY & G-CURVE ANALYZER — PASS #${primaryRun.id}",
            color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        if (traceA.size < 2) {
            Box(modifier = Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text("Complete a GPS pass to view high-rate velocity curves.", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            val maxTimeMs = max(traceA.last().tMs, if (traceB.isNotEmpty()) traceB.last().tMs else 1L).coerceAtLeast(1000L)
            val maxSpeed = max(traceA.maxOf { it.speedKmh }, if (traceB.isNotEmpty()) traceB.maxOf { it.speedKmh } else 100.0).coerceAtLeast(60.0)
            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF080B10), RoundedCornerShape(10.dp))) {
                val w = size.width; val h = size.height
                for (i in 1..3) drawLine(Color(0xFF1E293B), Offset(0f, h * (i / 4f)), Offset(w, h * (i / 4f)))
                fun drawCurve(pts: List<TelemetryPoint>, col: Color) {
                    if (pts.size < 2) return
                    val p = Path()
                    pts.forEachIndexed { i, pt ->
                        val x = (pt.tMs.toFloat() / maxTimeMs.toFloat()) * w
                        val y = h - ((pt.speedKmh / maxSpeed).toFloat() * (h * 0.86f)) - 6f
                        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, col, style = Stroke(4f, cap = StrokeCap.Round))
                }
                drawCurve(traceA, Color(0xFF00E676))
                if (traceB.isNotEmpty()) drawCurve(traceB, Color(0xFF00B0FF))
                val cx = cursorFraction * w
                drawLine(Color.White.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
            }
            val targetMs = (cursorFraction * maxTimeMs).toLong()
            val ptA = traceA.minByOrNull { abs(it.tMs - targetMs) }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.US, "T: %.2fs", targetMs / 1000.0), color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                ptA?.let { Text("${UnitConverter.formatSpeed(it.speedKmh, useMph)} (${String.format(Locale.US, "%.2fG", it.longG)})", color = Color(0xFF00E676), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            }
            Slider(value = cursorFraction, onValueChange = { cursorFraction = it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun MotorsportBottomNavBar(currentScreen: AppScreen, onNavigate: (AppScreen) -> Unit) {
    NavigationBar(containerColor = Color(0xFF0D1118), modifier = Modifier.height(62.dp)) {
        listOf(
            Triple(AppScreen.HUB_HOME, "RACE HUB", Icons.Default.Home),
            Triple(AppScreen.DASHBOARDS, "DASHES", Icons.Default.Speed),
            Triple(AppScreen.TRACK_MANAGER, "SAT TRACK", Icons.Default.Map),
            Triple(AppScreen.RUN_LOGS, "SLIPS", Icons.Default.Assessment),
            Triple(AppScreen.SETTINGS, "GARAGE", Icons.Default.Settings)
        ).forEach { (screen, label, icon) ->
            val selected = currentScreen == screen || (screen == AppScreen.HUB_HOME && currentScreen == AppScreen.ACTIVE_TIMER)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
                label = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF00E676), selectedTextColor = Color(0xFF00E676), unselectedIconColor = Color.Gray, unselectedTextColor = Color.Gray, indicatorColor = Color(0xFF1E293B))
            )
        }
    }
}

@Composable
fun GpsTopTelemetryBar(
    uiState: TelemetryUiState,
    currentScreen: AppScreen,
    showNavIconsInTopBar: Boolean,
    onBack: () -> Unit,
    onNavigate: (AppScreen) -> Unit
) {
    val lockColor = when {
        uiState.externalGnssActive -> Color(0xFF00E5FF)
        uiState.satellitesUsed >= 10 && uiState.accuracyM <= 3.5f -> Color(0xFF00E676)
        uiState.satellitesUsed >= 6 -> Color(0xFFFFC400)
        else -> Color(0xFFFF5252)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (currentScreen != AppScreen.HUB_HOME) {
                IconButton(onClick = onBack, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00E676)) }
                Spacer(Modifier.width(4.dp))
            }
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(lockColor))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (uiState.externalGnssActive) "EXT GNSS ${String.format(Locale.US, "%.0fHz", uiState.currentHz)}" else "SATS ${uiState.satellitesUsed}/${uiState.satellitesVisible} • ±${String.format(Locale.US, "%.1fm", uiState.accuracyM)}",
                color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0D1118)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                Text("DA: ${uiState.weather.densityAltitudeFt} ft", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            if (showNavIconsInTopBar) {
                IconButton(onClick = { onNavigate(AppScreen.DASHBOARDS) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Speed, null, tint = Color.Gray) }
                IconButton(onClick = { onNavigate(AppScreen.TRACK_MANAGER) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Map, null, tint = Color.Gray) }
                IconButton(onClick = { onNavigate(AppScreen.RUN_LOGS) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Assessment, null, tint = Color.Gray) }
                IconButton(onClick = { onNavigate(AppScreen.SETTINGS) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Settings, null, tint = Color.Gray) }
            }
        }
    }
}

@Composable
private fun InlineCustomSpeedSelector(settings: AppSettings, onUpdateCustomSpeeds: (Double, Double) -> Unit) {
    val unitLabel = UnitConverter.speedUnit(settings.useMph)
    var startText by remember(settings.customStartSpeedKmh, settings.useMph) { mutableStateOf(String.format(Locale.US, "%.0f", UnitConverter.speed(settings.customStartSpeedKmh, settings.useMph))) }
    var endText by remember(settings.customEndSpeedKmh, settings.useMph) { mutableStateOf(String.format(Locale.US, "%.0f", UnitConverter.speed(settings.customEndSpeedKmh, settings.useMph))) }
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
        Text("CUSTOM SPEED INTERVAL ($unitLabel)", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it; it.toDoubleOrNull()?.let { v -> if (v >= 0.0) onUpdateCustomSpeeds(UnitConverter.toKmh(v, settings.useMph), settings.customEndSpeedKmh) } },
                label = { Text("Start ($unitLabel)", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it; it.toDoubleOrNull()?.let { v -> if (v > 5.0) onUpdateCustomSpeeds(settings.customStartSpeedKmh, UnitConverter.toKmh(v, settings.useMph)) } },
                label = { Text("Target ($unitLabel)", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PrimarySpeedAndTimerCard(dispSpeed: Double, speedUnit: String, uiState: TelemetryUiState, useMph: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(if (compact) 12.dp else 14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                Text("LIVE GPS VELOCITY", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(Locale.US, "%.1f", dispSpeed), color = MaterialTheme.colorScheme.onSurface, fontSize = if (compact) 34.sp else 42.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(4.dp))
                    Text(speedUnit, color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ELAPSED TIME", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(formatMsToSec(uiState.liveElapsedMs), color = Color(0xFF00E676), fontSize = if (compact) 30.sp else 36.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp)); HorizontalDivider(color = Color(0xFF263238)); Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricPill("DISTANCE", UnitConverter.formatDist(uiState.liveDistanceM, useMph))
            if (uiState.mode == TestMode.TOP_SPEED_LOG) {
                MetricPill("PEAK SPEED", UnitConverter.formatSpeed(uiState.sessionMaxSpeedKmh, useMph))
            } else {
                MetricPill("LONG G", String.format(Locale.US, "%.2f G", uiState.currentG))
            }
            MetricPill("PEAK G", String.format(Locale.US, "%.2f G", uiState.peakG))
        }
    }
}

@Composable
private fun TrackPinControlsCard(activeTrack: TrackLayout?, currentLat: Double, currentLon: Double, onDropTrackPin: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
        Text("SATELLITE SECTOR PINS — ${activeTrack?.name ?: "Custom Track"}", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(String.format(Locale.US, "Live GPS: %.5f, %.5f", currentLat, currentLon), color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PinDropButton("START", (activeTrack?.startLat ?: 0.0) != 0.0, { onDropTrackPin(0) }, Modifier.weight(1f))
            PinDropButton("SEC 1", (activeTrack?.sector1Lat ?: 0.0) != 0.0, { onDropTrackPin(1) }, Modifier.weight(1f))
            PinDropButton("SEC 2", (activeTrack?.sector2Lat ?: 0.0) != 0.0, { onDropTrackPin(2) }, Modifier.weight(1f))
            PinDropButton("FINISH", (activeTrack?.finishLat ?: 0.0) != 0.0, { onDropTrackPin(3) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PinDropButton(label: String, isSet: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (isSet) Color(0xFF064E3B) else Color(0xFF1E293B), contentColor = Color.White)
    ) { Text(if (isSet) "$label ✓" else "+ $label", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
}

@Composable
fun FirstLaunchVehicleOnboardingDialog(onCreateProfile: (String, String, Double, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var makeModel by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("1420") }
    var drivetrain by remember { mutableStateOf("RWD") }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("SET UP YOUR PRIMARY VEHICLE", fontWeight = FontWeight.Black, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Vehicle Nickname (e.g. E46)") }, singleLine = true)
                OutlinedTextField(value = makeModel, onValueChange = { makeModel = it }, label = { Text("Make & Model") }, singleLine = true)
                OutlinedTextField(value = weightText, onValueChange = { weightText = it }, label = { Text("Weight (kg)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("RWD", "AWD", "FWD").forEach { dt ->
                        Button(
                            onClick = { drivetrain = dt },
                            colors = ButtonDefaults.buttonColors(containerColor = if (drivetrain == dt) Color(0xFF00E676) else Color(0xFF1E293B), contentColor = if (drivetrain == dt) Color.Black else Color.White),
                            modifier = Modifier.weight(1f)
                        ) { Text(dt, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreateProfile(if (name.isBlank()) "E46" else name.trim(), if (makeModel.isBlank()) "BMW" else makeModel.trim(), weightText.toDoubleOrNull() ?: 1420.0, drivetrain) }) {
                Text("START TELEMETRY", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(title, color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun MetricPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SlipRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (highlight) Color(0xFF00E676) else Color(0xFFB0BEC5), fontSize = 12.sp, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace)
        Text(value, color = if (highlight) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

fun formatMsToSec(ms: Long): String {
    val safe = abs(ms)
    val sign = if (ms < 0) "-" else ""
    return String.format(Locale.US, "%s%d.%03ds", sign, safe / 1000, safe % 1000)
}

fun formatNullableMs(ms: Long?): String = if (ms != null) formatMsToSec(ms) else "--.---"
