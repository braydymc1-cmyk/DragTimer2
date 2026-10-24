package com.performance.gpstimer

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class AppScreen {
    HUB_HOME, ACTIVE_TIMER, DASHBOARDS, TRACK_MANAGER, RUN_LOGS, SETTINGS
}

class MainActivity : ComponentActivity() {

    private var telemetryService by mutableStateOf<GpsTelemetryService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GpsTelemetryService.LocalBinder
            telemetryService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            telemetryService = null
            isBound = false
        }
    }

    fun startAndBindTelemetryService() {
        if (!isBound) {
            val serviceIntent = Intent(this, GpsTelemetryService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        } else {
            telemetryService?.startRawGpsUpdates()
        }
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

            val colorScheme = when (settings.themeMode) {
                ThemeMode.OLED_DARK -> darkColorScheme(
                    background = Color(0xFF07090D),
                    surface = Color(0xFF12161F),
                    onSurface = Color.White,
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF00B0FF)
                )
                ThemeMode.SLATE_DARK -> darkColorScheme(
                    background = Color(0xFF111827),
                    surface = Color(0xFF1F2937),
                    onSurface = Color.White,
                    primary = Color(0xFF10B981),
                    secondary = Color(0xFF38BDF8)
                )
                ThemeMode.DAYLIGHT_LIGHT -> lightColorScheme(
                    background = Color(0xFFF1F5F9),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF0F172A),
                    primary = Color(0xFF059669),
                    secondary = Color(0xFF0284C7)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PerformanceTimerApp(
                        service = telemetryService,
                        settingsRepo = settingsRepo,
                        runDao = database.runDao(),
                        onPermissionGranted = { startAndBindTelemetryService() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val settings by settingsRepo.settings.collectAsState()
    val uiState by service?.uiState?.collectAsState() ?: remember { mutableStateOf(TelemetryUiState()) }
    val allRuns by runDao.getAllRuns().collectAsState(initial = emptyList())
    val allVehicles by runDao.getAllVehicles().collectAsState(initial = emptyList())
    val allTracks by runDao.getAllTracks().collectAsState(initial = emptyList())
    val allLaps by runDao.getAllLaps().collectAsState(initial = emptyList())

    var currentScreen by remember { mutableStateOf(AppScreen.HUB_HOME) }
    var fullScreenTreeMode by remember { mutableStateOf(false) }

    // Vehicle Photo Picker Launcher
    var photoTargetVehicle by remember { mutableStateOf<VehicleProfile?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && photoTargetVehicle != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val updated = photoTargetVehicle!!.copy(photoUri = uri.toString())
            scope.launch { runDao.insertVehicle(updated) }
        }
    }

    BackHandler(enabled = fullScreenTreeMode || currentScreen != AppScreen.HUB_HOME) {
        if (fullScreenTreeMode) {
            fullScreenTreeMode = false
        } else {
            service?.disarmRun()
            currentScreen = AppScreen.HUB_HOME
        }
    }

    LaunchedEffect(settings.orientationPref, currentScreen, uiState.mode) {
        val targetOrientation = when (settings.orientationPref) {
            OrientationPref.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            OrientationPref.ALWAYS_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationPref.ALWAYS_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK -> {
                if ((currentScreen == AppScreen.ACTIVE_TIMER &&
                            (uiState.mode == TestMode.DRAG_TREE || uiState.mode == TestMode.TRACK_CIRCUIT)) ||
                    currentScreen == AppScreen.DASHBOARDS
                ) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
        activity?.requestedOrientation = targetOrientation
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onPermissionGranted()
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val activeTrack = allTracks.find { it.id == settings.activeTrackId } ?: allTracks.firstOrNull()
    val activeVehicle = allVehicles.find { it.id == settings.activeVehicleId } ?: allVehicles.firstOrNull()

    LaunchedEffect(activeTrack) {
        service?.updateActiveTrack(activeTrack)
    }

    if (!settings.onboardingCompleted && allVehicles.isEmpty()) {
        FirstLaunchVehicleOnboardingDialog(
            onCreateProfile = { name, makeModel, weightKg, drivetrain ->
                scope.launch {
                    val newId = runDao.insertVehicle(
                        VehicleProfile(name = name, makeModel = makeModel, weightKg = weightKg, drivetrain = drivetrain)
                    )
                    settingsRepo.update { it.copy(onboardingCompleted = true, activeVehicleId = newId) }
                }
            }
        )
    }

    val dropPinAction: (Int) -> Unit = { pinType ->
        val lat = uiState.currentLat
        val lon = uiState.currentLon
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
            val pinName = when (pinType) {
                0 -> "START"
                1 -> "SECTOR 1"
                2 -> "SECTOR 2"
                else -> "FINISH"
            }
            Toast.makeText(context, "$pinName Pin Dropped (${String.format(Locale.US, "%.5f, %.5f", lat, lon)})", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        bottomBar = {
            if (!fullScreenTreeMode && !isLandscape) {
                MotorsportBottomNavBar(
                    currentScreen = currentScreen,
                    onNavigate = { currentScreen = it }
                )
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
            GpsTopTelemetryBar(
                uiState = uiState,
                currentScreen = currentScreen,
                showNavIconsInTopBar = isLandscape,
                onBack = {
                    if (fullScreenTreeMode) fullScreenTreeMode = false
                    else {
                        service?.disarmRun()
                        currentScreen = AppScreen.HUB_HOME
                    }
                },
                onNavigate = { currentScreen = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (currentScreen) {
                AppScreen.HUB_HOME -> {
                    ModeHubScreen(
                        settings = settings,
                        activeVehicle = activeVehicle,
                        weather = uiState.weather,
                        isLandscape = isLandscape,
                        onRefreshWeather = { service?.refreshWeather() },
                        onPickCarPhoto = {
                            if (activeVehicle != null) {
                                photoTargetVehicle = activeVehicle
                                photoPickerLauncher.launch(arrayOf("image/*"))
                            } else {
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
                        fullScreenTree = fullScreenTreeMode,
                        onToggleFullScreenTree = { fullScreenTreeMode = !fullScreenTreeMode },
                        onUpdateCustomSpeeds = { startKmh, endKmh ->
                            settingsRepo.update { it.copy(customStartSpeedKmh = startKmh, customEndSpeedKmh = endKmh) }
                        },
                        onArmToggle = {
                            if (uiState.status == RunStatus.IDLE || uiState.status == RunStatus.COMPLETED) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MotorsportDashboardView(
                            uiState = uiState,
                            settings = settings,
                            onSelectStyle = { style -> settingsRepo.update { it.copy(dashStyle = style) } },
                            onUpdateSlotMetric = { idx, metric ->
                                val mutable = settings.customDashSlots.toMutableList()
                                if (idx in mutable.indices) {
                                    mutable[idx] = metric
                                    settingsRepo.update { it.copy(customDashSlots = mutable) }
                                }
                            }
                        )
                    }
                }

                AppScreen.TRACK_MANAGER -> {
                    TrackManagerScreen(
                        tracks = allTracks,
                        laps = allLaps,
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
                                val newId = runDao.insertTrack(
                                    TrackLayout(name = name, startLat = uiState.currentLat, startLon = uiState.currentLon, isCircuit = true)
                                )
                                settingsRepo.update { it.copy(activeTrackId = newId) }
                                Toast.makeText(context, "Track '$name' created!", Toast.LENGTH_SHORT).show()
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
                        onAddVehicle = { v ->
                            scope.launch {
                                val id = runDao.insertVehicle(v)
                                settingsRepo.update { it.copy(activeVehicleId = id, onboardingCompleted = true) }
                            }
                        },
                        onPickVehiclePhoto = { v ->
                            photoTargetVehicle = v
                            photoPickerLauncher.launch(arrayOf("image/*"))
                        },
                        onBackupDatabase = {
                            scope.launch {
                                val json = ExportEngine.exportFullBackupJson(runDao)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_SUBJECT, "Apex Telemetry Backup JSON")
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                context.startActivity(Intent.createChooser(send, "Export Backup JSON"))
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

@Composable
fun MotorsportBottomNavBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF0D1118),
        tonalElevation = 4.dp,
        modifier = Modifier.height(62.dp)
    ) {
        val items = listOf(
            Triple(AppScreen.HUB_HOME, "RACE HUB", Icons.Default.Home),
            Triple(AppScreen.DASHBOARDS, "DASHES", Icons.Default.Speed),
            Triple(AppScreen.TRACK_MANAGER, "SAT TRACK", Icons.Default.Map),
            Triple(AppScreen.RUN_LOGS, "SLIPS", Icons.Default.Assessment),
            Triple(AppScreen.SETTINGS, "GARAGE", Icons.Default.Settings)
        )
        items.forEach { (screen, label, icon) ->
            val selected = currentScreen == screen || (screen == AppScreen.HUB_HOME && currentScreen == AppScreen.ACTIVE_TIMER)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(screen) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) },
                label = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF00E676),
                    selectedTextColor = Color(0xFF00E676),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color(0xFF1E293B)
                )
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (currentScreen != AppScreen.HUB_HOME) {
                IconButton(onClick = onBack, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00E676))
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(lockColor))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (uiState.externalGnssActive) "EXT GNSS ${String.format(Locale.US, "%.0fHz", uiState.currentHz)}"
                else "SATS ${uiState.satellitesUsed}/${uiState.satellitesVisible} • ±${String.format(Locale.US, "%.1fm", uiState.accuracyM)}",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D1118))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "DA: ${uiState.weather.densityAltitudeFt} ft",
                    color = Color(0xFF00B0FF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (showNavIconsInTopBar) {
                IconButton(onClick = { onNavigate(AppScreen.DASHBOARDS) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Speed, contentDescription = "Dashes", tint = Color.Gray)
                }
                IconButton(onClick = { onNavigate(AppScreen.TRACK_MANAGER) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Map, contentDescription = "Track", tint = Color.Gray)
                }
                IconButton(onClick = { onNavigate(AppScreen.RUN_LOGS) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Assessment, contentDescription = "Logs", tint = Color.Gray)
                }
                IconButton(onClick = { onNavigate(AppScreen.SETTINGS) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
                }
            }
        }
    }
}

/**
 * Compact Sleek Vehicle Header Card + Graphical Mode Grid Hub
 */
@Composable
fun ModeHubScreen(
    settings: AppSettings,
    activeVehicle: VehicleProfile?,
    weather: WeatherSnapshot,
    isLandscape: Boolean,
    onRefreshWeather: () -> Unit,
    onPickCarPhoto: () -> Unit,
    onSelectMode: (TestMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Sleek Fixed-Height Vehicle & Atmospheric Card (Never stretches vertically!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF141B26), Color(0xFF0F141D))
                    )
                )
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VehicleAvatarBadge(
                photoUri = activeVehicle?.photoUri ?: "",
                sizeDp = 46.dp,
                onClick = onPickCarPhoto
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activeVehicle?.name?.uppercase() ?: "TAP TO SET VEHICLE",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeVehicle?.drivetrain ?: "RWD",
                        color = Color(0xFF00B0FF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${UnitConverter.formatTemp(weather.tempC, settings.useMph)} • ${weather.pressureHpa.toInt()} hPa • DA ${weather.densityAltitudeFt} ft",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onRefreshWeather,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Weather DA",
                    tint = Color(0xFF00B0FF),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isLandscape) 3 else 2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(TestMode.entries) { mode ->
                val accent = when (mode) {
                    TestMode.DRAG_TREE -> Color(0xFFFFC400)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accent.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(mode.badge, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = mode.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mode.subtitle,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveTimerScreen(
    uiState: TelemetryUiState,
    settings: AppSettings,
    activeTrack: TrackLayout?,
    isLandscape: Boolean,
    fullScreenTree: Boolean,
    onToggleFullScreenTree: () -> Unit,
    onUpdateCustomSpeeds: (Double, Double) -> Unit,
    onArmToggle: () -> Unit,
    onDropTrackPin: (Int) -> Unit,
    onShareTimeslip: (RunRecord) -> Unit
) {
    val isArmedOrRunning = uiState.status != RunStatus.IDLE && uiState.status != RunStatus.COMPLETED
    val dispSpeed = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)

    if (fullScreenTree && uiState.mode == TestMode.DRAG_TREE) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NhraDragLightingTree(
                stageState = uiState.treeStage,
                treeType = settings.treeType,
                reactionMs = uiState.reactionTimeMs,
                redLight = uiState.redLightJump,
                compactLandscape = isLandscape,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("NHRA FULLSCREEN TREE", color = Color(0xFFFFC400), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("EXIT", color = Color(0xFF00B0FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onToggleFullScreenTree() })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(String.format(Locale.US, "%.1f %s", dispSpeed, speedUnit), color = MaterialTheme.colorScheme.onSurface, fontSize = 36.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text("ET: ${formatMsToSec(uiState.liveElapsedMs)}", color = Color(0xFF00E676), fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Button(
                    onClick = onArmToggle,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676), contentColor = Color.Black)
                ) {
                    Text(if (isArmedOrRunning) "ABORT / RESET TREE" else "STAGE & START TREE", fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E293B))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${uiState.mode.title} • ${uiState.status.label}",
                color = Color(0xFFFFC400),
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (uiState.mode == TestMode.DRAG_TREE) {
                Text(
                    text = "FULLSCREEN TREE",
                    color = Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onToggleFullScreenTree() }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.mode == TestMode.CUSTOM_SPEED) {
            InlineCustomSpeedSelector(settings = settings, onUpdateCustomSpeeds = onUpdateCustomSpeeds)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.mode == TestMode.TRACK_CIRCUIT) {
            // Live Satellite Map + GT3/Cockpit Telemetry + Sector Pin Controls
            SatelliteTrackMapView(
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                activeTrack = activeTrack,
                modifier = Modifier.fillMaxWidth().height(if (isLandscape) 190.dp else 230.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            TrackPinControlsCard(
                activeTrack = activeTrack,
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                onDropTrackPin = onDropTrackPin
            )
            Spacer(modifier = Modifier.height(8.dp))
            MotorsportDashboardView(
                uiState = uiState,
                settings = settings,
                onSelectStyle = {},
                onUpdateSlotMetric = { _, _ -> }
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.mode == TestMode.DRAG_TREE) {
                    NhraDragLightingTree(
                        stageState = uiState.treeStage,
                        treeType = settings.treeType,
                        reactionMs = uiState.reactionTimeMs,
                        redLight = uiState.redLightJump,
                        compactLandscape = isLandscape,
                        modifier = Modifier.width(132.dp)
                    )
                }
                PrimarySpeedAndTimerCard(
                    dispSpeed = dispSpeed,
                    speedUnit = speedUnit,
                    uiState = uiState,
                    useMph = settings.useMph,
                    compact = isLandscape,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            TimeSlipCard(
                mode = uiState.mode,
                run = uiState.latestRun,
                useMph = settings.useMph,
                onShareTimeslip = onShareTimeslip
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onArmToggle,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
                contentColor = Color.Black
            )
        ) {
            Text(
                text = if (isArmedOrRunning) "ABORT / DISARM" else "ARM TIMING SYSTEM",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun InlineCustomSpeedSelector(
    settings: AppSettings,
    onUpdateCustomSpeeds: (Double, Double) -> Unit
) {
    val unitLabel = UnitConverter.speedUnit(settings.useMph)
    val startDisp = UnitConverter.speed(settings.customStartSpeedKmh, settings.useMph)
    val endDisp = UnitConverter.speed(settings.customEndSpeedKmh, settings.useMph)

    var startText by remember(settings.customStartSpeedKmh, settings.useMph) {
        mutableStateOf(String.format(Locale.US, "%.0f", startDisp))
    }
    var endText by remember(settings.customEndSpeedKmh, settings.useMph) {
        mutableStateOf(String.format(Locale.US, "%.0f", endDisp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        Text("CUSTOM SPEED INTERVAL ($unitLabel)", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = {
                    startText = it
                    it.toDoubleOrNull()?.let { v ->
                        if (v >= 0.0) onUpdateCustomSpeeds(UnitConverter.toKmh(v, settings.useMph), settings.customEndSpeedKmh)
                    }
                },
                label = { Text("Start ($unitLabel)", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = endText,
                onValueChange = {
                    endText = it
                    it.toDoubleOrNull()?.let { v ->
                        if (v > 5.0) onUpdateCustomSpeeds(settings.customStartSpeedKmh, UnitConverter.toKmh(v, settings.useMph))
                    }
                },
                label = { Text("Target ($unitLabel)", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PrimarySpeedAndTimerCard(
    dispSpeed: Double,
    speedUnit: String,
    uiState: TelemetryUiState,
    useMph: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(if (compact) 12.dp else 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text("SPEED", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(Locale.US, "%.1f", dispSpeed),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = if (compact) 34.sp else 40.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(speedUnit, color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ELAPSED", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    text = formatMsToSec(uiState.liveElapsedMs),
                    color = Color(0xFF00E676),
                    fontSize = if (compact) 30.sp else 34.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF263238))
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricPill("DISTANCE", UnitConverter.formatDist(uiState.liveDistanceM, useMph))
            MetricPill("LONG G", String.format(Locale.US, "%.2f G", uiState.currentG))
            MetricPill("PEAK G", String.format(Locale.US, "%.2f G", uiState.peakG))
        }
    }
}

@Composable
private fun TrackPinControlsCard(
    activeTrack: TrackLayout?,
    currentLat: Double,
    currentLon: Double,
    onDropTrackPin: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Text(
            text = "SATELLITE SECTOR PINS — ${activeTrack?.name ?: "Custom Track"}",
            color = Color(0xFF00B0FF),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = String.format(Locale.US, "Live GPS Position: %.5f, %.5f", currentLat, currentLon),
            color = Color.Gray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))
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
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSet) Color(0xFF064E3B) else Color(0xFF1E293B),
            contentColor = Color.White
        )
    ) {
        Text(if (isSet) "$label ✓" else "+ $label", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun TimeSlipCard(
    mode: TestMode,
    run: RunRecord?,
    useMph: Boolean,
    onShareTimeslip: (RunRecord) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OFFICIAL DIGITAL TIMESLIP", color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (run != null) {
                TextButton(onClick = { onShareTimeslip(run) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share Slip", tint = Color(0xFF00E676))
                    Spacer(Modifier.width(4.dp))
                    Text("EXPORT SLIP", color = Color(0xFF00E676), fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF263238))
        Spacer(modifier = Modifier.height(6.dp))

        if (run?.reactionTimeMs != null || mode == TestMode.DRAG_TREE) {
            SlipRow("REACTION (R/T)", if (run?.reactionTimeMs != null) "${formatMsToSec(run.reactionTimeMs)} ${if (run.redLightJump) "(RED)" else "(GREEN)"}" else "--.---", highlight = run?.redLightJump == true)
        }
        if (mode == TestMode.BRAKE_100_0) {
            SlipRow("START SPEED", UnitConverter.formatSpeed(run?.brakingStartSpeedKmh ?: 100.0, useMph))
            SlipRow("STOP TIME", formatNullableMs(run?.primaryTimeMs), highlight = true)
            SlipRow("STOP DISTANCE", run?.brakingDistanceM?.let { UnitConverter.formatDist(it, useMph) } ?: "---", highlight = true)
        } else {
            SlipRow("60 FT", formatNullableMs(run?.time60ftMs))
            SlipRow("330 FT", formatNullableMs(run?.time330ftMs))
            SlipRow("1/8 MILE", if (run?.timeEighthMileMs != null) "${formatMsToSec(run.timeEighthMileMs)} @ ${UnitConverter.formatSpeed(run.speedEighthMileKmh ?: 0.0, useMph)}" else "--.---")
            SlipRow("1000 FT", formatNullableMs(run?.time1000ftMs))
            SlipRow("PRIMARY ET", if (run != null) "${formatMsToSec(run.primaryTimeMs)} @ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}" else "--.---", highlight = true)
            SlipRow(if (useMph) "0 - 60 MPH" else "0 - 100 KM/H", formatNullableMs(run?.time0to100Ms))
        }
    }
}

@Composable
fun TrackManagerScreen(
    tracks: List<TrackLayout>,
    laps: List<TrackLapRecord>,
    activeTrack: TrackLayout?,
    uiState: TelemetryUiState,
    onSelectTrack: (TrackLayout) -> Unit,
    onActivateTrackForEditing: (TrackLayout) -> Unit,
    onCreateTrack: (String) -> Unit,
    onDropPinOnActive: (Int) -> Unit,
    onDeleteTrack: (TrackLayout) -> Unit
) {
    var newTrackName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SatelliteTrackMapView(
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                activeTrack = activeTrack,
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )
        }
        item {
            TrackPinControlsCard(
                activeTrack = activeTrack,
                currentLat = uiState.currentLat,
                currentLon = uiState.currentLon,
                onDropTrackPin = onDropPinOnActive
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTrackName,
                    onValueChange = { newTrackName = it },
                    label = { Text("New Circuit Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val name = if (newTrackName.isBlank()) "Custom Circuit ${tracks.size + 1}" else newTrackName.trim()
                    onCreateTrack(name)
                    newTrackName = ""
                }) {
                    Text("ADD TRACK")
                }
            }
        }
        items(tracks, key = { it.id }) { track ->
            val isCurrent = track.id == activeTrack?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(if (isCurrent) 1.5.dp else 0.dp, if (isCurrent) Color(0xFF00E676) else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { onActivateTrackForEditing(track) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isCurrent) "${track.name} ✓" else track.name, color = if (isCurrent) Color(0xFF00E676) else Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Best Lap: ${formatNullableMs(track.bestLapMs)}", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Button(onClick = { onSelectTrack(track) }) { Text("RACE") }
                IconButton(onClick = { onDeleteTrack(track) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                }
            }
        }
    }
}

/**
 * Graphical Timeslip & Export Studio Screen
 */
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // Graphical Export Studio Action Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color(0xFF141B26), Color(0xFF1E293B)))
                    )
                    .padding(14.dp)
            ) {
                Text("TIMESLIP & TELEMETRY EXPORT STUDIO", color = Color(0xFF00E676), fontWeight = FontWeight.Black, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { primaryRun?.let { onSavePng(it) } },
                        enabled = primaryRun != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SAVE SLIP PNG", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = { primaryRun?.let { onShareSlip(it) } },
                        enabled = primaryRun != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF), contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SHARE PDF SLIP", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Button(
                        onClick = onExportCsv,
                        enabled = runs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC400), contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("EXPORT CSV", fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        if (primaryRun != null) {
            item {
                RunComparisonGraph(primaryRun = primaryRun, comparisonRun = compareRun, useMph = useMph)
            }
        }

        items(runs, key = { it.id }) { run ->
            val isPrimary = run.id == primaryRun?.id
            val isCompare = run.id == compareRun?.id

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = if (isPrimary || isCompare) 1.5.dp else 0.dp,
                        color = if (isPrimary) Color(0xFF00E676) else if (isCompare) Color(0xFF00B0FF) else Color.Transparent,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { selectedPrimaryId = run.id }
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("PASS #${run.id} • ${run.testMode} • ${run.vehicleName}", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(run.timestampEpochMs)), color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Row {
                        TextButton(onClick = { selectedCompareId = if (selectedCompareId == run.id) null else run.id }) {
                            Text(if (isCompare) "OVERLAY ON" else "COMPARE", color = Color(0xFF00B0FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { onDeleteRun(run) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(formatMsToSec(run.primaryTimeMs), color = MaterialTheme.colorScheme.onSurface, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text("@ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}", color = Color(0xFF00B0FF), fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetricPill("60 FT", formatNullableMs(run.time60ftMs))
                    MetricPill("1/8 MILE", formatNullableMs(run.timeEighthMileMs))
                    MetricPill("0-100", formatNullableMs(run.time0to100Ms))
                    MetricPill("PEAK G", String.format(Locale.US, "%.2fG", run.peakG))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectObd: (String, String) -> Unit,
    onSelectGnss: (GnssSourceType, String, String) -> Unit,
    onAddVehicle: (VehicleProfile) -> Unit,
    onPickVehiclePhoto: (VehicleProfile) -> Unit,
    onBackupDatabase: () -> Unit,
    onRestoreDatabase: (String) -> Unit
) {
    val context = LocalContext.current
    var showPreviousObd by remember { mutableStateOf(false) }
    var obdDropdownExpanded by remember { mutableStateOf(false) }
    var showPreviousGnss by remember { mutableStateOf(false) }
    var gnssDropdownExpanded by remember { mutableStateOf(false) }

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
    var newVehWeight by remember { mutableStateOf("1420") }
    var restoreJsonInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsCard("GARAGE — VEHICLE PROFILES & CAR PHOTOS") {
                Text("Tap any car photo icon to upload a picture from your gallery:", color = Color.Gray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(8.dp))
                vehicles.forEach { v ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0D1118))
                            .clickable { onUpdateSettings { it.copy(activeVehicleId = v.id) } }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            VehicleAvatarBadge(
                                photoUri = v.photoUri,
                                sizeDp = 44.dp,
                                onClick = { onPickVehiclePhoto(v) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(v.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${v.makeModel} • ${v.weightKg.toInt()} kg • Tap photo to change", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                        Text(
                            text = if (settings.activeVehicleId == v.id) "ACTIVE ✓" else "USE",
                            color = if (settings.activeVehicleId == v.id) Color(0xFF00E676) else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newVehName, onValueChange = { newVehName = it }, label = { Text("Vehicle Name") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = newVehWeight, onValueChange = { newVehWeight = it }, label = { Text("kg") }, singleLine = true, modifier = Modifier.width(76.dp))
                    Button(onClick = {
                        if (newVehName.isNotBlank()) {
                            onAddVehicle(VehicleProfile(name = newVehName.trim(), makeModel = "Custom", weightKg = newVehWeight.toDoubleOrNull() ?: 1420.0, drivetrain = "RWD"))
                            newVehName = ""
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

                Spacer(modifier = Modifier.height(10.dp))
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
                    Spacer(modifier = Modifier.height(6.dp))
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
            SettingsCard("DISPLAY, UNITS & DRAG TREE") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("UNITS (KPH / MPH)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = { onUpdateSettings { it.copy(useMph = !it.useMph) } }) {
                        Text(if (settings.useMph) "MPH / FT" else "KM/H / M")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("THEME", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    Button(onClick = {
                        val next = ThemeMode.entries[(settings.themeMode.ordinal + 1) % ThemeMode.entries.size]
                        onUpdateSettings { it.copy(themeMode = next) }
                    }) { Text(settings.themeMode.label) }
                }
                Spacer(modifier = Modifier.height(6.dp))
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
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = restoreJsonInput, onValueChange = { restoreJsonInput = it }, label = { Text("Paste Backup JSON Here") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { if (restoreJsonInput.isNotBlank()) { onRestoreDatabase(restoreJsonInput); restoreJsonInput = "" } },
                    enabled = restoreJsonInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("RESTORE RUNS") }
            }
        }
    }
}

@Composable
fun FirstLaunchVehicleOnboardingDialog(
    onCreateProfile: (name: String, makeModel: String, weightKg: Double, drivetrain: String) -> Unit
) {
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
            Button(onClick = {
                onCreateProfile(
                    if (name.isBlank()) "E46" else name.trim(),
                    if (makeModel.isBlank()) "BMW" else makeModel.trim(),
                    weightText.toDoubleOrNull() ?: 1420.0,
                    drivetrain
                )
            }) { Text("START TELEMETRY", fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(title, color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
fun MetricPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Text(text = value, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
    val safe = kotlin.math.abs(ms)
    val seconds = safe / 1000
    val millis = safe % 1000
    val sign = if (ms < 0) "-" else ""
    return String.format(Locale.US, "%s%d.%03ds", sign, seconds, millis)
}

fun formatNullableMs(ms: Long?): String = if (ms != null) formatMsToSec(ms) else "--.---"
