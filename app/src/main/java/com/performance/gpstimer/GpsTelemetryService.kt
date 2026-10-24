package com.performance.gpstimer

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class TelemetryUiState(
    val mode: TestMode = TestMode.QUARTER_MILE,
    val status: RunStatus = RunStatus.IDLE,
    val treeStage: TreeStageState = TreeStageState.OFF,
    val reactionTimeMs: Long? = null,
    val redLightJump: Boolean = false,
    val currentSpeedKmh: Double = 0.0,
    val currentG: Double = 0.0,
    val lateralG: Double = 0.0,
    val peakG: Double = 0.0,
    val liveElapsedMs: Long = 0L,
    val liveDistanceM: Double = 0.0,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val accuracyM: Float = 0f,
    val currentHz: Double = 1.0,
    val currentLat: Double = -31.67432,
    val currentLon: Double = 115.78685,
    val currentAltM: Double = 15.0,
    val weather: WeatherSnapshot = WeatherSnapshot(),
    val obd: ObdLiveMetrics = ObdLiveMetrics(),
    val externalGnssActive: Boolean = false,
    val latestRun: RunRecord? = null,
    val currentLapNumber: Int = 1,
    val currentLapMs: Long = 0L,
    val lastSector1Ms: Long? = null,
    val lastSector2Ms: Long? = null,
    val lastSector3Ms: Long? = null,
    val bestLapMs: Long? = null,
    val liveDeltaMs: Long = 0L
)

class GpsTelemetryService : Service(), LocationListener, SensorEventListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var database: AppDatabase
    lateinit var settingsRepo: SettingsRepository
    lateinit var obdManager: ObdElm327Manager
    private lateinit var extGnssManager: ExternalGnssManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val fusionFilter = ImuGpsFusionFilter()
    private val _uiState = MutableStateFlow(TelemetryUiState())
    val uiState: StateFlow<TelemetryUiState> = _uiState.asStateFlow()

    private var prevSample: GpsSample? = null
    private var runStartTimeNanos: Long = 0L
    private var runStartDistanceM: Double = 0.0
    private var runStartAltitudeM: Double = 0.0
    private var cumulativeDistanceM: Double = 0.0
    private var stagingOriginDistanceM: Double = 0.0

    private var treeCountdownJob: Job? = null
    private var greenLightTimeNanos: Long = 0L
    private var activeReactionMs: Long? = null
    private var activeRedLight: Boolean = false

    private var split60ftMs: Long? = null
    private var split330ftMs: Long? = null
    private var splitEighthMs: Long? = null
    private var speedEighthKmh: Double? = null
    private var split1000ftMs: Long? = null
    private var splitQuarterMs: Long? = null
    private var speedQuarterKmh: Double? = null
    private var splitHalfMileMs: Long? = null
    private var speedHalfMileKmh: Double? = null
    private var split0to100Ms: Long? = null
    private var split100to150Ms: Long? = null
    private var timeAt150Nanos: Long? = null
    private var brakingStartSpeedKmh: Double = 100.0
    private var peakRunG: Double = 0.0
    private val activeTrace = mutableListOf<TelemetryPoint>()

    private var activeTrack: TrackLayout? = null
    private var lapStartTimeNanos: Long = 0L
    private var sector1CrossNanos: Long? = null
    private var sector2CrossNanos: Long? = null
    private var lastPinTriggerNanos: Long = 0L
    private var lapMaxSpeedKmh: Double = 0.0
    private var lapPeakLatG: Double = 0.0
    private var lapPeakBrakeG: Double = 0.0

    private val recentFixTimesNanos = ArrayDeque<Long>(15)
    private var weatherFetchedOnce = false

    inner class LocalBinder : Binder() {
        fun getService(): GpsTelemetryService = this@GpsTelemetryService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        settingsRepo = SettingsRepository(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        obdManager = ObdElm327Manager(this)
        extGnssManager = ExternalGnssManager(this) { lat, lon, speedMps, altM, sats ->
            onRawFixAvailable(
                elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                lat = lat,
                lon = lon,
                rawSpeedMps = speedMps,
                altM = altM,
                accM = 0.5f,
                externalSats = sats
            )
        }

        serviceScope.launch {
            obdManager.metrics.collect { obd ->
                _uiState.value = _uiState.value.copy(obd = obd)
            }
        }

        acquireWakeLock()
        startForegroundServiceNotification()
        startRawGpsUpdates()
        startImuSensors()
    }

    private fun startImuSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val enabled = settingsRepo.settings.value.imuFusionEnabled
            fusionFilter.onImuLinearAcceleration(
                event.values[0], event.values[1], event.values[2], event.timestamp, enabled
            )
            _uiState.value = _uiState.value.copy(
                currentG = fusionFilter.longitudinalG,
                lateralG = fusionFilter.lateralG
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (extGnssManager.isConnected) return
            var used = 0
            val total = status.satelliteCount
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
            }
            _uiState.value = _uiState.value.copy(satellitesUsed = used, satellitesVisible = total)
        }
    }

    @SuppressLint("MissingPermission")
    fun startRawGpsUpdates() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper())
            locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastLoc != null) {
                _uiState.value = _uiState.value.copy(
                    currentLat = lastLoc.latitude,
                    currentLon = lastLoc.longitude,
                    currentAltM = lastLoc.altitude
                )
            }
        } catch (_: SecurityException) {}
    }

    fun connectObdDevice(mac: String, name: String) {
        val history = settingsRepo.settings.value.historyObdDevices.toMutableSet()
        if (mac.isNotBlank()) history.add("$name|$mac")
        settingsRepo.update { it.copy(btObdAddress = mac, btObdName = name, historyObdDevices = history) }
        if (mac.isNotBlank()) obdManager.connect(mac) else obdManager.disconnect()
    }

    fun connectGnssDevice(sourceType: GnssSourceType, id: String, name: String) {
        val history = settingsRepo.settings.value.historyGnssDevices.toMutableSet()
        if (id.isNotBlank()) history.add("$name|$id")
        settingsRepo.update {
            it.copy(
                gnssSourceType = sourceType,
                gnssDeviceId = id,
                gnssDeviceName = name,
                historyGnssDevices = history
            )
        }
        when (sourceType) {
            GnssSourceType.INTERNAL_GPS -> extGnssManager.disconnect()
            GnssSourceType.USB_CABLE_GNSS -> extGnssManager.connectUsbCable(id)
            GnssSourceType.BLUETOOTH_GNSS -> extGnssManager.connectBluetooth(id)
        }
    }

    fun refreshWeather() {
        val st = _uiState.value
        serviceScope.launch {
            WeatherCalculator.fetchLiveWeather(st.currentLat, st.currentLon, st.currentAltM)?.let { w ->
                _uiState.value = _uiState.value.copy(weather = w)
            }
        }
    }

    fun updateActiveTrack(track: TrackLayout?) {
        activeTrack = track
        _uiState.value = _uiState.value.copy(bestLapMs = track?.bestLapMs)
    }

    fun setTestMode(mode: TestMode, track: TrackLayout? = null) {
        disarmRun()
        activeTrack = track
        _uiState.value = _uiState.value.copy(mode = mode, latestRun = null, bestLapMs = track?.bestLapMs)
    }

    fun armSystem(track: TrackLayout? = null) {
        treeCountdownJob?.cancel()
        resetRunMetrics()
        if (track != null) activeTrack = track

        val mode = _uiState.value.mode
        val nextStatus = when (mode) {
            TestMode.DRAG_TREE -> RunStatus.TREE_COUNTDOWN
            TestMode.QUARTER_MILE, TestMode.HALF_MILE, TestMode.ONE_MILE, TestMode.ACCEL_0_100 -> RunStatus.STAGED_STOP
            TestMode.ROLL_100_200, TestMode.CUSTOM_SPEED -> RunStatus.STAGED_ROLL
            TestMode.BRAKE_100_0 -> RunStatus.STAGED_BRAKE
            TestMode.TRACK_CIRCUIT -> RunStatus.TRACK_ACTIVE
        }

        _uiState.value = _uiState.value.copy(
            status = nextStatus,
            treeStage = if (mode == TestMode.DRAG_TREE) TreeStageState.PRE_STAGE else TreeStageState.OFF,
            reactionTimeMs = null,
            redLightJump = false,
            liveElapsedMs = 0L,
            liveDistanceM = 0.0,
            peakG = 0.0
        )

        if (mode == TestMode.DRAG_TREE) startDragTreeSequence()
    }

    private fun startDragTreeSequence() {
        val tree = settingsRepo.settings.value.treeType
        greenLightTimeNanos = 0L
        treeCountdownJob = serviceScope.launch {
            _uiState.value = _uiState.value.copy(treeStage = TreeStageState.PRE_STAGE)
            delay(650L)
            _uiState.value = _uiState.value.copy(treeStage = TreeStageState.STAGE)
            delay(Random.nextLong(650L, 1350L))

            if (tree.isSimultaneous) {
                _uiState.value = _uiState.value.copy(treeStage = TreeStageState.AMBER_3)
                delay(tree.amberIntervalMs)
            } else {
                _uiState.value = _uiState.value.copy(treeStage = TreeStageState.AMBER_1)
                delay(tree.amberIntervalMs)
                _uiState.value = _uiState.value.copy(treeStage = TreeStageState.AMBER_2)
                delay(tree.amberIntervalMs)
                _uiState.value = _uiState.value.copy(treeStage = TreeStageState.AMBER_3)
                delay(tree.amberIntervalMs)
            }

            greenLightTimeNanos = SystemClock.elapsedRealtimeNanos()
            if (_uiState.value.treeStage != TreeStageState.RED_FOUL) {
                _uiState.value = _uiState.value.copy(treeStage = TreeStageState.GREEN)
            }
        }
    }

    fun disarmRun() {
        treeCountdownJob?.cancel()
        resetRunMetrics()
        _uiState.value = _uiState.value.copy(
            status = RunStatus.IDLE,
            treeStage = TreeStageState.OFF,
            liveElapsedMs = 0L,
            liveDistanceM = 0.0
        )
    }

    private fun resetRunMetrics() {
        runStartTimeNanos = 0L
        runStartDistanceM = 0.0
        runStartAltitudeM = 0.0
        stagingOriginDistanceM = cumulativeDistanceM
        greenLightTimeNanos = 0L
        activeReactionMs = null
        activeRedLight = false
        split60ftMs = null
        split330ftMs = null
        splitEighthMs = null
        speedEighthKmh = null
        split1000ftMs = null
        splitQuarterMs = null
        speedQuarterKmh = null
        splitHalfMileMs = null
        speedHalfMileKmh = null
        split0to100Ms = null
        split100to150Ms = null
        timeAt150Nanos = null
        peakRunG = 0.0
        activeTrace.clear()
    }

    override fun onLocationChanged(location: Location) {
        if (extGnssManager.isConnected) return
        onRawFixAvailable(
            elapsedNanos = location.elapsedRealtimeNanos,
            lat = location.latitude,
            lon = location.longitude,
            rawSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            altM = if (location.hasAltitude()) location.altitude else 0.0,
            accM = if (location.hasAccuracy()) location.accuracy else 99f,
            externalSats = null
        )
    }

    private fun onRawFixAvailable(
        elapsedNanos: Long,
        lat: Double,
        lon: Double,
        rawSpeedMps: Double,
        altM: Double,
        accM: Float,
        externalSats: Int?
    ) {
        val cfg = settingsRepo.settings.value
        recentFixTimesNanos.addLast(elapsedNanos)
        if (recentFixTimesNanos.size > 12) recentFixTimesNanos.removeFirst()
        val hz = if (recentFixTimesNanos.size >= 2) {
            val spanSec = (recentFixTimesNanos.last() - recentFixTimesNanos.first()) / 1_000_000_000.0
            if (spanSec > 0) (recentFixTimesNanos.size - 1) / spanSec else 1.0
        } else 1.0

        val previous = prevSample
        var gpsAccelG = 0.0
        val dtSec = if (previous != null) (elapsedNanos - previous.elapsedNanos) / 1_000_000_000.0 else 0.0
        if (previous != null && dtSec in 0.005..5.0) {
            gpsAccelG = ((rawSpeedMps - previous.speedMps) / dtSec) / 9.80665
        }

        val fusedSpeedMps = fusionFilter.onGpsSpeedFix(rawSpeedMps, gpsAccelG, cfg.imuFusionEnabled)
        val speedKmh = fusedSpeedMps * 3.6
        val effectiveG = if (cfg.imuFusionEnabled && abs(fusionFilter.longitudinalG) > 0.05) {
            fusionFilter.longitudinalG
        } else gpsAccelG

        if (previous != null && dtSec in 0.005..5.0) {
            val avgSpeedMps = 0.5 * (previous.speedMps + fusedSpeedMps)
            cumulativeDistanceM += avgSpeedMps * dtSec
        }

        val currentSample = GpsSample(
            elapsedNanos = elapsedNanos,
            speedMps = fusedSpeedMps,
            speedKmh = speedKmh,
            cumulativeDistanceM = cumulativeDistanceM,
            altitudeM = altM,
            accuracyM = accM,
            longitudinalG = effectiveG,
            lateralG = fusionFilter.lateralG,
            latitude = lat,
            longitude = lon
        )

        if (!weatherFetchedOnce && lat != 0.0 && lon != 0.0) {
            weatherFetchedOnce = true
            _uiState.value = _uiState.value.copy(currentLat = lat, currentLon = lon, currentAltM = altM)
            refreshWeather()
        }

        if (previous != null) {
            processStateMachine(previous, currentSample, hz, cfg)
        }

        prevSample = currentSample

        _uiState.value = _uiState.value.copy(
            currentSpeedKmh = speedKmh,
            currentG = effectiveG,
            lateralG = fusionFilter.lateralG,
            accuracyM = accM,
            currentHz = hz,
            currentLat = if (lat != 0.0) lat else _uiState.value.currentLat,
            currentLon = if (lon != 0.0) lon else _uiState.value.currentLon,
            currentAltM = altM,
            externalGnssActive = extGnssManager.isConnected,
            satellitesUsed = externalSats ?: _uiState.value.satellitesUsed
        )
    }

    private fun recordTelemetryPoint(s: GpsSample) {
        val elapsedMs = max(0L, (s.elapsedNanos - runStartTimeNanos) / 1_000_000L)
        val distM = max(0.0, s.cumulativeDistanceM - runStartDistanceM)
        val obd = _uiState.value.obd
        activeTrace.add(
            TelemetryPoint(
                tMs = elapsedMs,
                speedKmh = s.speedKmh,
                distM = distM,
                longG = s.longitudinalG,
                latG = s.lateralG,
                rpm = obd.rpm,
                boostPsi = obd.boostPsi,
                throttlePct = obd.throttlePct,
                lat = s.latitude,
                lon = s.longitude
            )
        )
    }

    private fun processStateMachine(s1: GpsSample, s2: GpsSample, hz: Double, cfg: AppSettings) {
        val state = _uiState.value
        when (state.mode) {
            TestMode.DRAG_TREE, TestMode.QUARTER_MILE, TestMode.HALF_MILE, TestMode.ONE_MILE, TestMode.ACCEL_0_100 ->
                processStandingRun(state, s1, s2, hz, cfg)
            TestMode.ROLL_100_200, TestMode.CUSTOM_SPEED ->
                processRollRun(state, s1, s2, hz, cfg)
            TestMode.BRAKE_100_0 ->
                processBrakingRun(state, s1, s2, hz, cfg)
            TestMode.TRACK_CIRCUIT ->
                processTrackCircuit(state, s1, s2)
        }
    }

    private fun processStandingRun(state: TelemetryUiState, s1: GpsSample, s2: GpsSample, hz: Double, cfg: AppSettings) {
        if (state.status == RunStatus.STAGED_STOP || state.status == RunStatus.TREE_COUNTDOWN) {
            if (s2.speedKmh < TimingConstants.LAUNCH_SPEED_THRESHOLD_KMH) {
                stagingOriginDistanceM = s2.cumulativeDistanceM
            } else {
                val launchCrossed = if (!cfg.rolloutEnabled) {
                    runStartTimeNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, TimingConstants.LAUNCH_SPEED_THRESHOLD_KMH)
                    runStartDistanceM = TimingMath.interpolateDistanceAtSpeed(s1, s2, TimingConstants.LAUNCH_SPEED_THRESHOLD_KMH)
                    runStartAltitudeM = TimingMath.interpolateAltitudeAtSpeed(s1, s2, TimingConstants.LAUNCH_SPEED_THRESHOLD_KMH)
                    true
                } else {
                    val rolloutTargetM = stagingOriginDistanceM + TimingConstants.ONE_FOOT_M
                    if (s2.cumulativeDistanceM >= rolloutTargetM) {
                        runStartTimeNanos = TimingMath.interpolateTimeAtDistance(s1, s2, rolloutTargetM)
                        runStartDistanceM = rolloutTargetM
                        runStartAltitudeM = TimingMath.interpolateAltitudeAtDistance(s1, s2, rolloutTargetM)
                        true
                    } else false
                }

                if (launchCrossed) {
                    if (state.mode == TestMode.DRAG_TREE) {
                        treeCountdownJob?.cancel()
                        if (greenLightTimeNanos == 0L || runStartTimeNanos < greenLightTimeNanos) {
                            activeRedLight = true
                            val jumpMs = if (greenLightTimeNanos > 0L) (runStartTimeNanos - greenLightTimeNanos) / 1_000_000L else -250L
                            activeReactionMs = jumpMs
                            _uiState.value = _uiState.value.copy(
                                treeStage = TreeStageState.RED_FOUL,
                                redLightJump = true,
                                reactionTimeMs = jumpMs
                            )
                        } else {
                            activeRedLight = false
                            activeReactionMs = (runStartTimeNanos - greenLightTimeNanos) / 1_000_000L
                            _uiState.value = _uiState.value.copy(
                                treeStage = TreeStageState.GREEN,
                                redLightJump = false,
                                reactionTimeMs = activeReactionMs
                            )
                        }
                    }
                    _uiState.value = _uiState.value.copy(status = RunStatus.RUNNING)
                    evaluateStandingSplits(s1, s2, hz, cfg)
                }
            }
        } else if (state.status == RunStatus.RUNNING) {
            evaluateStandingSplits(s1, s2, hz, cfg)
        }
    }

    private fun evaluateStandingSplits(s1: GpsSample, s2: GpsSample, hz: Double, cfg: AppSettings) {
        recordTelemetryPoint(s2)
        peakRunG = max(peakRunG, abs(s2.longitudinalG))
        val currentRunDist = max(0.0, s2.cumulativeDistanceM - runStartDistanceM)
        val currentElapsedMs = max(0L, (s2.elapsedNanos - runStartTimeNanos) / 1_000_000L)
        val mode = _uiState.value.mode

        val targetSprintKmh = if (cfg.useMph) 96.5606 else 100.0
        if (split0to100Ms == null && s2.speedKmh >= targetSprintKmh) {
            val crossNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, targetSprintKmh)
            split0to100Ms = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
            if (mode == TestMode.ACCEL_0_100) {
                val endDist = TimingMath.interpolateDistanceAtSpeed(s1, s2, targetSprintKmh) - runStartDistanceM
                val endAlt = TimingMath.interpolateAltitudeAtSpeed(s1, s2, targetSprintKmh)
                finalizeRun(mode, split0to100Ms!!, targetSprintKmh, max(1.0, endDist), endAlt, hz, cfg)
                return
            }
        }

        val target60 = runStartDistanceM + TimingConstants.DIST_60_FT_M
        if (split60ftMs == null && s2.cumulativeDistanceM >= target60) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, target60)
            split60ftMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
        }

        val target330 = runStartDistanceM + TimingConstants.DIST_330_FT_M
        if (split330ftMs == null && s2.cumulativeDistanceM >= target330) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, target330)
            split330ftMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
        }

        val targetEighth = runStartDistanceM + TimingConstants.DIST_EIGHTH_MILE_M
        if (splitEighthMs == null && s2.cumulativeDistanceM >= targetEighth) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, targetEighth)
            splitEighthMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
            speedEighthKmh = TimingMath.interpolateSpeedAtDistance(s1, s2, targetEighth)
        }

        val target1000 = runStartDistanceM + TimingConstants.DIST_1000_FT_M
        if (split1000ftMs == null && s2.cumulativeDistanceM >= target1000) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, target1000)
            split1000ftMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
        }

        val targetQuarter = runStartDistanceM + TimingConstants.DIST_QUARTER_MILE_M
        if (splitQuarterMs == null && s2.cumulativeDistanceM >= targetQuarter) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, targetQuarter)
            splitQuarterMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
            speedQuarterKmh = TimingMath.interpolateSpeedAtDistance(s1, s2, targetQuarter)
            if (mode == TestMode.QUARTER_MILE || mode == TestMode.DRAG_TREE) {
                val endAlt = TimingMath.interpolateAltitudeAtDistance(s1, s2, targetQuarter)
                finalizeRun(mode, splitQuarterMs!!, speedQuarterKmh!!, TimingConstants.DIST_QUARTER_MILE_M, endAlt, hz, cfg)
                return
            }
        }

        val targetHalf = runStartDistanceM + TimingConstants.DIST_HALF_MILE_M
        if (splitHalfMileMs == null && s2.cumulativeDistanceM >= targetHalf) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, targetHalf)
            splitHalfMileMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
            speedHalfMileKmh = TimingMath.interpolateSpeedAtDistance(s1, s2, targetHalf)
            if (mode == TestMode.HALF_MILE) {
                val endAlt = TimingMath.interpolateAltitudeAtDistance(s1, s2, targetHalf)
                finalizeRun(mode, splitHalfMileMs!!, speedHalfMileKmh!!, TimingConstants.DIST_HALF_MILE_M, endAlt, hz, cfg)
                return
            }
        }

        val targetMile = runStartDistanceM + TimingConstants.DIST_ONE_MILE_M
        if (mode == TestMode.ONE_MILE && s2.cumulativeDistanceM >= targetMile) {
            val crossNanos = TimingMath.interpolateTimeAtDistance(s1, s2, targetMile)
            val mileMs = max(0L, (crossNanos - runStartTimeNanos) / 1_000_000L)
            val mileSpd = TimingMath.interpolateSpeedAtDistance(s1, s2, targetMile)
            val endAlt = TimingMath.interpolateAltitudeAtDistance(s1, s2, targetMile)
            finalizeRun(mode, mileMs, mileSpd, TimingConstants.DIST_ONE_MILE_M, endAlt, hz, cfg, mileMs, mileSpd)
            return
        }

        _uiState.value = _uiState.value.copy(liveElapsedMs = currentElapsedMs, liveDistanceM = currentRunDist, peakG = peakRunG)
    }

    private fun processRollRun(state: TelemetryUiState, s1: GpsSample, s2: GpsSample, hz: Double, cfg: AppSettings) {
        val startTargetKmh = when (state.mode) {
            TestMode.ROLL_100_200 -> if (cfg.useMph) 96.5606 else 100.0
            else -> cfg.customStartSpeedKmh
        }
        val endTargetKmh = when (state.mode) {
            TestMode.ROLL_100_200 -> if (cfg.useMph) 209.2147 else 200.0
            else -> cfg.customEndSpeedKmh
        }
        val midTargetKmh = (startTargetKmh + endTargetKmh) * 0.5

        if (state.status == RunStatus.STAGED_ROLL) {
            if (s1.speedKmh < startTargetKmh && s2.speedKmh >= startTargetKmh) {
                runStartTimeNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, startTargetKmh)
                runStartDistanceM = TimingMath.interpolateDistanceAtSpeed(s1, s2, startTargetKmh)
                runStartAltitudeM = TimingMath.interpolateAltitudeAtSpeed(s1, s2, startTargetKmh)
                _uiState.value = _uiState.value.copy(status = RunStatus.RUNNING)
            }
        }

        if (_uiState.value.status == RunStatus.RUNNING) {
            if (s2.speedKmh < startTargetKmh - 10.0) {
                armSystem()
                return
            }
            recordTelemetryPoint(s2)
            peakRunG = max(peakRunG, abs(s2.longitudinalG))
            val currentRunDist = max(0.0, s2.cumulativeDistanceM - runStartDistanceM)
            val currentElapsedMs = max(0L, (s2.elapsedNanos - runStartTimeNanos) / 1_000_000L)

            if (split100to150Ms == null && s2.speedKmh >= midTargetKmh) {
                val crossMid = TimingMath.interpolateTimeAtSpeed(s1, s2, midTargetKmh)
                timeAt150Nanos = crossMid
                split100to150Ms = max(0L, (crossMid - runStartTimeNanos) / 1_000_000L)
            }

            if (s2.speedKmh >= endTargetKmh) {
                val finishNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, endTargetKmh)
                val totalTimeMs = max(0L, (finishNanos - runStartTimeNanos) / 1_000_000L)
                val endDistM = TimingMath.interpolateDistanceAtSpeed(s1, s2, endTargetKmh)
                val endAltM = TimingMath.interpolateAltitudeAtSpeed(s1, s2, endTargetKmh)
                val runDistM = max(1.0, endDistM - runStartDistanceM)
                val split150to200 = timeAt150Nanos?.let { max(0L, (finishNanos - it) / 1_000_000L) }
                finalizeRun(state.mode, totalTimeMs, endTargetKmh, runDistM, endAltM, hz, cfg, split150to200 = split150to200)
                return
            }

            _uiState.value = _uiState.value.copy(liveElapsedMs = currentElapsedMs, liveDistanceM = currentRunDist, peakG = peakRunG)
        }
    }

    private fun processBrakingRun(state: TelemetryUiState, s1: GpsSample, s2: GpsSample, hz: Double, cfg: AppSettings) {
        val brakeStartKmh = if (cfg.useMph) 96.5606 else 100.0
        if (state.status == RunStatus.STAGED_BRAKE) {
            if (s1.speedKmh >= brakeStartKmh && s2.speedKmh < brakeStartKmh) {
                brakingStartSpeedKmh = brakeStartKmh
                runStartTimeNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, brakeStartKmh)
                runStartDistanceM = TimingMath.interpolateDistanceAtSpeed(s1, s2, brakeStartKmh)
                runStartAltitudeM = TimingMath.interpolateAltitudeAtSpeed(s1, s2, brakeStartKmh)
                _uiState.value = _uiState.value.copy(status = RunStatus.BRAKING_ACTIVE)
            }
        }

        if (_uiState.value.status == RunStatus.BRAKING_ACTIVE) {
            recordTelemetryPoint(s2)
            peakRunG = max(peakRunG, abs(s2.longitudinalG))
            val currentDistM = max(0.0, s2.cumulativeDistanceM - runStartDistanceM)
            val currentElapsedMs = max(0L, (s2.elapsedNanos - runStartTimeNanos) / 1_000_000L)

            if (s2.speedKmh <= 1.8) {
                val finishNanos = TimingMath.interpolateTimeAtSpeed(s1, s2, 0.0)
                val stopTimeMs = max(100L, (finishNanos - runStartTimeNanos) / 1_000_000L)
                val stopDistM = max(1.0, TimingMath.interpolateDistanceAtSpeed(s1, s2, 0.0) - runStartDistanceM)
                val avgG = ((brakeStartKmh / 3.6) / (stopTimeMs / 1000.0)) / 9.80665
                finalizeRun(TestMode.BRAKE_100_0, stopTimeMs, 0.0, stopDistM, s2.altitudeM, hz, cfg, brakeDistM = stopDistM, avgBrakeG = avgG)
                return
            }

            _uiState.value = _uiState.value.copy(liveElapsedMs = currentElapsedMs, liveDistanceM = currentDistM, peakG = peakRunG)
        }
    }

    private fun processTrackCircuit(state: TelemetryUiState, s1: GpsSample, s2: GpsSample) {
        if (state.status != RunStatus.TRACK_ACTIVE) return
        val track = activeTrack ?: return

        if (lapStartTimeNanos > 0L) {
            val liveLapMs = max(0L, (s2.elapsedNanos - lapStartTimeNanos) / 1_000_000L)
            lapMaxSpeedKmh = max(lapMaxSpeedKmh, s2.speedKmh)
            lapPeakLatG = max(lapPeakLatG, abs(s2.lateralG))
            if (s2.longitudinalG < 0) lapPeakBrakeG = max(lapPeakBrakeG, abs(s2.longitudinalG))
            recordTelemetryPoint(s2)

            if (sector1CrossNanos == null && track.sector1Lat != 0.0) {
                TimingMath.checkSectorPinCrossing(s1, s2, track.sector1Lat, track.sector1Lon)?.let { cross ->
                    if (cross - lapStartTimeNanos > 3_000_000_000L) {
                        sector1CrossNanos = cross
                        _uiState.value = _uiState.value.copy(lastSector1Ms = (cross - lapStartTimeNanos) / 1_000_000L)
                    }
                }
            }

            if (sector1CrossNanos != null && sector2CrossNanos == null && track.sector2Lat != 0.0) {
                TimingMath.checkSectorPinCrossing(s1, s2, track.sector2Lat, track.sector2Lon)?.let { cross ->
                    if (cross - sector1CrossNanos!! > 3_000_000_000L) {
                        sector2CrossNanos = cross
                        _uiState.value = _uiState.value.copy(lastSector2Ms = (cross - sector1CrossNanos!!) / 1_000_000L)
                    }
                }
            }

            _uiState.value = _uiState.value.copy(currentLapMs = liveLapMs)
        }

        if (s2.elapsedNanos - lastPinTriggerNanos > 8_000_000_000L) {
            val finishLat = if (track.isCircuit || track.finishLat == 0.0) track.startLat else track.finishLat
            val finishLon = if (track.isCircuit || track.finishLon == 0.0) track.startLon else track.finishLon

            TimingMath.checkSectorPinCrossing(s1, s2, finishLat, finishLon)?.let { crossNanos ->
                lastPinTriggerNanos = crossNanos
                if (lapStartTimeNanos > 0L) {
                    val totalLapMs = (crossNanos - lapStartTimeNanos) / 1_000_000L
                    val s3Ms = sector2CrossNanos?.let { (crossNanos - it) / 1_000_000L }
                    val prevBest = _uiState.value.bestLapMs
                    val newBest = if (prevBest == null || totalLapMs < prevBest) totalLapMs else prevBest
                    val delta = if (prevBest != null) totalLapMs - prevBest else 0L
                    val lapNum = _uiState.value.currentLapNumber
                    val traceJson = TelemetrySerializer.encodeTrace(activeTrace)

                    serviceScope.launch {
                        val vehicles = database.runDao().getAllVehiclesSnapshot()
                        val vName = vehicles.find { it.id == settingsRepo.settings.value.activeVehicleId }?.name ?: "Track Car"
                        database.runDao().insertLap(
                            TrackLapRecord(
                                trackId = track.id,
                                trackName = track.name,
                                vehicleName = vName,
                                timestampEpochMs = System.currentTimeMillis(),
                                lapNumber = lapNum,
                                lapTimeMs = totalLapMs,
                                sector1Ms = _uiState.value.lastSector1Ms,
                                sector2Ms = _uiState.value.lastSector2Ms,
                                sector3Ms = s3Ms,
                                maxSpeedKmh = lapMaxSpeedKmh,
                                peakLatG = lapPeakLatG,
                                peakBrakingG = lapPeakBrakeG,
                                telemetryJson = traceJson
                            )
                        )
                        if (newBest != prevBest) {
                            database.runDao().insertTrack(track.copy(bestLapMs = newBest))
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        currentLapNumber = lapNum + 1,
                        lastSector3Ms = s3Ms,
                        bestLapMs = newBest,
                        liveDeltaMs = delta
                    )
                }

                lapStartTimeNanos = crossNanos
                runStartTimeNanos = crossNanos
                runStartDistanceM = s2.cumulativeDistanceM
                sector1CrossNanos = null
                sector2CrossNanos = null
                lapMaxSpeedKmh = 0.0
                lapPeakLatG = 0.0
                lapPeakBrakeG = 0.0
                activeTrace.clear()
            }
        }
    }

    private fun finalizeRun(
        mode: TestMode,
        primaryTimeMs: Long,
        trapSpeedKmh: Double,
        totalDistM: Double,
        endAltM: Double,
        hz: Double,
        cfg: AppSettings,
        oneMileMs: Long? = null,
        oneMileSpeed: Double? = null,
        split150to200: Long? = null,
        brakeDistM: Double? = null,
        avgBrakeG: Double? = null
    ) {
        val slope = TimingMath.calculateSlopePercent(runStartAltitudeM, endAltM, totalDistM)
        val w = _uiState.value.weather
        val traceJson = TelemetrySerializer.encodeTrace(activeTrace)

        serviceScope.launch {
            val vehicles = database.runDao().getAllVehiclesSnapshot()
            val activeVehicle = vehicles.find { it.id == cfg.activeVehicleId }
            val record = RunRecord(
                timestampEpochMs = System.currentTimeMillis(),
                testMode = mode.name,
                primaryTimeMs = primaryTimeMs,
                trapSpeedKmh = trapSpeedKmh,
                totalDistanceM = totalDistM,
                time60ftMs = split60ftMs,
                time330ftMs = split330ftMs,
                timeEighthMileMs = splitEighthMs,
                speedEighthMileKmh = speedEighthKmh,
                time1000ftMs = split1000ftMs,
                time0to100Ms = split0to100Ms,
                time100to150Ms = split100to150Ms,
                time150to200Ms = split150to200,
                slopePercent = slope,
                isValidSlope = slope >= -1.0,
                peakG = peakRunG,
                rolloutEnabled = cfg.rolloutEnabled,
                gpsHz = hz,
                reactionTimeMs = activeReactionMs,
                redLightJump = activeRedLight,
                treeType = if (mode == TestMode.DRAG_TREE) cfg.treeType.name else "NONE",
                timeHalfMileMs = splitHalfMileMs,
                speedHalfMileKmh = speedHalfMileKmh,
                timeOneMileMs = oneMileMs,
                speedOneMileKmh = oneMileSpeed,
                brakingStartSpeedKmh = if (mode == TestMode.BRAKE_100_0) brakingStartSpeedKmh else null,
                brakingDistanceM = brakeDistM,
                avgBrakingG = avgBrakeG,
                vehicleProfileId = activeVehicle?.id ?: 1L,
                vehicleName = activeVehicle?.name ?: "Default Vehicle",
                ambientTempC = w.tempC,
                barometricPressureHpa = w.pressureHpa,
                humidityPct = w.humidityPct,
                densityAltitudeFt = w.densityAltitudeFt,
                telemetryJson = traceJson
            )
            database.runDao().insertRun(record)
            _uiState.value = _uiState.value.copy(
                status = RunStatus.COMPLETED,
                liveElapsedMs = record.primaryTimeMs,
                liveDistanceM = record.totalDistanceM,
                latestRun = record
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApexGps::TelemetryWakeLock").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "gps_telemetry_channel"
        val channel = NotificationChannel(channelId, "High Precision Motorsport Telemetry", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Apex GPS & OBD Telemetry Active")
            .setContentText("100Hz IMU + GNSS fusion locked")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        sensorManager.unregisterListener(this)
        obdManager.disconnect()
        extGnssManager.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }
}
