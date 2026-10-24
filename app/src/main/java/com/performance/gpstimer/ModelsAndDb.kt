package com.performance.gpstimer

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.*

enum class TestMode(val title: String, val subtitle: String, val badge: String) {
    DRAG_TREE("DRAG STRIP + TREE", "Fullscreen Tree + Audio Beep -> Live Slip", "TREE"),
    QUARTER_MILE("1/4 MILE PASS", "Standing 402.3m drag strip timeslip", "1/4 MI"),
    HALF_MILE("1/2 MILE PASS", "Standing 804.7m airstrip trap speed", "1/2 MI"),
    ONE_MILE("1 MILE PASS", "Standing 1609.3m full mile pass", "1 MILE"),
    TOP_SPEED_LOG("TOP SPEED LOGGER", "Manual Start/Stop peak speed & distance log", "MAX V"),
    ACCEL_0_100("0 - 100 / 0 - 60", "Standing sprint acceleration timer", "SPRINT"),
    ROLL_100_200("100 - 200 / 60 - 130", "Flying highway roll interval", "ROLL"),
    BRAKE_100_0("100 - 0 BRAKING", "Stopping distance, time & peak G", "BRAKE"),
    CUSTOM_SPEED("CUSTOM SPEED", "Set custom start & target speeds on screen", "CUSTOM"),
    TRACK_CIRCUIT("SATELLITE TRACK", "Draggable satellite map, pins & lap timer", "TRACK")
}

enum class TreeType(val label: String, val amberIntervalMs: Long, val isSimultaneous: Boolean) {
    SPORTSMAN_500("SPORTSMAN (.500s)", 500L, false),
    PRO_400("PRO TREE (.400s)", 400L, true),
    PRO_500("PRO TREE (.500s)", 500L, true)
}

enum class TreeStageState { OFF, PRE_STAGE, STAGE, AMBER_1, AMBER_2, AMBER_3, GREEN, RED_FOUL }

enum class RunStatus(val label: String) {
    IDLE("READY / DISARMED"),
    STAGED_STOP("STAGED — LAUNCH WHEN READY"),
    TREE_COUNTDOWN("WATCH THE TREE..."),
    STAGED_ROLL("ARMED — CRUISE BELOW START SPEED"),
    STAGED_BRAKE("ARMED — ACCELERATE ABOVE BRAKE SPEED"),
    RUNNING("TIMING IN PROGRESS"),
    TOP_SPEED_ACTIVE("RECORDING TOP SPEED — PRESS STOP TO SAVE"),
    BRAKING_ACTIVE("BRAKING TEST ACTIVE"),
    TRACK_ACTIVE("SATELLITE LAP TIMING ACTIVE"),
    COMPLETED("PASS COMPLETED")
}

enum class ThemeMode(val label: String) {
    OLED_DARK("OLED Motorsport"),
    SLATE_DARK("Carbon Slate"),
    DAYLIGHT_LIGHT("Daylight High-Contrast")
}

enum class OrientationPref(val label: String) {
    AUTO("Auto-Rotate (Default)"),
    LOCK_LANDSCAPE_IN_DRAG_TRACK("Landscape in Drag/Track"),
    ALWAYS_LANDSCAPE("Always Landscape"),
    ALWAYS_PORTRAIT("Always Portrait")
}

enum class DashStyle(val label: String) {
    GT3_MOTEC("GT3 Carbon"),
    ANALOG_TWIN("Pro Analog"),
    CYBER_STREET("Cyber Neon"),
    CUSTOM_6_SLOT("Custom 6-Slot")
}

enum class DashMetricType(val label: String, val unit: String) {
    GPS_SPEED("GPS SPEED", "SPD"),
    OBD_RPM("ENGINE RPM", "RPM"),
    BOOST_PSI("BOOST / VAC", "PSI"),
    COOLANT_TEMP("COOLANT ECT", "TEMP"),
    INTAKE_TEMP("INTAKE IAT", "TEMP"),
    THROTTLE_PCT("THROTTLE TPS", "%"),
    BATTERY_VOLTS("BATTERY", "VOLTS"),
    LONG_G("ACCEL / BRAKE G", "G"),
    LAT_G("CORNERING G", "G"),
    DENSITY_ALT("DENSITY ALT", "FT")
}

enum class GnssSourceType(val label: String) {
    INTERNAL_GPS("Phone Internal GNSS"),
    USB_CABLE_GNSS("USB Cable GNSS (OTG)"),
    BLUETOOTH_GNSS("Bluetooth External GNSS")
}

data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val useMph: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.OLED_DARK,
    val rolloutEnabled: Boolean = true,
    val treeType: TreeType = TreeType.SPORTSMAN_500,
    val orientationPref: OrientationPref = OrientationPref.AUTO,
    val imuFusionEnabled: Boolean = true,
    val customStartSpeedKmh: Double = 60.0,
    val customEndSpeedKmh: Double = 160.0,
    val dashStyle: DashStyle = DashStyle.GT3_MOTEC,
    val redlineRpm: Int = 7000,
    val activeVehicleId: Long = 1L,
    val activeTrackId: Long = 1L,
    val gnssSourceType: GnssSourceType = GnssSourceType.INTERNAL_GPS,
    val btObdAddress: String = "",
    val btObdName: String = "",
    val gnssDeviceId: String = "",
    val gnssDeviceName: String = "",
    val historyObdDevices: Set<String> = emptySet(),
    val historyGnssDevices: Set<String> = emptySet(),
    val customDashSlots: List<DashMetricType> = listOf(
        DashMetricType.GPS_SPEED, DashMetricType.OBD_RPM, DashMetricType.BOOST_PSI,
        DashMetricType.COOLANT_TEMP, DashMetricType.THROTTLE_PCT, DashMetricType.LONG_G
    )
)

data class WeatherSnapshot(
    val tempC: Double = 22.0,
    val pressureHpa: Double = 1013.25,
    val humidityPct: Double = 45.0,
    val altitudeM: Double = 15.0,
    val densityAltitudeFt: Int = 450
)

data class ObdLiveMetrics(
    val connected: Boolean = false,
    val deviceName: String = "Disconnected",
    val rpm: Int = 0,
    val obdSpeedKmh: Int = 0,
    val coolantTempC: Int = 86,
    val intakeTempC: Int = 27,
    val throttlePct: Double = 0.0,
    val mapKpa: Int = 101,
    val boostPsi: Double = 0.0,
    val batteryVolts: Double = 13.8
)

data class TelemetryPoint(
    val tMs: Long,
    val speedKmh: Double,
    val distM: Double,
    val longG: Double,
    val latG: Double = 0.0,
    val rpm: Int = 0,
    val boostPsi: Double = 0.0,
    val throttlePct: Double = 0.0,
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

object TelemetrySerializer {
    fun encodeTrace(points: List<TelemetryPoint>): String {
        val arr = JSONArray()
        val step = if (points.size > 400) (points.size / 400).coerceAtLeast(1) else 1
        for (i in points.indices step step) {
            val p = points[i]
            arr.put(JSONObject().apply {
                put("t", p.tMs)
                put("v", (p.speedKmh * 100).roundToInt() / 100.0)
                put("d", (p.distM * 100).roundToInt() / 100.0)
                put("g", (p.longG * 100).roundToInt() / 100.0)
                put("lg", (p.latG * 100).roundToInt() / 100.0)
                put("r", p.rpm)
            })
        }
        return arr.toString()
    }

    fun decodeTrace(json: String): List<TelemetryPoint> {
        if (json.isBlank() || json == "[]") return emptyList()
        val list = mutableListOf<TelemetryPoint>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(TelemetryPoint(
                    tMs = o.optLong("t", 0L),
                    speedKmh = o.optDouble("v", 0.0),
                    distM = o.optDouble("d", 0.0),
                    longG = o.optDouble("g", 0.0),
                    latG = o.optDouble("lg", 0.0),
                    rpm = o.optInt("r", 0)
                ))
            }
        } catch (_: Exception) {}
        return list
    }
}

object UnitConverter {
    const val KMH_TO_MPH = 0.6213711922
    const val M_TO_FT = 3.280839895
    fun speed(kmh: Double, useMph: Boolean): Double = if (useMph) kmh * KMH_TO_MPH else kmh
    fun toKmh(v: Double, useMph: Boolean): Double = if (useMph) v / KMH_TO_MPH else v
    fun speedUnit(useMph: Boolean): String = if (useMph) "MPH" else "KM/H"
    fun dist(meters: Double, useMph: Boolean): Double = if (useMph) meters * M_TO_FT else meters
    fun distUnit(useMph: Boolean): String = if (useMph) "ft" else "m"
    fun formatSpeed(kmh: Double, useMph: Boolean): String = String.format(Locale.US, "%.1f %s", speed(kmh, useMph), speedUnit(useMph))
    fun formatDist(meters: Double, useMph: Boolean): String = String.format(Locale.US, "%.1f %s", dist(meters, useMph), distUnit(useMph))
    fun formatTemp(tempC: Double, useMph: Boolean): String =
        if (useMph) String.format(Locale.US, "%.0f°F", tempC * 9.0 / 5.0 + 32.0) else String.format(Locale.US, "%.0f°C", tempC)
}

object WeatherCalculator {
    fun computeDensityAltitude(tempC: Double, pressureHpa: Double, humidityPct: Double, altitudeM: Double): WeatherSnapshot {
        val tempK = tempC + 273.15
        val es = 6.1078 * 10.0.pow((7.5 * tempC) / (tempC + 237.3))
        val vp = es * (humidityPct.coerceIn(0.0, 100.0) / 100.0)
        val dryP = (pressureHpa - vp).coerceAtLeast(100.0)
        val rho = (dryP * 100.0) / (287.058 * tempK) + (vp * 100.0) / (461.495 * tempK)
        val daMeters = 44330.0 * (1.0 - (rho / 1.225).coerceIn(0.5, 1.3).pow(1.0 / 4.25588))
        return WeatherSnapshot(tempC, pressureHpa, humidityPct, altitudeM, (daMeters * 3.28084).roundToInt())
    }

    suspend fun fetchLiveWeather(lat: Double, lon: Double, altM: Double): WeatherSnapshot? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,surface_pressure").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val cur = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                computeDensityAltitude(cur.optDouble("temperature_2m", 22.0), cur.optDouble("surface_pressure", 1013.25), cur.optDouble("relative_humidity_2m", 45.0), altM)
            } else null
        } catch (_: Exception) { null }
    }
}

/**
 * Process-Wide Singleton Settings Repository so UI, Track Mode, and Service are always 100% in sync.
 */
class SettingsRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("apex_telemetry_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    companion object {
        @Volatile private var INSTANCE: SettingsRepository? = null
        fun getInstance(context: Context): SettingsRepository = INSTANCE ?: synchronized(this) {
            SettingsRepository(context.applicationContext).also { INSTANCE = it }
        }
    }

    private fun load(): AppSettings {
        val slotStr = prefs.getString("customDashSlots", "") ?: ""
        val parsedSlots = if (slotStr.isNotBlank()) slotStr.split(",").mapNotNull { n -> DashMetricType.entries.find { it.name == n } } else emptyList()
        return AppSettings(
            onboardingCompleted = prefs.getBoolean("onboardingCompleted", false),
            useMph = prefs.getBoolean("useMph", false),
            themeMode = ThemeMode.entries.find { it.name == prefs.getString("themeMode", ThemeMode.OLED_DARK.name) } ?: ThemeMode.OLED_DARK,
            rolloutEnabled = prefs.getBoolean("rolloutEnabled", true),
            treeType = TreeType.entries.find { it.name == prefs.getString("treeType", TreeType.SPORTSMAN_500.name) } ?: TreeType.SPORTSMAN_500,
            orientationPref = OrientationPref.entries.find { it.name == prefs.getString("orientationPref", OrientationPref.AUTO.name) } ?: OrientationPref.AUTO,
            imuFusionEnabled = prefs.getBoolean("imuFusionEnabled", true),
            customStartSpeedKmh = prefs.getFloat("customStartKmh", 60f).toDouble(),
            customEndSpeedKmh = prefs.getFloat("customEndKmh", 160f).toDouble(),
            dashStyle = DashStyle.entries.find { it.name == prefs.getString("dashStyle", DashStyle.GT3_MOTEC.name) } ?: DashStyle.GT3_MOTEC,
            redlineRpm = prefs.getInt("redlineRpm", 7000),
            activeVehicleId = prefs.getLong("activeVehicleId", 1L),
            activeTrackId = prefs.getLong("activeTrackId", 1L),
            gnssSourceType = GnssSourceType.entries.find { it.name == prefs.getString("gnssSourceType", GnssSourceType.INTERNAL_GPS.name) } ?: GnssSourceType.INTERNAL_GPS,
            btObdAddress = prefs.getString("btObdAddress", "") ?: "",
            btObdName = prefs.getString("btObdName", "") ?: "",
            gnssDeviceId = prefs.getString("gnssDeviceId", "") ?: "",
            gnssDeviceName = prefs.getString("gnssDeviceName", "") ?: "",
            historyObdDevices = prefs.getStringSet("historyObdDevices", emptySet()) ?: emptySet(),
            historyGnssDevices = prefs.getStringSet("historyGnssDevices", emptySet()) ?: emptySet(),
            customDashSlots = if (parsedSlots.size == 6) parsedSlots else AppSettings().customDashSlots
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().apply {
            putBoolean("onboardingCompleted", next.onboardingCompleted)
            putBoolean("useMph", next.useMph)
            putString("themeMode", next.themeMode.name)
            putBoolean("rolloutEnabled", next.rolloutEnabled)
            putString("treeType", next.treeType.name)
            putString("orientationPref", next.orientationPref.name)
            putBoolean("imuFusionEnabled", next.imuFusionEnabled)
            putFloat("customStartKmh", next.customStartSpeedKmh.toFloat())
            putFloat("customEndKmh", next.customEndSpeedKmh.toFloat())
            putString("dashStyle", next.dashStyle.name)
            putInt("redlineRpm", next.redlineRpm)
            putLong("activeVehicleId", next.activeVehicleId)
            putLong("activeTrackId", next.activeTrackId)
            putString("gnssSourceType", next.gnssSourceType.name)
            putString("btObdAddress", next.btObdAddress)
            putString("btObdName", next.btObdName)
            putString("gnssDeviceId", next.gnssDeviceId)
            putString("gnssDeviceName", next.gnssDeviceName)
            putStringSet("historyObdDevices", next.historyObdDevices)
            putStringSet("historyGnssDevices", next.historyGnssDevices)
            putString("customDashSlots", next.customDashSlots.joinToString(",") { it.name })
            apply()
        }
    }
}

data class GpsSample(
    val elapsedNanos: Long,
    val speedMps: Double,
    val speedKmh: Double,
    val cumulativeDistanceM: Double,
    val altitudeM: Double,
    val accuracyM: Float,
    val longitudinalG: Double,
    val lateralG: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

object TimingConstants {
    const val ONE_FOOT_M = 0.3048
    const val DIST_60_FT_M = 18.288
    const val DIST_330_FT_M = 100.584
    const val DIST_EIGHTH_MILE_M = 201.168
    const val DIST_1000_FT_M = 304.800
    const val DIST_QUARTER_MILE_M = 402.336
    const val DIST_HALF_MILE_M = 804.672
    const val DIST_ONE_MILE_M = 1609.344
    const val LAUNCH_SPEED_THRESHOLD_KMH = 1.6
    const val LAUNCH_G_THRESHOLD = 0.16
}

class ImuGpsFusionFilter {
    var fusedSpeedMps: Double = 0.0; private set
    var longitudinalG: Double = 0.0; private set
    var lateralG: Double = 0.0; private set
    private var lastImuNanos: Long = 0L

    fun onImuLinearAcceleration(ax: Float, ay: Float, az: Float, timestampNanos: Long, enabled: Boolean) {
        if (!enabled) return
        val forwardAccelMps2 = sqrt((ay * ay + az * az).toDouble()) * if (ay + az >= 0) 1.0 else -1.0
        longitudinalG = (forwardAccelMps2 / 9.80665).coerceIn(-2.5, 2.5)
        lateralG = (ax / 9.80665).coerceIn(-2.5, 2.5)
        if (lastImuNanos != 0L) {
            val dt = (timestampNanos - lastImuNanos) / 1_000_000_000.0
            if (dt in 0.001..0.1 && (fusedSpeedMps > 0.25 || abs(longitudinalG) > TimingConstants.LAUNCH_G_THRESHOLD)) {
                fusedSpeedMps = max(0.0, fusedSpeedMps + forwardAccelMps2 * dt)
            }
        }
        lastImuNanos = timestampNanos
    }

    fun onGpsSpeedFix(gpsSpeedMps: Double, gpsAccelG: Double, imuEnabled: Boolean): Double {
        if (!imuEnabled) { fusedSpeedMps = gpsSpeedMps; longitudinalG = gpsAccelG; return gpsSpeedMps }
        fusedSpeedMps = if (gpsSpeedMps < 0.35 && abs(longitudinalG) < 0.12) 0.0 else 0.78 * gpsSpeedMps + 0.22 * fusedSpeedMps
        return fusedSpeedMps
    }
}

object TimingMath {
    fun interpolateTimeAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Long {
        val dv = s2.speedKmh - s1.speedKmh
        if (abs(dv) < 1e-6) return s2.elapsedNanos
        val f = ((targetSpeedKmh - s1.speedKmh) / dv).coerceIn(0.0, 1.0)
        return s1.elapsedNanos + ((s2.elapsedNanos - s1.elapsedNanos) * f).toLong()
    }

    fun interpolateDistanceAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Double {
        val crossNanos = interpolateTimeAtSpeed(s1, s2, targetSpeedKmh)
        val dt = (crossNanos - s1.elapsedNanos) / 1_000_000_000.0
        val totalDt = (s2.elapsedNanos - s1.elapsedNanos) / 1_000_000_000.0
        if (totalDt <= 0.0) return s2.cumulativeDistanceM
        val a = (s2.speedMps - s1.speedMps) / totalDt
        return s1.cumulativeDistanceM + (s1.speedMps * dt) + (0.5 * a * dt * dt)
    }

    fun interpolateAltitudeAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Double {
        val dv = s2.speedKmh - s1.speedKmh
        if (abs(dv) < 1e-6) return s2.altitudeM
        return s1.altitudeM + (s2.altitudeM - s1.altitudeM) * ((targetSpeedKmh - s1.speedKmh) / dv).coerceIn(0.0, 1.0)
    }

    fun interpolateTimeAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Long {
        val dx = s2.cumulativeDistanceM - s1.cumulativeDistanceM
        if (dx <= 1e-6) return s2.elapsedNanos
        val rem = (targetDistanceM - s1.cumulativeDistanceM).coerceIn(0.0, dx)
        val dt = (s2.elapsedNanos - s1.elapsedNanos) / 1_000_000_000.0
        if (dt <= 0.0) return s2.elapsedNanos
        val v0 = s1.speedMps
        val a = 2.0 * (dx - v0 * dt) / (dt * dt)
        val tSec = if (abs(a) < 1e-4) {
            if (v0 > 1e-3) rem / v0 else dt * (rem / dx)
        } else {
            val root = (-v0 + sqrt(max(0.0, v0 * v0 + 2.0 * a * rem))) / a
            if (root in 0.0..dt) root else dt * (rem / dx)
        }
        return s1.elapsedNanos + (tSec * 1_000_000_000.0).toLong()
    }

    fun interpolateSpeedAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Double {
        val cross = interpolateTimeAtDistance(s1, s2, targetDistanceM)
        val tot = (s2.elapsedNanos - s1.elapsedNanos).toDouble()
        if (tot <= 0.0) return s2.speedKmh
        return s1.speedKmh + (s2.speedKmh - s1.speedKmh) * ((cross - s1.elapsedNanos) / tot).coerceIn(0.0, 1.0)
    }

    fun interpolateAltitudeAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Double {
        val dx = s2.cumulativeDistanceM - s1.cumulativeDistanceM
        if (dx <= 1e-6) return s2.altitudeM
        return s1.altitudeM + (s2.altitudeM - s1.altitudeM) * ((targetDistanceM - s1.cumulativeDistanceM) / dx).coerceIn(0.0, 1.0)
    }

    fun calculateSlopePercent(startAltM: Double, endAltM: Double, distanceM: Double): Double =
        if (distanceM < 10.0) 0.0 else ((endAltM - startAltM) / distanceM) * 100.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun checkSectorPinCrossing(s1: GpsSample, s2: GpsSample, pinLat: Double, pinLon: Double, gateRadiusM: Double = 24.0): Long? {
        if (pinLat == 0.0 && pinLon == 0.0) return null
        val d2 = haversineMeters(s2.latitude, s2.longitude, pinLat, pinLon)
        if (d2 > gateRadiusM) return null
        val d1 = haversineMeters(s1.latitude, s1.longitude, pinLat, pinLon)
        val ratio = (d1 / (d1 + d2).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
        return s1.elapsedNanos + ((s2.elapsedNanos - s1.elapsedNanos) * ratio).toLong()
    }
}

@Entity(tableName = "performance_runs")
data class RunRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val testMode: String,
    val primaryTimeMs: Long,
    val trapSpeedKmh: Double,
    val totalDistanceM: Double,
    val time60ftMs: Long? = null,
    val time330ftMs: Long? = null,
    val timeEighthMileMs: Long? = null,
    val speedEighthMileKmh: Double? = null,
    val time1000ftMs: Long? = null,
    val time0to100Ms: Long? = null,
    val time100to150Ms: Long? = null,
    val time150to200Ms: Long? = null,
    val slopePercent: Double,
    val isValidSlope: Boolean,
    val peakG: Double,
    val rolloutEnabled: Boolean,
    val gpsHz: Double,
    @ColumnInfo(defaultValue = "NULL") val reactionTimeMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val redLightJump: Boolean = false,
    @ColumnInfo(defaultValue = "'NONE'") val treeType: String = "NONE",
    @ColumnInfo(defaultValue = "NULL") val timeHalfMileMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val speedHalfMileKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val timeOneMileMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val speedOneMileKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val brakingStartSpeedKmh: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val brakingDistanceM: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val avgBrakingG: Double? = null,
    @ColumnInfo(defaultValue = "1") val vehicleProfileId: Long = 1L,
    @ColumnInfo(defaultValue = "'Default Vehicle'") val vehicleName: String = "Default Vehicle",
    @ColumnInfo(defaultValue = "NULL") val ambientTempC: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val barometricPressureHpa: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val humidityPct: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val densityAltitudeFt: Int? = null,
    @ColumnInfo(defaultValue = "'[]'") val telemetryJson: String = "[]"
)

@Entity(tableName = "vehicle_profiles")
data class VehicleProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val makeModel: String,
    val weightKg: Double,
    val drivetrain: String,
    val notes: String = "",
    @ColumnInfo(defaultValue = "''") val photoUri: String = ""
)

@Entity(tableName = "track_layouts")
data class TrackLayout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val sector1Lat: Double = 0.0,
    val sector1Lon: Double = 0.0,
    val sector2Lat: Double = 0.0,
    val sector2Lon: Double = 0.0,
    val finishLat: Double = 0.0,
    val finishLon: Double = 0.0,
    val isCircuit: Boolean = true,
    val bestLapMs: Long? = null
)

@Entity(tableName = "track_laps")
data class TrackLapRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val trackName: String,
    val vehicleName: String,
    val timestampEpochMs: Long,
    val lapNumber: Int,
    val lapTimeMs: Long,
    val sector1Ms: Long? = null,
    val sector2Ms: Long? = null,
    val sector3Ms: Long? = null,
    val maxSpeedKmh: Double,
    val peakLatG: Double,
    val peakBrakingG: Double,
    val telemetryJson: String = "[]"
)

@Dao
interface RunDao {
    @Query("SELECT * FROM performance_runs ORDER BY timestampEpochMs DESC")
    fun getAllRuns(): Flow<List<RunRecord>>
    @Query("SELECT * FROM performance_runs ORDER BY timestampEpochMs DESC")
    suspend fun getAllRunsSnapshot(): List<RunRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: RunRecord): Long
    @Delete
    suspend fun deleteRun(run: RunRecord)
    @Query("DELETE FROM performance_runs")
    suspend fun clearAllRuns()

    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    fun getAllVehicles(): Flow<List<VehicleProfile>>
    @Query("SELECT * FROM vehicle_profiles ORDER BY id ASC")
    suspend fun getAllVehiclesSnapshot(): List<VehicleProfile>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleProfile): Long
    @Delete
    suspend fun deleteVehicle(vehicle: VehicleProfile)

    @Query("SELECT * FROM track_layouts ORDER BY id ASC")
    fun getAllTracks(): Flow<List<TrackLayout>>
    @Query("SELECT * FROM track_layouts ORDER BY id ASC")
    suspend fun getAllTracksSnapshot(): List<TrackLayout>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackLayout): Long
    @Delete
    suspend fun deleteTrack(track: TrackLayout)

    @Query("SELECT * FROM track_laps ORDER BY timestampEpochMs DESC")
    fun getAllLaps(): Flow<List<TrackLapRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLap(lap: TrackLapRecord): Long
    @Delete
    suspend fun deleteLap(lap: TrackLapRecord)
}

@Database(entities = [RunRecord::class, VehicleProfile::class, TrackLayout::class, TrackLapRecord::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN reactionTimeMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN redLightJump INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN treeType TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN timeHalfMileMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN speedHalfMileKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN timeOneMileMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN speedOneMileKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN brakingStartSpeedKmh REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN brakingDistanceM REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN avgBrakingG REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN vehicleProfileId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN vehicleName TEXT NOT NULL DEFAULT 'Default Vehicle'")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN ambientTempC REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN barometricPressureHpa REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN humidityPct REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN densityAltitudeFt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE performance_runs ADD COLUMN telemetryJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS `vehicle_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `makeModel` TEXT NOT NULL, `weightKg` REAL NOT NULL, `drivetrain` TEXT NOT NULL, `notes` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_layouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startLat` REAL NOT NULL, `startLon` REAL NOT NULL, `sector1Lat` REAL NOT NULL, `sector1Lon` REAL NOT NULL, `sector2Lat` REAL NOT NULL, `sector2Lon` REAL NOT NULL, `finishLat` REAL NOT NULL, `finishLon` REAL NOT NULL, `isCircuit` INTEGER NOT NULL, `bestLapMs` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `track_laps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` INTEGER NOT NULL, `trackName` TEXT NOT NULL, `vehicleName` TEXT NOT NULL, `timestampEpochMs` INTEGER NOT NULL, `lapNumber` INTEGER NOT NULL, `lapTimeMs` INTEGER NOT NULL, `sector1Ms` INTEGER, `sector2Ms` INTEGER, `sector3Ms` INTEGER, `maxSpeedKmh` REAL NOT NULL, `peakLatG` REAL NOT NULL, `peakBrakingG` REAL NOT NULL, `telemetryJson` TEXT NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicle_profiles ADD COLUMN photoUri TEXT NOT NULL DEFAULT ''")
            }
        }
        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "gps_drag_timer.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            val dao = getInstance(context).runDao()
                            if (dao.getAllTracksSnapshot().isEmpty()) {
                                dao.insertTrack(TrackLayout(
                                    name = "Wanneroo Raceway (Barbagallo)",
                                    startLat = -31.67432, startLon = 115.78685,
                                    sector1Lat = -31.67108, sector1Lon = 115.78852,
                                    sector2Lat = -31.67610, sector2Lon = 115.79160,
                                    finishLat = -31.67432, finishLon = 115.78685, isCircuit = true
                                ))
                            }
                        }
                    }
                }).build().also { INSTANCE = it }
        }
    }
}
