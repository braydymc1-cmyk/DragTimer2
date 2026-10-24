package com.performance.gpstimer

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class AppScreen {
    HUB_HOME, ACTIVE_TIMER, TRACK_MANAGER, RUN_LOGS, SETTINGS
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

    // Hardware / Gesture Back Button Handler
    BackHandler(enabled = fullScreenTreeMode || currentScreen != AppScreen.HUB_HOME) {
        if (fullScreenTreeMode) {
  fullScreenTreeMode = false
        } else {
  service?.disarmRun()
  currentScreen = AppScreen.HUB_HOME
        }
    }

    // Orientation Manager
    LaunchedEffect(settings.orientationPref, currentScreen, uiState.mode) {
        val targetOrientation = when (settings.orientationPref) {
  OrientationPref.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
  OrientationPref.ALWAYS_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  OrientationPref.ALWAYS_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
  OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK -> {
      if (currentScreen == AppScreen.ACTIVE_TIMER &&
          (uiState.mode == TestMode.DRAG_TREE || uiState.mode == TestMode.TRACK_CIRCUIT)
      ) {
          ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      } else {
          ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
  }
        }
        activity?.requestedOrientation = targetOrientation
    }

    // Runtime Permissions
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

    LaunchedEffect(activeTrack) {
        service?.updateActiveTrack(activeTrack)
    }

    // First-Time Vehicle Setup Onboarding Prompt
    if (!settings.onboardingCompleted && allVehicles.isEmpty()) {
        FirstLaunchVehicleOnboardingDialog(
  useMph = settings.useMph,
  onCreateProfile = { name, makeModel, weightKg, drivetrain ->
      scope.launch {
          val newId = runDao.insertVehicle(
              VehicleProfile(
                  name = name,
                  makeModel = makeModel,
                  weightKg = weightKg,
                  drivetrain = drivetrain
              )
          )
          settingsRepo.update {
              it.copy(onboardingCompleted = true, activeVehicleId = newId)
          }
      }
  }
        )
    }

    Column(
        modifier = Modifier
  .fillMaxSize()
  .padding(horizontal = 10.dp, vertical = if (isLandscape) 6.dp else 10.dp)
    ) {
        GpsStatusBar(
  uiState = uiState,
  currentScreen = currentScreen,
  onBack = {
      if (fullScreenTreeMode) fullScreenTreeMode = false
      else {
          service?.disarmRun()
          currentScreen = AppScreen.HUB_HOME
      }
  },
  onNavigate = { currentScreen = it }
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 6.dp else 10.dp))

        val dropPinAction: (Int) -> Unit = { pinType ->
  val lat = if (uiState.currentLat != 0.0) uiState.currentLat else -31.67432
  val lon = if (uiState.currentLon != 0.0) uiState.currentLon else 115.78685
  scope.launch {
      val baseTrack = activeTrack ?: TrackLayout(
          name = "Custom Track 1",
          startLat = lat,
          startLon = lon
      )
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
      Toast.makeText(
          context,
          "$pinName Pin Saved (${String.format(Locale.US, "%.5f, %.5f", lat, lon)})",
          Toast.LENGTH_SHORT
      ).show()
  }
        }

        when (currentScreen) {
  AppScreen.HUB_HOME -> {
      ModeHubScreen(
          settings = settings,
          vehicles = allVehicles,
          weather = uiState.weather,
          isLandscape = isLandscape,
          onRefreshWeather = { service?.refreshWeather() },
          onSelectMode = { mode ->
              service?.setTestMode(mode, activeTrack)
              currentScreen = AppScreen.ACTIVE_TIMER
          },
          onOpenTrackManager = { currentScreen = AppScreen.TRACK_MANAGER },
          onOpenLogs = { currentScreen = AppScreen.RUN_LOGS },
          onOpenSettings = { currentScreen = AppScreen.SETTINGS }
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
              settingsRepo.update {
                  it.copy(customStartSpeedKmh = startKmh, customEndSpeedKmh = endKmh)
              }
          },
          onArmToggle = {
              if (uiState.status == RunStatus.IDLE || uiState.status == RunStatus.COMPLETED) {
                  service?.armSystem(activeTrack)
              } else {
                  service?.disarmRun()
              }
          },
          onDropTrackPin = dropPinAction,
          onCycleGaugeLayout = {
              val next = GaugeLayoutStyle.entries[(settings.gaugeLayout.ordinal + 1) % GaugeLayoutStyle.entries.size]
              settingsRepo.update { it.copy(gaugeLayout = next) }
          },
          onShareTimeslip = { run ->
              ExportEngine.shareTimeslipPdf(context, run, settings.useMph)
          }
      )
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
          onActivateTrackForEditing = { t ->
              settingsRepo.update { it.copy(activeTrackId = t.id) }
          },
          onCreateTrack = { name ->
              val lat = if (uiState.currentLat != 0.0) uiState.currentLat else -31.67432
              val lon = if (uiState.currentLon != 0.0) uiState.currentLon else 115.78685
              scope.launch {
                  val newId = runDao.insertTrack(
                      TrackLayout(name = name, startLat = lat, startLon = lon, isCircuit = true)
                  )
                  settingsRepo.update { it.copy(activeTrackId = newId) }
                  Toast.makeText(context, "Track '$name' created & selected!", Toast.LENGTH_SHORT).show()
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

@Composable
fun FirstLaunchVehicleOnboardingDialog(
    useMph: Boolean,
    onCreateProfile: (name: String, makeModel: String, weightKg: Double, drivetrain: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var makeModel by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("1420") }
    var drivetrain by remember { mutableStateOf("RWD") }

    AlertDialog(
        onDismissRequest = {},
        title = {
  Text(
      "WELCOME — SET UP YOUR VEHICLE",
      fontWeight = FontWeight.Black,
      fontSize = 16.sp,
      fontFamily = FontFamily.Monospace
  )
        },
        text = {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          "Create your primary vehicle profile to log accurate timeslips, G-curves, and track records:",
          fontSize = 12.sp,
          color = Color.Gray
      )
      OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Profile Nickname (e.g. Track E46)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
          value = makeModel,
          onValueChange = { makeModel = it },
          label = { Text("Make & Model (e.g. BMW 325i / 330i)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
          value = weightText,
          onValueChange = { weightText = it },
          label = { Text("Curb Weight in kg (with driver)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("RWD", "AWD", "FWD").forEach { dt ->
              Button(
                  onClick = { drivetrain = dt },
                  colors = ButtonDefaults.buttonColors(
                      containerColor = if (drivetrain == dt) Color(0xFF00E676) else Color(0xFF1E293B),
                      contentColor = if (drivetrain == dt) Color.Black else Color.White
                  ),
                  modifier = Modifier.weight(1f)
              ) {
                  Text(dt, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
          }
      }
  }
        },
        confirmButton = {
  Button(
      onClick = {
          val finalName = if (name.isBlank()) "My Performance Car" else name.trim()
          val finalModel = if (makeModel.isBlank()) "Custom Build" else makeModel.trim()
          val wKg = weightText.toDoubleOrNull() ?: 1420.0
          onCreateProfile(finalName, finalModel, wKg, drivetrain)
      }
  ) {
      Text("SAVE VEHICLE & START", fontWeight = FontWeight.Bold)
  }
        }
    )
}

@Composable
fun GpsStatusBar(
    uiState: TelemetryUiState,
    currentScreen: AppScreen,
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
  .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
  if (currentScreen != AppScreen.HUB_HOME) {
      IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF00E676))
      }
      Spacer(modifier = Modifier.width(4.dp))
  } else {
      Icon(Icons.Default.Home, contentDescription = "Home", tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))
      Spacer(modifier = Modifier.width(6.dp))
  }
  Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(lockColor))
  Spacer(modifier = Modifier.width(6.dp))
  Text(
      text = if (uiState.externalGnssActive) "EXT GNSS ${String.format(Locale.US, "%.0fHz", uiState.currentHz)}"
      else "SATS ${uiState.satellitesUsed}/${uiState.satellitesVisible} • ±${String.format(Locale.US, "%.1fm", uiState.accuracyM)}",
      color = MaterialTheme.colorScheme.onSurface,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
  )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
  Text("DA:${uiState.weather.densityAltitudeFt}ft", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
  IconButton(onClick = { onNavigate(AppScreen.TRACK_MANAGER) }, modifier = Modifier.size(26.dp)) {
      Icon(Icons.Default.Place, contentDescription = "Tracks", tint = if (currentScreen == AppScreen.TRACK_MANAGER) Color(0xFF00E676) else Color.Gray)
  }
  IconButton(onClick = { onNavigate(AppScreen.RUN_LOGS) }, modifier = Modifier.size(26.dp)) {
      Icon(Icons.Default.List, contentDescription = "Logs", tint = if (currentScreen == AppScreen.RUN_LOGS) Color(0xFF00E676) else Color.Gray)
  }
  IconButton(onClick = { onNavigate(AppScreen.SETTINGS) }, modifier = Modifier.size(26.dp)) {
      Icon(Icons.Default.Settings, contentDescription = "Settings", tint = if (currentScreen == AppScreen.SETTINGS) Color(0xFF00E676) else Color.Gray)
  }
        }
    }
}

@Composable
fun ModeHubScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    weather: WeatherSnapshot,
    isLandscape: Boolean,
    onRefreshWeather: () -> Unit,
    onSelectMode: (TestMode) -> Unit,
    onOpenTrackManager: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeVehicle = vehicles.find { it.id == settings.activeVehicleId } ?: vehicles.firstOrNull()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
  modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(MaterialTheme.colorScheme.surface)
      .padding(horizontal = 12.dp, vertical = 8.dp),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically
        ) {
  Column {
      Text(
          text = "VEHICLE: ${activeVehicle?.name ?: "Tap Settings to Add"}",
          color = Color(0xFF00E676),
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace
      )
      Text(
          text = "${UnitConverter.formatTemp(weather.tempC, settings.useMph)} • ${weather.pressureHpa.toInt()} hPa • Density Alt: ${weather.densityAltitudeFt} ft",
          color = Color.Gray,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace
      )
  }
  TextButton(onClick = onRefreshWeather) {
      Text("REFRESH DA", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
  }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
              .clip(RoundedCornerShape(12.dp))
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
              .clickable { onSelectMode(mode) }
              .padding(12.dp),
          verticalArrangement = Arrangement.SpaceBetween
      ) {
          Text(
              text = mode.title,
              color = accent,
              fontSize = 14.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = mode.subtitle,
              color = Color.Gray,
              fontSize = 11.sp,
              lineHeight = 14.sp
          )
      }
  }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  Button(
      onClick = onOpenTrackManager,
      modifier = Modifier.weight(1f),
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
  ) {
      Text("TRACK PINS", color = Color(0xFF00B0FF), fontSize = 11.sp)
  }
  Button(
      onClick = onOpenLogs,
      modifier = Modifier.weight(1f),
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
  ) {
      Text("SLIPS & GRAPHS", color = Color(0xFF00E676), fontSize = 11.sp)
  }
  Button(
      onClick = onOpenSettings,
      modifier = Modifier.weight(1f),
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
  ) {
      Text("SETTINGS / OBD", color = Color(0xFFFFC400), fontSize = 11.sp)
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
    onCycleGaugeLayout: () -> Unit,
    onShareTimeslip: (RunRecord) -> Unit
) {
    val isArmedOrRunning = uiState.status != RunStatus.IDLE && uiState.status != RunStatus.COMPLETED
    val dispSpeed = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)

    // Full-Screen Drag Tree Mode (Adaptive for both Landscape and Portrait)
    if (fullScreenTree && uiState.mode == TestMode.DRAG_TREE) {
        if (isLandscape) {
  Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      NhraDragLightingTree(
          stageState = uiState.treeStage,
          treeType = settings.treeType,
          reactionMs = uiState.reactionTimeMs,
          redLight = uiState.redLightJump,
          compactLandscape = true,
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
                  Text("FULLSCREEN DRAG TREE", color = Color(0xFFFFC400), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                  Text(
                      "EXIT",
                      color = Color(0xFF00B0FF),
                      fontSize = 12.sp,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.clickable { onToggleFullScreenTree() }
                  )
              }
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                  text = String.format(Locale.US, "%.1f %s", dispSpeed, speedUnit),
                  color = MaterialTheme.colorScheme.onSurface,
                  fontSize = 36.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.Monospace
              )
              Text(
                  text = "ET: ${formatMsToSec(uiState.liveElapsedMs)}",
                  color = Color(0xFF00E676),
                  fontSize = 28.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.Monospace
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                  text = "60': ${formatNullableMs(uiState.latestRun?.time60ftMs)}  |  1/4: ${formatNullableMs(uiState.latestRun?.primaryTimeMs)}",
                  color = Color.Gray,
                  fontSize = 12.sp,
                  fontFamily = FontFamily.Monospace
              )
          }

          Button(
              onClick = onArmToggle,
              modifier = Modifier.fillMaxWidth().height(50.dp),
              colors = ButtonDefaults.buttonColors(
                  containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
                  contentColor = Color.Black
              )
          ) {
              Text(
                  if (isArmedOrRunning) "ABORT / RESET TREE" else "STAGE & START TREE",
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.Monospace
              )
          }
      }
  }
        } else {
  Column(modifier = Modifier.fillMaxSize()) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text(
              text = String.format(Locale.US, "%.1f %s  |  %s", dispSpeed, speedUnit, formatMsToSec(uiState.liveElapsedMs)),
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 18.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
          TextButton(onClick = onToggleFullScreenTree) {
              Text("EXIT FULLSCREEN", color = Color(0xFF00B0FF))
          }
      }
      NhraDragLightingTree(
          stageState = uiState.treeStage,
          treeType = settings.treeType,
          reactionMs = uiState.reactionTimeMs,
          redLight = uiState.redLightJump,
          modifier = Modifier.fillMaxWidth().weight(1f)
      )
      Spacer(modifier = Modifier.height(8.dp))
      Button(
          onClick = onArmToggle,
          modifier = Modifier.fillMaxWidth().height(50.dp),
          colors = ButtonDefaults.buttonColors(
              containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
              contentColor = Color.Black
          )
      ) {
          Text(
              if (isArmedOrRunning) "ABORT / RESET TREE" else "STAGE & START LIGHT TREE",
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
      }
  }
        }
        return
    }

    // Standard Active Timer Screen — Responsive Landscape vs Portrait
    if (isLandscape) {
        Row(
  modifier = Modifier.fillMaxSize(),
  horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
  // Left Pane: Speedometer / Tree / Gauges + Arm Button
  Column(
      modifier = Modifier
          .weight(1.05f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.SpaceBetween
  ) {
      Column {
          StatusBannerBar(
              uiState = uiState,
              settings = settings,
              onToggleFullScreenTree = onToggleFullScreenTree,
              onCycleGaugeLayout = onCycleGaugeLayout
          )
          Spacer(modifier = Modifier.height(6.dp))

          if (uiState.mode == TestMode.CUSTOM_SPEED) {
              InlineCustomSpeedSelector(
                  settings = settings,
                  onUpdateCustomSpeeds = onUpdateCustomSpeeds
              )
              Spacer(modifier = Modifier.height(6.dp))
          }

          if (uiState.mode == TestMode.TRACK_CIRCUIT) {
              TrackCockpitGauges(uiState = uiState, settings = settings)
          } else {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  if (uiState.mode == TestMode.DRAG_TREE) {
                      NhraDragLightingTree(
                          stageState = uiState.treeStage,
                          treeType = settings.treeType,
                          reactionMs = uiState.reactionTimeMs,
                          redLight = uiState.redLightJump,
                          compactLandscape = true,
                          modifier = Modifier.width(125.dp)
                      )
                  }
                  PrimarySpeedAndTimerCard(
                      dispSpeed = dispSpeed,
                      speedUnit = speedUnit,
                      uiState = uiState,
                      useMph = settings.useMph,
                      compact = true,
                      modifier = Modifier.weight(1f)
                  )
              }
          }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Button(
          onClick = onArmToggle,
          modifier = Modifier.fillMaxWidth().height(46.dp),
          shape = RoundedCornerShape(10.dp),
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

  // Right Pane: Timeslip or Track Pin Controls
  Column(
      modifier = Modifier
          .weight(0.95f)
          .fillMaxHeight()
          .verticalScroll(rememberScrollState())
  ) {
      if (uiState.mode == TestMode.TRACK_CIRCUIT) {
          TrackPinControlsCard(
              activeTrack = activeTrack,
              currentLat = uiState.currentLat,
              currentLon = uiState.currentLon,
              onDropTrackPin = onDropTrackPin
          )
      } else {
          TimeSlipCard(
              mode = uiState.mode,
              run = uiState.latestRun,
              useMph = settings.useMph,
              onShareTimeslip = onShareTimeslip
          )
      }
  }
        }
    } else {
        // Portrait Mode Layout
        Column(
  modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
        ) {
  StatusBannerBar(
      uiState = uiState,
      settings = settings,
      onToggleFullScreenTree = onToggleFullScreenTree,
      onCycleGaugeLayout = onCycleGaugeLayout
  )

  Spacer(modifier = Modifier.height(8.dp))

  if (uiState.mode == TestMode.CUSTOM_SPEED) {
      InlineCustomSpeedSelector(
          settings = settings,
          onUpdateCustomSpeeds = onUpdateCustomSpeeds
      )
      Spacer(modifier = Modifier.height(8.dp))
  }

  if (uiState.mode == TestMode.TRACK_CIRCUIT) {
      TrackCockpitGauges(uiState = uiState, settings = settings)
      Spacer(modifier = Modifier.height(8.dp))
      TrackPinControlsCard(
          activeTrack = activeTrack,
          currentLat = uiState.currentLat,
          currentLon = uiState.currentLon,
          onDropTrackPin = onDropTrackPin
      )
  } else {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
          if (uiState.mode == TestMode.DRAG_TREE) {
              NhraDragLightingTree(
                  stageState = uiState.treeStage,
                  treeType = settings.treeType,
                  reactionMs = uiState.reactionTimeMs,
                  redLight = uiState.redLightJump,
                  modifier = Modifier.width(136.dp)
              )
          }
          PrimarySpeedAndTimerCard(
              dispSpeed = dispSpeed,
              speedUnit = speedUnit,
              uiState = uiState,
              useMph = settings.useMph,
              compact = false,
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
      modifier = Modifier.fillMaxWidth().height(52.dp),
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(
          containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
          contentColor = Color.Black
      )
  ) {
      Text(
          text = if (isArmedOrRunning) "ABORT / DISARM" else "ARM TIMING SYSTEM",
          fontSize = 16.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
      )
  }
        }
    }
}

@Composable
private fun StatusBannerBar(
    uiState: TelemetryUiState,
    settings: AppSettings,
    onToggleFullScreenTree: () -> Unit,
    onCycleGaugeLayout: () -> Unit
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
  fontFamily = FontFamily.Monospace
        )
        if (uiState.mode == TestMode.DRAG_TREE) {
  Text(
      text = "FULLSCREEN TREE",
      color = Color(0xFF00E676),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.clickable { onToggleFullScreenTree() }
  )
        } else if (uiState.mode == TestMode.TRACK_CIRCUIT) {
  Text(
      text = "GAUGES: ${settings.gaugeLayout.label}",
      color = Color(0xFF00B0FF),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.clickable { onCycleGaugeLayout() }
  )
        }
    }
}

/**
 * Inline Custom Speed Selector right inside the Custom Speed category
 */
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
        Text(
  text = "CUSTOM SPEED INTERVAL ($unitLabel)",
  color = Color(0xFF00B0FF),
  fontSize = 11.sp,
  fontWeight = FontWeight.Bold,
  fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
  modifier = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.spacedBy(8.dp),
  verticalAlignment = Alignment.CenterVertically
        ) {
  OutlinedTextField(
      value = startText,
      onValueChange = {
          startText = it
          val v = it.toDoubleOrNull()
          if (v != null && v >= 0.0) {
              onUpdateCustomSpeeds(UnitConverter.toKmh(v, settings.useMph), settings.customEndSpeedKmh)
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
          val v = it.toDoubleOrNull()
          if (v != null && v > 5.0) {
              onUpdateCustomSpeeds(settings.customStartSpeedKmh, UnitConverter.toKmh(v, settings.useMph))
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

        Row(
  modifier = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
  text = "ACTIVE TRACK: ${activeTrack?.name ?: "Tap any pin button to auto-create"}",
  color = Color(0xFF00B0FF),
  fontSize = 12.sp,
  fontFamily = FontFamily.Monospace,
  fontWeight = FontWeight.Bold
        )
        Text(
  text = String.format(Locale.US, "Live GPS: %.5f, %.5f", currentLat, currentLon),
  color = Color.Gray,
  fontSize = 11.sp,
  fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  PinDropButton(
      label = "START PIN",
      isSet = (activeTrack?.startLat ?: 0.0) != 0.0,
      onClick = { onDropTrackPin(0) },
      modifier = Modifier.weight(1f)
  )
  PinDropButton(
      label = "SECTOR 1",
      isSet = (activeTrack?.sector1Lat ?: 0.0) != 0.0,
      onClick = { onDropTrackPin(1) },
      modifier = Modifier.weight(1f)
  )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
  PinDropButton(
      label = "SECTOR 2",
      isSet = (activeTrack?.sector2Lat ?: 0.0) != 0.0,
      onClick = { onDropTrackPin(2) },
      modifier = Modifier.weight(1f)
  )
  PinDropButton(
      label = "FINISH PIN",
      isSet = (activeTrack?.finishLat ?: 0.0) != 0.0,
      onClick = { onDropTrackPin(3) },
      modifier = Modifier.weight(1f)
  )
        }
    }
}

@Composable
private fun PinDropButton(
    label: String,
    isSet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
  containerColor = if (isSet) Color(0xFF064E3B) else Color(0xFF1E293B),
  contentColor = Color.White
        )
    ) {
        Text(
  text = if (isSet) "$label ✓" else "SET $label",
  fontSize = 11.sp,
  fontWeight = FontWeight.Bold,
  fontFamily = FontFamily.Monospace
        )
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
  Text(
      text = "DIGITAL TIMESLIP",
      color = Color(0xFF00B0FF),
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace
  )
  if (run != null) {
      TextButton(onClick = { onShareTimeslip(run) }) {
          Icon(Icons.Default.Share, contentDescription = "Share Slip", tint = Color(0xFF00E676))
          Spacer(Modifier.width(4.dp))
          Text("SAVE PNG / SHARE", color = Color(0xFF00E676), fontSize = 11.sp)
      }
  }
        }

        HorizontalDivider(color = Color(0xFF263238))
        Spacer(modifier = Modifier.height(6.dp))

        if (run?.reactionTimeMs != null || mode == TestMode.DRAG_TREE) {
  SlipRow(
      "REACTION (R/T)",
      if (run?.reactionTimeMs != null) {
          "${formatMsToSec(run.reactionTimeMs)} ${if (run.redLightJump) "(RED)" else "(GREEN)"}"
      } else "--.---",
      highlight = run?.redLightJump == true
  )
        }

        if (mode == TestMode.BRAKE_100_0) {
  SlipRow("START SPEED", UnitConverter.formatSpeed(run?.brakingStartSpeedKmh ?: 100.0, useMph))
  SlipRow("STOP TIME", formatNullableMs(run?.primaryTimeMs), highlight = true)
  SlipRow("STOP DISTANCE", run?.brakingDistanceM?.let { UnitConverter.formatDist(it, useMph) } ?: "---", highlight = true)
  SlipRow("PEAK / AVG G", String.format(Locale.US, "%.2fG / %.2fG", run?.peakG ?: 0.0, run?.avgBrakingG ?: 0.0))
        } else {
  SlipRow("60 FT", formatNullableMs(run?.time60ftMs))
  SlipRow("330 FT", formatNullableMs(run?.time330ftMs))
  SlipRow(
      "1/8 MILE",
      if (run?.timeEighthMileMs != null) {
          "${formatMsToSec(run.timeEighthMileMs)} @ ${UnitConverter.formatSpeed(run.speedEighthMileKmh ?: 0.0, useMph)}"
      } else "--.---"
  )
  SlipRow("1000 FT", formatNullableMs(run?.time1000ftMs))
  SlipRow(
      "PRIMARY ET",
      if (run != null) {
          "${formatMsToSec(run.primaryTimeMs)} @ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}"
      } else "--.---",
      highlight = true
  )
  if (mode == TestMode.HALF_MILE || run?.timeHalfMileMs != null) {
      SlipRow(
          "1/2 MILE",
          if (run?.timeHalfMileMs != null) {
              "${formatMsToSec(run.timeHalfMileMs)} @ ${UnitConverter.formatSpeed(run.speedHalfMileKmh ?: 0.0, useMph)}"
          } else "--.---",
          highlight = true
      )
  }
  SlipRow(if (useMph) "0 - 60 MPH" else "0 - 100 KM/H", formatNullableMs(run?.time0to100Ms))
  if (mode == TestMode.ROLL_100_200 || mode == TestMode.CUSTOM_SPEED) {
      SlipRow("FIRST HALF SPLIT", formatNullableMs(run?.time100to150Ms))
      SlipRow("SECOND HALF SPLIT", formatNullableMs(run?.time150to200Ms))
  }
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
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(14.dp)
  ) {
      Text(
          text = "1. CREATE A NEW CUSTOM TRACK",
          color = Color(0xFF00E676),
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace
      )
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
              value = newTrackName,
              onValueChange = { newTrackName = it },
              label = { Text("Track Name (e.g. Local Circuit)") },
              singleLine = true,
              modifier = Modifier.weight(1f)
          )
          Button(
              onClick = {
                  val name = if (newTrackName.isBlank()) "Custom Track ${tracks.size + 1}" else newTrackName.trim()
                  onCreateTrack(name)
                  newTrackName = ""
              }
          ) {
              Text("CREATE")
          }
      }
  }
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
  Text("SAVED TRACK LAYOUTS (${tracks.size})", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
          Text(
              text = if (isCurrent) "${track.name} (SELECTED)" else track.name,
              color = if (isCurrent) Color(0xFF00E676) else Color(0xFF00B0FF),
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
          )
          Text(
              text = String.format(
                  Locale.US,
                  "Start: %.4f, %.4f | S1:%s S2:%s | Best: %s",
                  track.startLat,
                  track.startLon,
                  if (track.sector1Lat != 0.0) "✓" else "-",
                  if (track.sector2Lat != 0.0) "✓" else "-",
                  formatNullableMs(track.bestLapMs)
              ),
              color = Color.Gray,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
          )
      }
      Button(onClick = { onSelectTrack(track) }) {
          Text("DRIVE")
      }
      IconButton(onClick = { onDeleteTrack(track) }) {
          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
      }
  }
        }

        if (laps.isNotEmpty()) {
  item {
      Text("RECENT LAPS", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
  }
  items(laps.take(15), key = { it.id }) { lap ->
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surface)
              .padding(12.dp),
          horizontalArrangement = Arrangement.SpaceBetween
      ) {
          Column {
              Text("${lap.trackName} • Lap #${lap.lapNumber}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Text(
                  "S1: ${formatNullableMs(lap.sector1Ms)} | S2: ${formatNullableMs(lap.sector2Ms)} | S3: ${formatNullableMs(lap.sector3Ms)}",
                  color = Color.Gray,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
              )
          }
          Text(
              formatMsToSec(lap.lapTimeMs),
              color = Color(0xFF00E676),
              fontSize = 18.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
      }
  }
        }
    }
}

@Composable
fun RunHistoryScreen(
    runs: List<RunRecord>,
    useMph: Boolean,
    onDeleteRun: (RunRecord) -> Unit,
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
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
      Text(
          text = "SAVED PASSES & OVERLAYS (${runs.size})",
          color = MaterialTheme.colorScheme.onSurface,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          fontFamily = FontFamily.Monospace
      )
      Button(
          onClick = onExportCsv,
          enabled = runs.isNotEmpty(),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
      ) {
          Icon(Icons.Default.Share, contentDescription = "Export CSV", tint = Color(0xFF00E676))
          Spacer(Modifier.width(6.dp))
          Text("EXPORT CSV", color = Color(0xFF00E676), fontSize = 11.sp)
      }
  }
        }

        if (primaryRun != null) {
  item {
      RunComparisonGraph(primaryRun = primaryRun, comparisonRun = compareRun, useMph = useMph)
  }
  item {
      RunReplayPlayerCard(run = primaryRun, useMph = useMph)
  }
        }

        items(runs, key = { it.id }) { run ->
  val isPrimary = run.id == primaryRun?.id
  val isCompare = run.id == compareRun?.id

  Column(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .border(
              width = if (isPrimary || isCompare) 1.5.dp else 0.dp,
              color = if (isPrimary) Color(0xFF00E676) else if (isCompare) Color(0xFF00B0FF) else Color.Transparent,
              shape = RoundedCornerShape(12.dp)
          )
          .padding(12.dp)
  ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Column {
              Text(
                  text = "#${run.id} • ${run.testMode} (${run.vehicleName})",
                  color = Color(0xFF00E676),
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp,
                  fontFamily = FontFamily.Monospace
              )
              Text(
                  text = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date(run.timestampEpochMs)),
                  color = Color.Gray,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
              )
          }
          Row {
              TextButton(onClick = { selectedPrimaryId = run.id }) {
                  Text("GRAPH", color = Color(0xFF00E676), fontSize = 11.sp)
              }
              TextButton(onClick = { selectedCompareId = if (selectedCompareId == run.id) null else run.id }) {
                  Text("OVERLAY", color = Color(0xFF00B0FF), fontSize = 11.sp)
              }
              IconButton(onClick = { onShareSlip(run) }) {
                  Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFFFFC400))
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
          Text(
              text = formatMsToSec(run.primaryTimeMs),
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 26.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
          Text(
              text = if (run.testMode == TestMode.BRAKE_100_0.name) {
                  UnitConverter.formatDist(run.brakingDistanceM ?: run.totalDistanceM, useMph)
              } else {
                  "@ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}"
              },
              color = Color(0xFF00B0FF),
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace
          )
      }

      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = "60': ${formatNullableMs(run.time60ftMs)} | 1/8: ${formatNullableMs(run.timeEighthMileMs)} | R/T: ${formatNullableMs(run.reactionTimeMs)} | DA: ${run.densityAltitudeFt ?: 0}ft",
          color = Color.Gray,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace
      )
  }
        }
    }
}

/**
 * Settings Screen with Dropdowns for OBD-II and USB Cable / Bluetooth GNSS
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectObd: (String, String) -> Unit,
    onSelectGnss: (GnssSourceType, String, String) -> Unit,
    onAddVehicle: (VehicleProfile) -> Unit,
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

    // Filter OBD dropdown options: active/connected only vs previously connected/paired
    val obdOptions = remember(allBtDevices, showPreviousObd, settings.historyObdDevices) {
        if (showPreviousObd) {
  allBtDevices
        } else {
  allBtDevices.filter { it.isCurrentlyConnected }
        }
    }

    // Filter GNSS dropdown options (USB Cable + Bluetooth)
    val gnssOptions = remember(usbGnssDevices, allBtDevices, showPreviousGnss, settings.gnssSourceType) {
        when (settings.gnssSourceType) {
  GnssSourceType.INTERNAL_GPS -> emptyList()
  GnssSourceType.USB_CABLE_GNSS -> {
      if (showPreviousGnss) {
          val hist = settings.historyGnssDevices.mapNotNull { entry ->
              val parts = entry.split("|")
              if (parts.size == 2 && parts[1].startsWith("USB:")) {
                  HardwareDeviceOption(parts[1], "${parts[0]} (Saved USB)", false, true)
              } else null
          }
          (usbGnssDevices + hist).distinctBy { it.id }
      } else {
          usbGnssDevices
      }
  }
  GnssSourceType.BLUETOOTH_GNSS -> {
      if (showPreviousGnss) allBtDevices else allBtDevices.filter { it.isCurrentlyConnected }
  }
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
  SettingsCard("DISPLAY, UNITS & ORIENTATION") {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("UNITS (KPH / MPH)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = { onUpdateSettings { it.copy(useMph = !it.useMph) } }) {
              Text(if (settings.useMph) "IMPERIAL (MPH / FT)" else "METRIC (KM/H / M)")
          }
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("THEME MODE", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = {
              val next = ThemeMode.entries[(settings.themeMode.ordinal + 1) % ThemeMode.entries.size]
              onUpdateSettings { it.copy(themeMode = next) }
          }) {
              Text(settings.themeMode.label, fontSize = 12.sp)
          }
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("ORIENTATION", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = {
              val next = OrientationPref.entries[(settings.orientationPref.ordinal + 1) % OrientationPref.entries.size]
              onUpdateSettings { it.copy(orientationPref = next) }
          }) {
              Text(settings.orientationPref.label, fontSize = 11.sp)
          }
      }
  }
        }

        item {
  SettingsCard("OBD-II ADAPTER CONNECTION") {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("Show Previously Connected OBD Devices", color = Color.Gray, fontSize = 12.sp)
          Switch(checked = showPreviousObd, onCheckedChange = { showPreviousObd = it })
      }
      Spacer(modifier = Modifier.height(6.dp))
      Box(modifier = Modifier.fillMaxWidth()) {
          OutlinedButton(
              onClick = { obdDropdownExpanded = true },
              modifier = Modifier.fillMaxWidth()
          ) {
              Text(
                  text = if (settings.btObdName.isNotBlank()) "OBD: ${settings.btObdName}"
                  else "Select OBD-II Device (${obdOptions.size} available) ▼",
                  color = Color(0xFF00E676)
              )
          }
          DropdownMenu(
              expanded = obdDropdownExpanded,
              onDismissRequest = { obdDropdownExpanded = false }
          ) {
              DropdownMenuItem(
                  text = { Text("Disconnect / None") },
                  onClick = {
                      onSelectObd("", "")
                      obdDropdownExpanded = false
                  }
              )
              if (obdOptions.isEmpty()) {
                  DropdownMenuItem(
                      text = { Text("No active OBD found (Toggle 'Show Previously Connected')") },
                      onClick = { obdDropdownExpanded = false }
                  )
              } else {
                  obdOptions.forEach { dev ->
                      DropdownMenuItem(
                          text = { Text("${dev.name} (${dev.id})") },
                          onClick = {
                              onSelectObd(dev.id, dev.name)
                              obdDropdownExpanded = false
                          }
                      )
                  }
              }
          }
      }
  }
        }

        item {
  SettingsCard("GNSS RECEIVER (USB CABLE OR BLUETOOTH)") {
      Text("Select GNSS Input Source:", color = Color.Gray, fontSize = 11.sp)
      Spacer(modifier = Modifier.height(6.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          GnssSourceType.entries.forEach { src ->
              val selected = settings.gnssSourceType == src
              Button(
                  onClick = { onSelectGnss(src, settings.gnssDeviceId, settings.gnssDeviceName) },
                  colors = ButtonDefaults.buttonColors(
                      containerColor = if (selected) Color(0xFF00B0FF) else Color(0xFF1E293B),
                      contentColor = if (selected) Color.Black else Color.White
                  ),
                  modifier = Modifier.weight(1f)
              ) {
                  Text(
                      when (src) {
                          GnssSourceType.INTERNAL_GPS -> "PHONE GPS"
                          GnssSourceType.USB_CABLE_GNSS -> "USB CABLE"
                          GnssSourceType.BLUETOOTH_GNSS -> "BLUETOOTH"
                      },
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold
                  )
              }
          }
      }

      if (settings.gnssSourceType != GnssSourceType.INTERNAL_GPS) {
          Spacer(modifier = Modifier.height(8.dp))
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
          ) {
              Text("Show Previously Connected GNSS Devices", color = Color.Gray, fontSize = 12.sp)
              Switch(checked = showPreviousGnss, onCheckedChange = { showPreviousGnss = it })
          }
          Spacer(modifier = Modifier.height(6.dp))
          Box(modifier = Modifier.fillMaxWidth()) {
              OutlinedButton(
                  onClick = { gnssDropdownExpanded = true },
                  modifier = Modifier.fillMaxWidth()
              ) {
                  Text(
                      text = if (settings.gnssDeviceName.isNotBlank()) "GNSS: ${settings.gnssDeviceName}"
                      else "Select ${settings.gnssSourceType.label} (${gnssOptions.size} found) ▼",
                      color = Color(0xFF00B0FF)
                  )
              }
              DropdownMenu(
                  expanded = gnssDropdownExpanded,
                  onDismissRequest = { gnssDropdownExpanded = false }
              ) {
                  if (gnssOptions.isEmpty()) {
                      DropdownMenuItem(
                          text = {
                              Text(
                                  if (settings.gnssSourceType == GnssSourceType.USB_CABLE_GNSS)
                                      "Plug in USB GNSS receiver (or toggle 'Show Previously Connected')"
                                  else "No active BT GNSS found (toggle 'Show Previously Connected')"
                              )
                          },
                          onClick = { gnssDropdownExpanded = false }
                      )
                  } else {
                      gnssOptions.forEach { dev ->
                          DropdownMenuItem(
                              text = { Text(dev.name) },
                              onClick = {
                                  onSelectGnss(settings.gnssSourceType, dev.id, dev.name)
                                  gnssDropdownExpanded = false
                              }
                          )
                      }
                  }
              }
          }
      }
  }
        }

        item {
  SettingsCard("DRAG STRIP TREE & ROLLOUT") {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("DRAG TREE TYPE", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = {
              val next = TreeType.entries[(settings.treeType.ordinal + 1) % TreeType.entries.size]
              onUpdateSettings { it.copy(treeType = next) }
          }) {
              Text(settings.treeType.label)
          }
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("1-FOOT DRAG ROLLOUT (0.30m)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Switch(
              checked = settings.rolloutEnabled,
              onCheckedChange = { onUpdateSettings { s -> s.copy(rolloutEnabled = it) } }
          )
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("100Hz IMU + GPS LAUNCH FUSION", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Switch(
              checked = settings.imuFusionEnabled,
              onCheckedChange = { onUpdateSettings { s -> s.copy(imuFusionEnabled = it) } }
          )
      }
  }
        }

        item {
  SettingsCard("VEHICLE PROFILES & BACKUP / RESTORE") {
      vehicles.forEach { v ->
          Row(
              modifier = Modifier
                  .fillMaxWidth()
                  .clickable { onUpdateSettings { it.copy(activeVehicleId = v.id) } }
                  .padding(vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween
          ) {
              Text("${v.name} • ${v.makeModel} (${v.weightKg.toInt()} kg)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
              Text(
                  if (settings.activeVehicleId == v.id) "ACTIVE ✓" else "SELECT",
                  color = if (settings.activeVehicleId == v.id) Color(0xFF00E676) else Color.Gray,
                  fontWeight = FontWeight.Bold
              )
          }
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
              value = newVehName,
              onValueChange = { newVehName = it },
              label = { Text("New Vehicle Name") },
              singleLine = true,
              modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
              value = newVehWeight,
              onValueChange = { newVehWeight = it },
              label = { Text("kg") },
              singleLine = true,
              modifier = Modifier.width(80.dp)
          )
          Button(onClick = {
              if (newVehName.isNotBlank()) {
                  onAddVehicle(
                      VehicleProfile(
                          name = newVehName.trim(),
                          makeModel = "Custom",
                          weightKg = newVehWeight.toDoubleOrNull() ?: 1420.0,
                          drivetrain = "RWD"
                      )
                  )
                  newVehName = ""
              }
          }) {
              Text("ADD")
          }
      }
      Spacer(modifier = Modifier.height(10.dp))
      Button(onClick = onBackupDatabase, modifier = Modifier.fillMaxWidth()) {
          Text("EXPORT FULL JSON BACKUP")
      }
      Spacer(modifier = Modifier.height(6.dp))
      OutlinedTextField(
          value = restoreJsonInput,
          onValueChange = { restoreJsonInput = it },
          label = { Text("Paste Backup JSON Here to Restore") },
          modifier = Modifier.fillMaxWidth()
      )
      Spacer(modifier = Modifier.height(6.dp))
      Button(
          onClick = {
              if (restoreJsonInput.isNotBlank()) {
                  onRestoreDatabase(restoreJsonInput)
                  restoreJsonInput = ""
              }
          },
          enabled = restoreJsonInput.isNotBlank(),
          modifier = Modifier.fillMaxWidth()
      ) {
          Text("RESTORE RUNS FROM BACKUP JSON")
      }
  }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(12.dp))
  .background(MaterialTheme.colorScheme.surface)
  .padding(14.dp)
    ) {
        Text(
  text = title,
  color = Color(0xFF00B0FF),
  fontWeight = FontWeight.Bold,
  fontSize = 12.sp,
  fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
fun MetricPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
  text = value,
  color = MaterialTheme.colorScheme.onSurface,
  fontSize = 13.sp,
  fontWeight = FontWeight.Bold,
  fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun SlipRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
  .fillMaxWidth()
  .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
  text = label,
  color = if (highlight) Color(0xFF00E676) else Color(0xFFB0BEC5),
  fontSize = if (highlight) 13.sp else 12.sp,
  fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
  fontFamily = FontFamily.Monospace
        )
        Text(
  text = value,
  color = if (highlight) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurface,
  fontSize = if (highlight) 14.sp else 12.sp,
  fontWeight = FontWeight.Bold,
  fontFamily = FontFamily.Monospace
        )
    }
}

fun formatMsToSec(ms: Long): String {
    val safe = kotlin.math.abs(ms)
    val seconds = safe / 1000
    val millis = safe % 1000
    val sign = if (ms < 0) "-" else ""
    return String.format(Locale.US, "%s%d.%03ds", sign, seconds, millis)
}

fun formatNullableMs(ms: Long?): String {
    return if (ms != null) formatMsToSec(ms) else "--.---"
}
