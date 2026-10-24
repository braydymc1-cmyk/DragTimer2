package com.performance.gpstimer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

enum class TestMode(val title: String, val subtitle: String, val badge: String) {
    DRAG_TREE("DRAG STRIP + TREE", "NHRA Pro/Sportsman Tree + R/T + 1/4 Mile", "TREE"),
    QUARTER_MILE("1/4 MILE PASS", "Standing 402.3m drag strip timeslip", "402M"),
    HALF_MILE("1/2 MILE ROLL/STOP", "Standing 804.7m airstrip trap speed", "805M"),
    ONE_MILE("1 MILE TOP SPEED", "Standing 1609.3m full mile pass", "1609M"),
    ACCEL_0_100("0 - 100 / 0 - 60", "Standing sprint acceleration timer", "SPRINT"),
    ROLL_100_200("100 - 200 / 60 - 130", "Flying highway roll interval", "ROLL"),
    BRAKE_100_0("100 - 0 BRAKING", "Stopping distance, time & peak G", "BRAKE"),
    CUSTOM_SPEED("CUSTOM SPEED", "User-defined start & target speeds", "CUSTOM"),
    TRACK_CIRCUIT("SATELLITE TRACK", "Satellite map pins, sectors & lap timer", "TRACK")
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
    AUTO("Auto-Rotate"),
    LOCK_LANDSCAPE_IN_DRAG_TRACK("Landscape in Drag/Track"),
    ALWAYS_LANDSCAPE("Always Landscape"),
    ALWAYS_PORTRAIT("Always Portrait")
}

enum class DashStyle(val label: String, val desc: String) {
    GT3_MOTEC("GT3 Pro Motorsport DDU", "LED shift array, central gear/speed, delta & 4-corner OBD"),
    ANALOG_TWIN("Twin-Dial Analog Cluster", "Sweep RPM & Speed needles + live 2D G-Meter"),
    CYBER_STREET("JDM Cyber Digital Bar", "Curved power-band bar, boost gauge & live splits"),
    CUSTOM_6_SLOT("Customisable 6-Slot Dash", "Tap any tile to choose your own OBD/GPS sensors")
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
    val orientationPref: OrientationPref = OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK,
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
        DashMetricType.GPS_SPEED,
        DashMetricType.OBD_RPM,
        DashMetricType.BOOST_PSI,
        DashMetricType.COOLANT_TEMP,
        DashMetricType.THROTTLE_PCT,
        DashMetricType.LONG_G
    )
)

data class WeatherSnapshot(
    val tempC: Double = 22.0,
    val pressureHpa: Double = 1013.25,
    val humidityPct: Double = 45.0,
    val altitudeM: Double = 15.0,
    val densityAltitudeFt: Int = 450,
    val isLive: Boolean = false
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
        val step = if (points.size > 450) (points.size / 450).coerceAtLeast(1) else 1
        for (i in points.indices step step) {
            val p = points[i]
            val obj = JSONObject()
            obj.put("t", p.tMs)
            obj.put("v", (p.speedKmh * 100).roundToInt() / 100.0)
            obj.put("d", (p.distM * 100).roundToInt() / 100.0)
            obj.put("g", (p.longG * 100).roundToInt() / 100.0)
            obj.put("lg", (p.latG * 100).roundToInt() / 100.0)
            obj.put("r", p.rpm)
            obj.put("b", (p.boostPsi * 10).roundToInt() / 10.0)
            obj.put("th", p.throttlePct.roundToInt())
            obj.put("la", p.lat)
            obj.put("lo", p.lon)
            arr.put(obj)
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
                list.add(
                    TelemetryPoint(
                        tMs = o.optLong("t", 0L),
                        speedKmh = o.optDouble("v", 0.0),
                        distM = o.optDouble("d", 0.0),
                        longG = o.optDouble("g", 0.0),
                        latG = o.optDouble("lg", 0.0),
                        rpm = o.optInt("r", 0),
                        boostPsi = o.optDouble("b", 0.0),
                        throttlePct = o.optDouble("th", 0.0),
                        lat = o.optDouble("la", 0.0),
                        lon = o.optDouble("lo", 0.0)
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}

object UnitConverter {
    const val KMH_TO_MPH = 0.6213711922
    const val M_TO_FT = 3.280839895

    fun speed(kmh: Double, useMph: Boolean): Double = if (useMph) kmh * KMH_TO_MPH else kmh
    fun toKmh(valueInCurrentUnit: Double, useMph: Boolean): Double =
        if (useMph) valueInCurrentUnit / KMH_TO_MPH else valueInCurrentUnit
    fun speedUnit(useMph: Boolean): String = if (useMph) "MPH" else "KM/H"
    fun dist(meters: Double, useMph: Boolean): Double = if (useMph) meters * M_TO_FT else meters
    fun distUnit(useMph: Boolean): String = if (useMph) "ft" else "m"

    fun formatSpeed(kmh: Double, useMph: Boolean): String =
        String.format(Locale.US, "%.1f %s", speed(kmh, useMph), speedUnit(useMph))

    fun formatDist(meters: Double, useMph: Boolean): String =
        String.format(Locale.US, "%.1f %s", dist(meters, useMph), distUnit(useMph))

    fun formatTemp(tempC: Double, useMph: Boolean): String =
        if (useMph) String.format(Locale.US, "%.0f°F", tempC * 9.0 / 5.0 + 32.0)
        else String.format(Locale.US, "%.0f°C", tempC)
}

object WeatherCalculator {
    fun computeDensityAltitude(tempC: Double, pressureHpa: Double, humidityPct: Double, altitudeM: Double): WeatherSnapshot {
        val tempK = tempC + 273.15
        val es = 6.1078 * 10.0.pow((7.5 * tempC) / (tempC + 237.3))
        val vaporPressure = es * (humidityPct.coerceIn(0.0, 100.0) / 100.0)
        val dryPressure = (pressureHpa - vaporPressure).coerceAtLeast(100.0)
        val rho = (dryPressure * 100.0) / (287.058 * tempK) + (vaporPressure * 100.0) / (461.495 * tempK)
        val densityRatio = (rho / 1.225).coerceIn(0.5, 1.3)
        val daMeters = 44330.0 * (1.0 - densityRatio.pow(1.0 / 4.25588))
        val daFeet = (daMeters * 3.28084).roundToInt()
        return WeatherSnapshot(tempC, pressureHpa, humidityPct, altitudeM, daFeet, true)
    }

    suspend fun fetchLiveWeather(lat: Double, lon: Double, altM: Double): WeatherSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,surface_pressure"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val cur = JSONObject(body).getJSONObject("current")
                    computeDensityAltitude(
                        cur.optDouble("temperature_2m", 22.0),
                        cur.optDouble("surface_pressure", 1013.25),
                        cur.optDouble("relative_humidity_2m", 45.0),
                        altM
                    )
                } else null
            } catch (_: Exception) { null }
        }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("apex_telemetry_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val slotStr = prefs.getString("customDashSlots", "") ?: ""
        val parsedSlots = if (slotStr.isNotBlank()) {
            slotStr.split(",").mapNotNull { name -> DashMetricType.entries.find { it.name == name } }
        } else emptyList()

        return AppSettings(
            onboardingCompleted = prefs.getBoolean("onboardingCompleted", false),
            useMph = prefs.getBoolean("useMph", false),
            themeMode = ThemeMode.entries.find { it.name == prefs.getString("themeMode", ThemeMode.OLED_DARK.name) } ?: ThemeMode.OLED_DARK,
            rolloutEnabled = prefs.getBoolean("rolloutEnabled", true),
            treeType = TreeType.entries.find { it.name == prefs.getString("treeType", TreeType.SPORTSMAN_500.name) } ?: TreeType.SPORTSMAN_500,
            orientationPref = OrientationPref.entries.find { it.name == prefs.getString("orientationPref", OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK.name) } ?: OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK,
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
