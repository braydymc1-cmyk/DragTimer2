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

enum class TestMode(
    val title: String,
    val subtitle: String,
    val isStandingStart: Boolean
) {
    DRAG_TREE("DRAG STRIP TREE", "Full NHRA Lighting Tree + R/T + 1/4 Mile Slip", true),
    QUARTER_MILE("1/4 MILE DRAG", "Standing 1/4 Mile (402.3m) with full splits", true),
    HALF_MILE("1/2 MILE AIRSTRIP", "Standing 1/2 Mile (804.7m) + 1/4 Mile splits", true),
    ONE_MILE("1 MILE TOP SPEED", "Standing 1 Mile (1609.3m) pass", true),
    ACCEL_0_100("0 - 100 / 0 - 60", "Standing sprint to target speed", true),
    ROLL_100_200("100 - 200 / 60 - 130", "Flying roll acceleration timer", false),
    BRAKE_100_0("100 - 0 BRAKING", "Deceleration time, stopping distance & peak G", false),
    CUSTOM_SPEED("CUSTOM SPEED RUN", "User-defined start & target speeds", false),
    TRACK_CIRCUIT("TRACK & OBD GAUGES", "GPS sector pins, lap delta & OBD-II cockpit", false)
}

enum class TreeType(val label: String, val amberIntervalMs: Long, val isSimultaneous: Boolean) {
    SPORTSMAN_500("SPORTSMAN (.500s)", 500L, false),
    PRO_400("PRO TREE (.400s)", 400L, true),
    PRO_500("PRO TREE (.500s)", 500L, true)
}

enum class TreeStageState {
    OFF, PRE_STAGE, STAGE, AMBER_1, AMBER_2, AMBER_3, GREEN, RED_FOUL
}

enum class RunStatus(val label: String) {
    IDLE("DISARMED"),
    WAITING_FOR_GPS("ACQUIRING GNSS..."),
    STAGED_STOP("STAGED - READY TO LAUNCH"),
    TREE_COUNTDOWN("TREE ACTIVE - WATCH LIGHTS"),
    STAGED_ROLL("ARMED - CRUISE BELOW START SPEED"),
    STAGED_BRAKE("ARMED - CRUISE ABOVE TARGET SPEED"),
    RUNNING("TIMING IN PROGRESS"),
    BRAKING_ACTIVE("BRAKING TEST IN PROGRESS"),
    TRACK_ACTIVE("TRACK TELEMETRY & LAP TIMING"),
    COMPLETED("RUN COMPLETE")
}

enum class ThemeMode(val label: String) {
    OLED_DARK("OLED Motorsport Dark"),
    SLATE_DARK("Carbon Slate Dark"),
    DAYLIGHT_LIGHT("High-Contrast Daylight")
}

enum class OrientationPref(val label: String) {
    AUTO("Auto-Rotate"),
    LOCK_LANDSCAPE_IN_DRAG_TRACK("Landscape Lock in Drag & Track"),
    ALWAYS_LANDSCAPE("Always Landscape"),
    ALWAYS_PORTRAIT("Always Portrait")
}

enum class GaugeLayoutStyle(val label: String) {
    PRO_ANALOG_COCKPIT("Pro Analog Dials + G-Ball"),
    SECTOR_DELTA_RACER("Big Lap Timer + Shift Bar"),
    OBD_MULTI_GRID("6-Channel OBD Telemetry Grid")
}

data class AppSettings(
    val useMph: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.OLED_DARK,
    val rolloutEnabled: Boolean = true,
    val treeType: TreeType = TreeType.SPORTSMAN_500,
    val orientationPref: OrientationPref = OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK,
    val imuFusionEnabled: Boolean = true,
    val customStartSpeedKmh: Double = 60.0,
    val customEndSpeedKmh: Double = 160.0,
    val gaugeLayout: GaugeLayoutStyle = GaugeLayoutStyle.PRO_ANALOG_COCKPIT,
    val redlineRpm: Int = 7000,
    val activeVehicleId: Long = 1L,
    val activeTrackId: Long = 1L,
    val btObdAddress: String = "",
    val btGnssAddress: String = ""
)

data class WeatherSnapshot(
    val tempC: Double = 22.0,
    val pressureHpa: Double = 1013.25,
    val humidityPct: Double = 45.0,
    val altitudeM: Double = 15.0,
    val densityAltitudeFt: Int = 450,
    val saeCorrectionFactor: Double = 1.000,
    val isLive: Boolean = false
)

data class ObdLiveMetrics(
    val connected: Boolean = false,
    val deviceName: String = "Disconnected",
    val rpm: Int = 0,
    val obdSpeedKmh: Int = 0,
    val coolantTempC: Int = 0,
    val intakeTempC: Int = 0,
    val throttlePct: Double = 0.0,
    val mapKpa: Int = 101,
    val boostPsi: Double = 0.0,
    val timingAdvanceDeg: Double = 0.0,
    val batteryVolts: Double = 12.6
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
        val step = if (points.size > 600) (points.size / 600).coerceAtLeast(1) else 1
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
    fun computeDensityAltitude(
        tempC: Double,
        pressureHpa: Double,
        humidityPct: Double,
        altitudeM: Double
    ): WeatherSnapshot {
        val tempK = tempC + 273.15
        val es = 6.1078 * 10.0.pow((7.5 * tempC) / (tempC + 237.3))
        val vaporPressure = es * (humidityPct.coerceIn(0.0, 100.0) / 100.0)
        val dryPressure = (pressureHpa - vaporPressure).coerceAtLeast(100.0)

        val rDry = 287.058
        val rVapor = 461.495
        val rho = (dryPressure * 100.0) / (rDry * tempK) + (vaporPressure * 100.0) / (rVapor * tempK)

        val densityRatio = (rho / 1.225).coerceIn(0.5, 1.3)
        val daMeters = 44330.0 * (1.0 - densityRatio.pow(1.0 / 4.25588))
        val daFeet = (daMeters * 3.28084).roundToInt()

        val sae = (1.180 * ((990.0 / dryPressure) * ((tempC + 273.15) / 298.15).pow(0.5))) - 0.180

        return WeatherSnapshot(
  tempC = tempC,
  pressureHpa = pressureHpa,
  humidityPct = humidityPct,
  altitudeM = altitudeM,
  densityAltitudeFt = daFeet,
  saeCorrectionFactor = sae.coerceIn(0.85, 1.25),
  isLive = true
        )
    }

    suspend fun fetchLiveWeather(lat: Double, lon: Double, altM: Double): WeatherSnapshot? =
        withContext(Dispatchers.IO) {
  try {
      val urlStr =
          "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,surface_pressure"
      val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
          connectTimeout = 5000
          readTimeout = 5000
          requestMethod = "GET"
      }
      if (conn.responseCode == 200) {
          val body = conn.inputStream.bufferedReader().use { it.readText() }
          val root = JSONObject(body)
          val cur = root.getJSONObject("current")
          val tempC = cur.optDouble("temperature_2m", 22.0)
          val hum = cur.optDouble("relative_humidity_2m", 45.0)
          val pres = cur.optDouble("surface_pressure", 1013.25)
          computeDensityAltitude(tempC, pres, hum, altM)
      } else null
  } catch (_: Exception) {
      null
  }
        }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("apex_telemetry_prefs", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        return AppSettings(
  useMph = prefs.getBoolean("useMph", false),
  themeMode = ThemeMode.entries.find { it.name == prefs.getString("themeMode", ThemeMode.OLED_DARK.name) }
      ?: ThemeMode.OLED_DARK,
  rolloutEnabled = prefs.getBoolean("rolloutEnabled", true),
  treeType = TreeType.entries.find { it.name == prefs.getString("treeType", TreeType.SPORTSMAN_500.name) }
      ?: TreeType.SPORTSMAN_500,
  orientationPref = OrientationPref.entries.find {
      it.name == prefs.getString("orientationPref", OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK.name)
  } ?: OrientationPref.LOCK_LANDSCAPE_IN_DRAG_TRACK,
  imuFusionEnabled = prefs.getBoolean("imuFusionEnabled", true),
  customStartSpeedKmh = prefs.getFloat("customStartKmh", 60f).toDouble(),
  customEndSpeedKmh = prefs.getFloat("customEndKmh", 160f).toDouble(),
  gaugeLayout = GaugeLayoutStyle.entries.find {
      it.name == prefs.getString("gaugeLayout", GaugeLayoutStyle.PRO_ANALOG_COCKPIT.name)
  } ?: GaugeLayoutStyle.PRO_ANALOG_COCKPIT,
  redlineRpm = prefs.getInt("redlineRpm", 7000),
  activeVehicleId = prefs.getLong("activeVehicleId", 1L),
  activeTrackId = prefs.getLong("activeTrackId", 1L),
  btObdAddress = prefs.getString("btObdAddress", "") ?: "",
  btGnssAddress = prefs.getString("btGnssAddress", "") ?: ""
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().apply {
  putBoolean("useMph", next.useMph)
  putString("themeMode", next.themeMode.name)
  putBoolean("rolloutEnabled", next.rolloutEnabled)
  putString("treeType", next.treeType.name)
  putString("orientationPref", next.orientationPref.name)
  putBoolean("imuFusionEnabled", next.imuFusionEnabled)
  putFloat("customStartKmh", next.customStartSpeedKmh.toFloat())
  putFloat("customEndKmh", next.customEndSpeedKmh.toFloat())
  putString("gaugeLayout", next.gaugeLayout.name)
  putInt("redlineRpm", next.redlineRpm)
  putLong("activeVehicleId", next.activeVehicleId)
  putLong("activeTrackId", next.activeTrackId)
  putString("btObdAddress", next.btObdAddress)
  putString("btGnssAddress", next.btGnssAddress)
  apply()
        }
    }
}
