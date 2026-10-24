package com.performance.gpstimer

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
          primary = Color(0xFF00E676),
          secondary = Color(0xFF00B0FF)
      )
      ThemeMode.SLATE_DARK -> darkColorScheme(
          background = Color(0xFF111827),
          surface = Color(0xFF1F2937),
          primary = Color(0xFF10B981),
          secondary = Color(0xFF38BDF8)
      )
      ThemeMode.DAYLIGHT_LIGHT -> lightColorScheme(
          background = Color(0xFFF1F5F9),
          surface = Color(0xFFFFFFFF),
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

    val settings by settingsRepo.settings.collectAsState()
    val uiState by service?.uiState?.collectAsState() ?: remember { mutableStateOf(TelemetryUiState()) }
    val allRuns by runDao.getAllRuns().collectAsState(initial = emptyList())
    val allVehicles by runDao.getAllVehicles().collectAsState(initial = emptyList())
    val allTracks by runDao.getAllTracks().collectAsState(initial = emptyList())
    val allLaps by runDao.getAllLaps().collectAsState(initial = emptyList())

    var currentScreen by remember { mutableStateOf(AppScreen.HUB_HOME) }
    var fullScreenTreeMode by remember { mutableStateOf(false) }

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

    Column(
        modifier = Modifier
  .fillMaxSize()
  .padding(12.dp)
    ) {
        GpsStatusBar(
  uiState = uiState,
  settings = settings,
  currentScreen = currentScreen,
  onNavigate = { currentScreen = it }
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (currentScreen) {
  AppScreen.HUB_HOME -> {
      ModeHubScreen(
          settings = settings,
          vehicles = allVehicles,
          weather = uiState.weather,
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
          fullScreenTree = fullScreenTreeMode,
          onToggleFullScreenTree = { fullScreenTreeMode = !fullScreenTreeMode },
          onArmToggle = {
              if (uiState.status == RunStatus.IDLE || uiState.status == RunStatus.COMPLETED) {
                  service?.armSystem(activeTrack)
              } else {
                  service?.disarmRun()
              }
          },
          onDropTrackPin = { pinType ->
              if (activeTrack != null && uiState.currentLat != 0.0) {
                  val updated = when (pinType) {
                      0 -> activeTrack.copy(startLat = uiState.currentLat, startLon = uiState.currentLon)
                      1 -> activeTrack.copy(sector1Lat = uiState.currentLat, sector1Lon = uiState.currentLon)
                      2 -> activeTrack.copy(sector2Lat = uiState.currentLat, sector2Lon = uiState.currentLon)
                      else -> activeTrack.copy(finishLat = uiState.currentLat, finishLon = uiState.currentLon)
                  }
                  scope.launch { runDao.insertTrack(updated) }
                  Toast.makeText(context, "GPS Pin Saved!", Toast.LENGTH_SHORT).show()
              }
          },
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
          uiState = uiState,
          settings = settings,
          onSelectTrack = { t ->
              settingsRepo.update { it.copy(activeTrackId = t.id) }
              service?.setTestMode(TestMode.TRACK_CIRCUIT, t)
              currentScreen = AppScreen.ACTIVE_TIMER
          },
          onSaveTrack = { t -> scope.launch { runDao.insertTrack(t) } },
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
          onConnectBluetooth = { obdMac, gnssMac ->
              settingsRepo.update { it.copy(btObdAddress = obdMac, btGnssAddress = gnssMac) }
              service?.connectBluetoothDevices(obdMac, gnssMac)
          },
          onAddVehicle = { v -> scope.launch { runDao.insertVehicle(v) } },
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
fun GpsStatusBar(
    uiState: TelemetryUiState,
    settings: AppSettings,
    currentScreen: AppScreen,
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
  .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
  verticalAlignment = Alignment.CenterVertically,
  modifier = Modifier.clickable { onNavigate(AppScreen.HUB_HOME) }
        ) {
  Icon(
      imageVector = Icons.Default.Home,
      contentDescription = "Home Hub",
      tint = if (currentScreen == AppScreen.HUB_HOME) Color(0xFF00E676) else Color.Gray,
      modifier = Modifier.size(20.dp)
  )
  Spacer(modifier = Modifier.width(8.dp))
  Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(lockColor))
  Spacer(modifier = Modifier.width(6.dp))
  Text(
      text = if (uiState.externalGnssActive) "EXT GNSS ${String.format(Locale.US, "%.0fHz", uiState.currentHz)}"
      else "SATS ${uiState.satellitesUsed}/${uiState.satellitesVisible} (${String.format(Locale.US, "%.0fHz", uiState.currentHz)})",
      color = MaterialTheme.colorScheme.onSurface,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
  )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
  Text(
      text = "DA: ${uiState.weather.densityAltitudeFt}ft",
      color = Color(0xFF00B0FF),
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace
  )
  IconButton(
      onClick = { onNavigate(AppScreen.TRACK_MANAGER) },
      modifier = Modifier.size(26.dp)
  ) {
      Icon(
          Icons.Default.Place,
          contentDescription = "Tracks",
          tint = if (currentScreen == AppScreen.TRACK_MANAGER) Color(0xFF00E676) else Color.Gray
      )
  }
  IconButton(
      onClick = { onNavigate(AppScreen.RUN_LOGS) },
      modifier = Modifier.size(26.dp)
  ) {
      Icon(
          Icons.Default.List,
          contentDescription = "Logs",
          tint = if (currentScreen == AppScreen.RUN_LOGS) Color(0xFF00E676) else Color.Gray
      )
  }
  IconButton(
      onClick = { onNavigate(AppScreen.SETTINGS) },
      modifier = Modifier.size(26.dp)
  ) {
      Icon(
          Icons.Default.Settings,
          contentDescription = "Settings",
          tint = if (currentScreen == AppScreen.SETTINGS) Color(0xFF00E676) else Color.Gray
      )
  }
        }
    }
}

@Composable
fun ModeHubScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    weather: WeatherSnapshot,
    onRefreshWeather: () -> Unit,
    onSelectMode: (TestMode) -> Unit,
    onOpenTrackManager: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeVehicle = vehicles.find { it.id == settings.activeVehicleId } ?: vehicles.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
  Row(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
      Column {
          Text(
              text = "ACTIVE VEHICLE: ${activeVehicle?.name ?: "Default"}",
              color = Color(0xFF00E676),
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace
          )
          Text(
              text = "${UnitConverter.formatTemp(weather.tempC, settings.useMph)} • ${weather.pressureHpa.toInt()} hPa • DA: ${weather.densityAltitudeFt} ft",
              color = Color.Gray,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
          )
      }
      TextButton(onClick = onRefreshWeather) {
          Text("UPDATE DA", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
      }
  }
        }

        item {
  Text(
      text = "SELECT TELEMETRY MODE",
      color = Color.Gray,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Bold
  )
        }

        items(TestMode.entries) { mode ->
  val accent = when (mode) {
      TestMode.DRAG_TREE -> Color(0xFFFFC400)
      TestMode.BRAKE_100_0 -> Color(0xFFFF5252)
      TestMode.TRACK_CIRCUIT -> Color(0xFF00B0FF)
      else -> Color(0xFF00E676)
  }
  Row(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
          .clickable { onSelectMode(mode) }
          .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
      Column(modifier = Modifier.weight(1f)) {
          Text(
              text = mode.title,
              color = accent,
              fontSize = 16.sp,
              fontWeight = FontWeight.Black,
              fontFamily = FontFamily.Monospace
          )
          Spacer(modifier = Modifier.height(3.dp))
          Text(
              text = mode.subtitle,
              color = Color.Gray,
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
          )
      }
      Text("GO →", color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
  }
        }

        item {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
      Button(
          onClick = onOpenTrackManager,
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
      ) {
          Text("TRACK PIN BUILDER", color = Color(0xFF00B0FF), fontSize = 11.sp)
      }
      Button(
          onClick = onOpenLogs,
          modifier = Modifier.weight(1f),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
      ) {
          Text("TIMESLIPS & GRAPHS", color = Color(0xFF00E676), fontSize = 11.sp)
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
}

@Composable
fun ActiveTimerScreen(
    uiState: TelemetryUiState,
    settings: AppSettings,
    activeTrack: TrackLayout?,
    fullScreenTree: Boolean,
    onToggleFullScreenTree: () -> Unit,
    onArmToggle: () -> Unit,
    onDropTrackPin: (Int) -> Unit,
    onCycleGaugeLayout: () -> Unit,
    onShareTimeslip: (RunRecord) -> Unit
) {
    val isArmedOrRunning = uiState.status != RunStatus.IDLE && uiState.status != RunStatus.COMPLETED
    val dispSpeed = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)

    if (fullScreenTree && uiState.mode == TestMode.DRAG_TREE) {
        Column(modifier = Modifier.fillMaxSize()) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
      Text(
          text = String.format(Locale.US, "%.1f %s  |  %s", dispSpeed, speedUnit, formatMsToSec(uiState.liveElapsedMs)),
          color = Color.White,
          fontSize = 20.sp,
          fontWeight = FontWeight.Black,
          fontFamily = FontFamily.Monospace
      )
      TextButton(onClick = onToggleFullScreenTree) {
          Text("EXIT FULLSCREEN TREE", color = Color(0xFF00B0FF))
      }
  }
  NhraDragLightingTree(
      stageState = uiState.treeStage,
      treeType = settings.treeType,
      reactionMs = uiState.reactionTimeMs,
      redLight = uiState.redLightJump,
      modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
  )
  Spacer(modifier = Modifier.height(8.dp))
  Button(
      onClick = onArmToggle,
      modifier = Modifier.fillMaxWidth().height(52.dp),
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
      .padding(horizontal = 12.dp, vertical = 8.dp),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically
        ) {
  Text(
      text = "${uiState.mode.title} • ${uiState.status.label}",
      color = Color(0xFFFFC400),
      fontWeight = FontWeight.Black,
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace
  )
  if (uiState.mode == TestMode.DRAG_TREE) {
      TextButton(onClick = onToggleFullScreenTree) {
          Text("FULLSCREEN TREE", color = Color(0xFF00E676), fontSize = 11.sp)
      }
  } else if (uiState.mode == TestMode.TRACK_CIRCUIT) {
      TextButton(onClick = onCycleGaugeLayout) {
          Text("LAYOUT: ${settings.gaugeLayout.name.take(10)}", color = Color(0xFF00B0FF), fontSize = 11.sp)
      }
  }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (uiState.mode == TestMode.TRACK_CIRCUIT) {
  TrackCockpitGauges(uiState = uiState, settings = settings)
  Spacer(modifier = Modifier.height(10.dp))

  Column(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(12.dp)
  ) {
      Text(
          text = "TRACK: ${activeTrack?.name ?: "Custom"} — DROP GPS SECTOR PINS AT CURRENT POSITION",
          color = Color(0xFF00B0FF),
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
          OutlinedButton(onClick = { onDropTrackPin(0) }, modifier = Modifier.weight(1f)) {
              Text("SET START", fontSize = 10.sp)
          }
          OutlinedButton(onClick = { onDropTrackPin(1) }, modifier = Modifier.weight(1f)) {
              Text("SET SEC 1", fontSize = 10.sp)
          }
          OutlinedButton(onClick = { onDropTrackPin(2) }, modifier = Modifier.weight(1f)) {
              Text("SET SEC 2", fontSize = 10.sp)
          }
          OutlinedButton(onClick = { onDropTrackPin(3) }, modifier = Modifier.weight(1f)) {
              Text("SET FINISH", fontSize = 10.sp)
          }
      }
  }
        } else {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
      if (uiState.mode == TestMode.DRAG_TREE) {
          NhraDragLightingTree(
              stageState = uiState.treeStage,
              treeType = settings.treeType,
              reactionMs = uiState.reactionTimeMs,
              redLight = uiState.redLightJump,
              modifier = Modifier.width(150.dp)
          )
      }

      Column(
          modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(14.dp))
              .background(MaterialTheme.colorScheme.surface)
              .padding(16.dp)
      ) {
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.Bottom
          ) {
              Column {
                  Text("SPEED", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                  Row(verticalAlignment = Alignment.Bottom) {
                      Text(
                          text = String.format(Locale.US, "%.1f", dispSpeed),
                          color = MaterialTheme.colorScheme.onSurface,
                          fontSize = 44.sp,
                          fontWeight = FontWeight.Black,
                          fontFamily = FontFamily.Monospace
                      )
                      Spacer(modifier = Modifier.width(4.dp))
                      Text(speedUnit, color = Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                  }
              }

              Column(horizontalAlignment = Alignment.End) {
                  Text("ELAPSED", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                  Text(
                      text = formatMsToSec(uiState.liveElapsedMs),
                      color = Color(0xFF00E676),
                      fontSize = 38.sp,
                      fontWeight = FontWeight.Black,
                      fontFamily = FontFamily.Monospace
                  )
              }
          }

          Spacer(modifier = Modifier.height(10.dp))
          HorizontalDivider(color = Color(0xFF263238))
          Spacer(modifier = Modifier.height(10.dp))

          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
          ) {
              MetricPill("DISTANCE", UnitConverter.formatDist(uiState.liveDistanceM, settings.useMph))
              MetricPill("LONG G", String.format(Locale.US, "%.2f G", uiState.currentG))
              MetricPill("PEAK G", String.format(Locale.US, "%.2f G", uiState.peakG))
          }
      }
  }

  Spacer(modifier = Modifier.height(12.dp))

  TimeSlipCard(
      mode = uiState.mode,
      run = uiState.latestRun,
      useMph = settings.useMph,
      onShareTimeslip = onShareTimeslip
  )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
  onClick = onArmToggle,
  modifier = Modifier
      .fillMaxWidth()
      .height(54.dp),
  shape = RoundedCornerShape(12.dp),
  colors = ButtonDefaults.buttonColors(
      containerColor = if (isArmedOrRunning) Color(0xFFFF5252) else Color(0xFF00E676),
      contentColor = Color.Black
  )
        ) {
  Text(
      text = if (isArmedOrRunning) "ABORT / DISARM" else "ARM TIMING SYSTEM",
      fontSize = 17.sp,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.Monospace
  )
        }
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
  .padding(16.dp)
    ) {
        Row(
  modifier = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically
        ) {
  Text(
      text = "OFFICIAL DIGITAL TIMESLIP",
      color = Color(0xFF00B0FF),
      fontWeight = FontWeight.Bold,
      fontSize = 13.sp,
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
        Spacer(modifier = Modifier.height(8.dp))

        if (run?.reactionTimeMs != null || mode == TestMode.DRAG_TREE) {
  SlipRow(
      "REACTION TIME (R/T)",
      if (run?.reactionTimeMs != null) {
          "${formatMsToSec(run.reactionTimeMs)} ${if (run.redLightJump) "(RED JUMP)" else "(GREEN)"}"
      } else "--.---",
      highlight = run?.redLightJump == true
  )
        }

        if (mode == TestMode.BRAKE_100_0) {
  SlipRow("BRAKE START SPEED", UnitConverter.formatSpeed(run?.brakingStartSpeedKmh ?: 100.0, useMph))
  SlipRow("STOPPING TIME", formatNullableMs(run?.primaryTimeMs), highlight = true)
  SlipRow(
      "STOPPING DISTANCE",
      run?.brakingDistanceM?.let { UnitConverter.formatDist(it, useMph) } ?: "---",
      highlight = true
  )
  SlipRow("PEAK DECEL / AVG G", String.format(Locale.US, "%.2fG / %.2fG", run?.peakG ?: 0.0, run?.avgBrakingG ?: 0.0))
        } else {
  SlipRow("60 FT (18.29m)", formatNullableMs(run?.time60ftMs))
  SlipRow("330 FT (100.58m)", formatNullableMs(run?.time330ftMs))
  SlipRow(
      "1/8 MILE (201.17m)",
      if (run?.timeEighthMileMs != null) {
          "${formatMsToSec(run.timeEighthMileMs)} @ ${
              UnitConverter.formatSpeed(run.speedEighthMileKmh ?: 0.0, useMph)
          }"
      } else "--.---"
  )
  SlipRow("1000 FT (304.80m)", formatNullableMs(run?.time1000ftMs))
  SlipRow(
      "1/4 MILE (402.34m)",
      if (run != null) {
          "${formatMsToSec(run.primaryTimeMs)} @ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}"
      } else "--.---",
      highlight = true
  )
  if (mode == TestMode.HALF_MILE || run?.timeHalfMileMs != null) {
      SlipRow(
          "1/2 MILE (804.67m)",
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
    uiState: TelemetryUiState,
    settings: AppSettings,
    onSelectTrack: (TrackLayout) -> Unit,
    onSaveTrack: (TrackLayout) -> Unit,
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
          text = "CREATE NEW TRACK LAYOUT AT CURRENT GPS LOCATION",
          color = Color(0xFF00E676),
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace
      )
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedTextField(
          value = newTrackName,
          onValueChange = { newTrackName = it },
          label = { Text("Track / Circuit Name") },
          modifier = Modifier.fillMaxWidth()
      )
      Spacer(modifier = Modifier.height(8.dp))
      Button(
          onClick = {
              if (newTrackName.isNotBlank()) {
                  onSaveTrack(
                      TrackLayout(
                          name = newTrackName,
                          startLat = uiState.currentLat,
                          startLon = uiState.currentLon,
                          isCircuit = true
                      )
                  )
                  newTrackName = ""
              }
          },
          modifier = Modifier.fillMaxWidth()
      ) {
          Text("CREATE TRACK & DROP START PIN HERE")
      }
  }
        }

        item {
  Text("SAVED TRACKS (${tracks.size})", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        items(tracks, key = { it.id }) { track ->
  Row(
      modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surface)
          .padding(14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
  ) {
      Column(modifier = Modifier.weight(1f)) {
          Text(track.name, color = Color(0xFF00B0FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Text(
              text = String.format(Locale.US, "Start: %.5f, %.5f | Best: %s", track.startLat, track.startLon, formatNullableMs(track.bestLapMs)),
              color = Color.Gray,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace
          )
      }
      Button(onClick = { onSelectTrack(track) }) {
          Text("LOAD")
      }
      IconButton(onClick = { onDeleteTrack(track) }) {
          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
      }
  }
        }

        if (laps.isNotEmpty()) {
  item {
      Text("RECENT RECORDED LAPS", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
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
              Text("${lap.trackName} • Lap #${lap.lapNumber}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
              fontSize = 20.sp,
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
          text = "LOGBOOK, OVERLAYS & REPLAY (${runs.size})",
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
          Text("REFINED CSV", color = Color(0xFF00E676), fontSize = 11.sp)
      }
  }
        }

        if (primaryRun != null) {
  item {
      RunComparisonGraph(
          primaryRun = primaryRun,
          comparisonRun = compareRun,
          useMph = useMph
      )
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
          .padding(14.dp)
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
              TextButton(onClick = {
                  selectedCompareId = if (selectedCompareId == run.id) null else run.id
              }) {
                  Text("OVERLAY", color = Color(0xFF00B0FF), fontSize = 11.sp)
              }
              IconButton(onClick = { onShareSlip(run) }) {
                  Icon(Icons.Default.Share, contentDescription = "Share Timeslip", tint = Color(0xFFFFC400))
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
              fontSize = 28.sp,
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

      Spacer(modifier = Modifier.height(6.dp))
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

@Composable
fun SettingsScreen(
    settings: AppSettings,
    vehicles: List<VehicleProfile>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onConnectBluetooth: (String, String) -> Unit,
    onAddVehicle: (VehicleProfile) -> Unit,
    onBackupDatabase: () -> Unit,
    onRestoreDatabase: (String) -> Unit
) {
    val context = LocalContext.current
    val pairedDevices = remember { BluetoothHelper.getPairedDevices(context) }

    var newVehName by remember { mutableStateOf("") }
    var newVehWeight by remember { mutableStateOf("1450") }
    var customStartText by remember(settings.customStartSpeedKmh) {
        mutableStateOf(settings.customStartSpeedKmh.toInt().toString())
    }
    var customEndText by remember(settings.customEndSpeedKmh) {
        mutableStateOf(settings.customEndSpeedKmh.toInt().toString())
    }
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
          Text("SPEED & DISTANCE UNITS (KPH / MPH)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = { onUpdateSettings { it.copy(useMph = !it.useMph) } }) {
              Text(if (settings.useMph) "IMPERIAL (MPH / FT)" else "METRIC (KM/H / M)")
          }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("THEME APPEARANCE", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Button(onClick = {
              val next = ThemeMode.entries[(settings.themeMode.ordinal + 1) % ThemeMode.entries.size]
              onUpdateSettings { it.copy(themeMode = next) }
          }) {
              Text(settings.themeMode.label)
          }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("ORIENTATION LOCK", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
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
  SettingsCard("DRAG STRIP TREE, ROLLOUT & CUSTOM THRESHOLDS") {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Text("DRAG LIGHTING TREE MODE", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
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
          Text("100Hz IMU + GPS KALMAN LAUNCH FUSION", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
          Switch(
              checked = settings.imuFusionEnabled,
              onCheckedChange = { onUpdateSettings { s -> s.copy(imuFusionEnabled = it) } }
          )
      }

      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
              value = customStartText,
              onValueChange = { customStartText = it },
              label = { Text("Custom Start (km/h)") },
              modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
              value = customEndText,
              onValueChange = { customEndText = it },
              label = { Text("Custom End (km/h)") },
              modifier = Modifier.weight(1f)
          )
          Button(onClick = {
              val s = customStartText.toDoubleOrNull() ?: 60.0
              val e = customEndText.toDoubleOrNull() ?: 160.0
              onUpdateSettings { it.copy(customStartSpeedKmh = s, customEndSpeedKmh = e) }
          }) {
              Text("SAVE")
          }
      }
  }
        }

        item {
  SettingsCard("BLUETOOTH OBD-II ELM327 & EXTERNAL GNSS") {
      Text(
          "Select paired Bluetooth devices for live OBD-II gauges or 10Hz/25Hz external GPS:",
          color = Color.Gray,
          fontSize = 11.sp
      )
      Spacer(modifier = Modifier.height(6.dp))
      if (pairedDevices.isEmpty()) {
          Text("No paired Bluetooth devices found.", color = Color(0xFFFFC400), fontSize = 12.sp)
      } else {
          pairedDevices.forEach { (name, mac) ->
              Row(
                  modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
              ) {
                  Text("$name ($mac)", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
                  TextButton(onClick = { onConnectBluetooth(mac, settings.btGnssAddress) }) {
                      Text(
                          if (settings.btObdAddress == mac) "OBD ACTIVE" else "SET OBD",
                          color = Color(0xFF00E676),
                          fontSize = 11.sp
                      )
                  }
                  TextButton(onClick = { onConnectBluetooth(settings.btObdAddress, mac) }) {
                      Text(
                          if (settings.btGnssAddress == mac) "GNSS ACTIVE" else "SET GNSS",
                          color = Color(0xFF00B0FF),
                          fontSize = 11.sp
                      )
                  }
              }
          }
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
              Text("${v.name} (${v.weightKg.toInt()} kg)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
              Text(
                  if (settings.activeVehicleId == v.id) "ACTIVE" else "SELECT",
                  color = if (settings.activeVehicleId == v.id) Color(0xFF00E676) else Color.Gray,
                  fontWeight = FontWeight.Bold
              )
          }
      }
      Spacer(modifier = Modifier.height(6.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
              value = newVehName,
              onValueChange = { newVehName = it },
              label = { Text("New Vehicle Name") },
              modifier = Modifier.weight(1f)
          )
          Button(
              onClick = {
                  if (newVehName.isNotBlank()) {
                      onAddVehicle(
                          VehicleProfile(
                              name = newVehName,
                              makeModel = "Custom",
                              weightKg = newVehWeight.toDoubleOrNull() ?: 1450.0,
                              drivetrain = "RWD"
                          )
                      )
                      newVehName = ""
                  }
              },
              modifier = Modifier.align(Alignment.CenterVertically)
          ) {
              Text("ADD")
          }
      }
      Spacer(modifier = Modifier.height(10.dp))
      Button(onClick = onBackupDatabase, modifier = Modifier.fillMaxWidth()) {
          Text("EXPORT FULL JSON BACKUP")
      }
      Spacer(modifier = Modifier.height(8.dp))
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
        Spacer(modifier = Modifier.height(10.dp))
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
  fontSize = 14.sp,
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
  .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
  text = label,
  color = if (highlight) Color(0xFF00E676) else Color(0xFFB0BEC5),
  fontSize = if (highlight) 14.sp else 13.sp,
  fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
  fontFamily = FontFamily.Monospace
        )
        Text(
  text = value,
  color = if (highlight) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurface,
  fontSize = if (highlight) 15.sp else 13.sp,
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
