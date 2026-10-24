package com.performance.gpstimer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ExportEngine {

    fun renderTimeslipBitmap(run: RunRecord, useMph: Boolean): Bitmap {
        val width = 760
        val height = 1140
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.parseColor("#F5F3EB"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.BLACK
  typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
  textSize = 32f
  textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.DKGRAY
  typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
  textSize = 20f
  textAlign = Paint.Align.CENTER
        }
        val rowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.BLACK
  typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
  textSize = 24f
        }
        val rowValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.BLACK
  typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
  textSize = 25f
  textAlign = Paint.Align.RIGHT
        }
        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
  color = Color.GRAY
  strokeWidth = 2f
        }

        var y = 65f
        canvas.drawText("APEX MOTORSPORT RACEWAY", width / 2f, y, titlePaint)
        y += 34f
        val df = SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.US)
        canvas.drawText(df.format(Date(run.timestampEpochMs)), width / 2f, y, subPaint)
        y += 30f
        canvas.drawText("VEHICLE: ${run.vehicleName.uppercase()}", width / 2f, y, subPaint)
        y += 28f
        canvas.drawLine(40f, y, width - 40f, y, dashPaint)

        fun drawRow(label: String, value: String, boldHighlight: Boolean = false) {
  y += 46f
  if (boldHighlight) {
      val rectPaint = Paint().apply { color = Color.parseColor("#E2DFD2") }
      canvas.drawRect(36f, y - 32f, width - 36f, y + 12f, rectPaint)
  }
  canvas.drawText(label, 52f, y, rowLabelPaint)
  canvas.drawText(value, width - 52f, y, rowValPaint)
        }

        fun sec(ms: Long?): String = if (ms != null) String.format(Locale.US, "%.3f s", ms / 1000.0) else "--.---"

        drawRow("RUN MODE", run.testMode)
        if (run.reactionTimeMs != null) {
  val rtPrefix = if (run.redLightJump) "RED LIGHT " else ""
  drawRow("R/T (${run.treeType})", "$rtPrefix${sec(run.reactionTimeMs)}", run.redLightJump)
        }

        if (run.testMode == TestMode.BRAKE_100_0.name) {
  drawRow("START SPEED", UnitConverter.formatSpeed(run.brakingStartSpeedKmh ?: 100.0, useMph))
  drawRow("STOP TIME", sec(run.primaryTimeMs), true)
  drawRow("STOP DISTANCE", UnitConverter.formatDist(run.brakingDistanceM ?: run.totalDistanceM, useMph), true)
  drawRow("PEAK DECEL G", String.format(Locale.US, "%.2f G", run.peakG))
  drawRow("AVG BRAKE G", String.format(Locale.US, "%.2f G", run.avgBrakingG ?: 0.0))
        } else {
  drawRow("60 FT", sec(run.time60ftMs))
  drawRow("330 FT", sec(run.time330ftMs))
  drawRow("1/8 MILE ET", sec(run.timeEighthMileMs))
  drawRow(
      "1/8 MILE SPEED",
      run.speedEighthMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "---"
  )
  drawRow("1000 FT", sec(run.time1000ftMs))
  drawRow("1/4 MILE ET", sec(run.primaryTimeMs), true)
  drawRow("1/4 TRAP SPEED", UnitConverter.formatSpeed(run.trapSpeedKmh, useMph), true)

  if (run.timeHalfMileMs != null) {
      drawRow("1/2 MILE ET", sec(run.timeHalfMileMs), true)
      drawRow(
          "1/2 MILE SPEED",
          run.speedHalfMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "---"
      )
  }
  if (run.timeOneMileMs != null) {
      drawRow("1 MILE ET", sec(run.timeOneMileMs), true)
      drawRow(
          "1 MILE SPEED",
          run.speedOneMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "---"
      )
  }
  val accelLabel = if (useMph) "0 - 60 MPH" else "0 - 100 KM/H"
  drawRow(accelLabel, sec(run.time0to100Ms))
  if (run.time100to150Ms != null) {
      drawRow("100 - 150 SPLIT", sec(run.time100to150Ms))
      drawRow("150 - 200 SPLIT", sec(run.time150to200Ms))
  }
        }

        y += 28f
        canvas.drawLine(40f, y, width - 40f, y, dashPaint)

        val slopeStatus = if (run.isValidSlope) "VALID (<1%)" else "INVALID DOWNHILL"
        drawRow("SLOPE GRADE", String.format(Locale.US, "%.2f%% (%s)", run.slopePercent, slopeStatus))
        drawRow("PEAK ACCEL / G", String.format(Locale.US, "%.2f G  |  %.1f Hz GNSS", run.peakG, run.gpsHz))
        drawRow("1-FT ROLLOUT", if (run.rolloutEnabled) "ENABLED" else "OFF")

        if (run.densityAltitudeFt != null) {
  val tempStr = UnitConverter.formatTemp(run.ambientTempC ?: 22.0, useMph)
  drawRow("DENSITY ALTITUDE", "${run.densityAltitudeFt} ft ($tempStr / ${run.barometricPressureHpa?.toInt()} hPa)")
        }

        y += 52f
        canvas.drawText("*** OFFICIAL GPS TELEMETRY RECORD ***", width / 2f, y, subPaint)
        return bmp
    }

    fun shareTimeslipPdf(context: Context, run: RunRecord, useMph: Boolean) {
        val bmp = renderTimeslipBitmap(run, useMph)

        // Save PNG image directly to Phone Gallery (Pictures/ApexTelemetry)
        try {
  val resolver = context.contentResolver
  val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "Apex_Timeslip_Run_${run.id}.png")
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ApexTelemetry")
      }
  }
  val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
  if (uri != null) {
      resolver.openOutputStream(uri)?.use { out ->
          bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
      }
      Toast.makeText(context, "Timeslip PNG saved to Gallery!", Toast.LENGTH_SHORT).show()
  }
        } catch (_: Exception) {}

        // Also generate PDF in cache
        try {
  val pdf = PdfDocument()
  val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
  val page = pdf.startPage(pageInfo)
  page.canvas.drawBitmap(bmp, 0f, 0f, null)
  pdf.finishPage(page)
  val file = File(context.cacheDir, "Timeslip_Run_${run.id}.pdf")
  FileOutputStream(file).use { pdf.writeTo(it) }
  pdf.close()
        } catch (_: Exception) {}

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
  type = "text/plain"
  putExtra(Intent.EXTRA_SUBJECT, "Official Timeslip - ${run.vehicleName} (${run.testMode})")
  putExtra(Intent.EXTRA_TEXT, buildTextTimeslip(run, useMph))
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share Official Timeslip"))
    }

    fun buildTextTimeslip(run: RunRecord, useMph: Boolean): String {
        val df = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.US)
        fun s(ms: Long?): String = if (ms != null) String.format(Locale.US, "%.3fs", ms / 1000.0) else "--.---"
        return buildString {
  appendLine("========================================")
  appendLine("      APEX GPS TELEMETRY TIMESLIP       ")
  appendLine("========================================")
  appendLine("Date:        ${df.format(Date(run.timestampEpochMs))}")
  appendLine("Vehicle:     ${run.vehicleName}")
  appendLine("Mode:        ${run.testMode}")
  if (run.reactionTimeMs != null) {
      appendLine("Reaction:    ${s(run.reactionTimeMs)} ${if (run.redLightJump) "(RED LIGHT)" else "(GREEN)"}")
  }
  appendLine("----------------------------------------")
  if (run.testMode == TestMode.BRAKE_100_0.name) {
      appendLine("Start Speed: ${UnitConverter.formatSpeed(run.brakingStartSpeedKmh ?: 100.0, useMph)}")
      appendLine("Stop Time:   ${s(run.primaryTimeMs)}")
      appendLine("Stop Dist:   ${UnitConverter.formatDist(run.brakingDistanceM ?: run.totalDistanceM, useMph)}")
      appendLine("Peak Decel:  ${String.format(Locale.US, "%.2f G", run.peakG)}")
  } else {
      appendLine("60 FT:       ${s(run.time60ftMs)}")
      appendLine("330 FT:      ${s(run.time330ftMs)}")
      appendLine("1/8 Mile:    ${s(run.timeEighthMileMs)} @ ${run.speedEighthMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "--"}")
      appendLine("1000 FT:     ${s(run.time1000ftMs)}")
      appendLine("1/4 Mile:    ${s(run.primaryTimeMs)} @ ${UnitConverter.formatSpeed(run.trapSpeedKmh, useMph)}")
      if (run.timeHalfMileMs != null) {
          appendLine("1/2 Mile:    ${s(run.timeHalfMileMs)} @ ${run.speedHalfMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "--"}")
      }
      if (run.time0to100Ms != null) {
          appendLine("0-100/0-60:  ${s(run.time0to100Ms)}")
      }
  }
  appendLine("----------------------------------------")
  appendLine("Slope Grade: ${String.format(Locale.US, "%.2f%% (%s)", run.slopePercent, if (run.isValidSlope) "VALID" else "INVALID")}")
  appendLine("Density Alt: ${run.densityAltitudeFt ?: 0} ft (${UnitConverter.formatTemp(run.ambientTempC ?: 22.0, useMph)})")
  appendLine("Peak G:      ${String.format(Locale.US, "%.2f G", run.peakG)} | Rollout: ${if (run.rolloutEnabled) "ON" else "OFF"}")
  appendLine("========================================")
        }
    }

    fun shareRefinedCsv(context: Context, runs: List<RunRecord>, useMph: Boolean) {
        val sb = StringBuilder()
        sb.appendLine("# APEX GPS TELEMETRY SUMMARY LOG")
        sb.appendLine("RunID,Date_ISO,Vehicle,Mode,TreeType,Reaction_s,RedLight,60ft_s,330ft_s,1_8_ET_s,1_8_Speed_${UnitConverter.speedUnit(useMph)},1000ft_s,Primary_ET_s,TrapSpeed_${UnitConverter.speedUnit(useMph)},HalfMile_ET_s,HalfMile_Speed,OneMile_ET_s,0_to_Target_s,100_150_s,150_200_s,BrakeDist_${UnitConverter.distUnit(useMph)},Slope_pct,ValidSlope,PeakG,DensityAlt_ft,Temp_C,Pressure_hPa,Rollout,GNSS_Hz")

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fun fmtMs(ms: Long?): String = if (ms != null) String.format(Locale.US, "%.3f", ms / 1000.0) else ""

        for (r in runs) {
  sb.appendLine(
      "${r.id},${iso.format(Date(r.timestampEpochMs))},\"${r.vehicleName}\",${r.testMode},${r.treeType}," +
              "${fmtMs(r.reactionTimeMs)},${r.redLightJump},${fmtMs(r.time60ftMs)},${fmtMs(r.time330ftMs)}," +
              "${fmtMs(r.timeEighthMileMs)},${r.speedEighthMileKmh?.let { String.format(Locale.US, "%.2f", UnitConverter.speed(it, useMph)) } ?: ""}," +
              "${fmtMs(r.time1000ftMs)},${fmtMs(r.primaryTimeMs)},${String.format(Locale.US, "%.2f", UnitConverter.speed(r.trapSpeedKmh, useMph))}," +
              "${fmtMs(r.timeHalfMileMs)},${r.speedHalfMileKmh?.let { String.format(Locale.US, "%.2f", UnitConverter.speed(it, useMph)) } ?: ""}," +
              "${fmtMs(r.timeOneMileMs)},${fmtMs(r.time0to100Ms)},${fmtMs(r.time100to150Ms)},${fmtMs(r.time150to200Ms)}," +
              "${r.brakingDistanceM?.let { String.format(Locale.US, "%.2f", UnitConverter.dist(it, useMph)) } ?: ""}," +
              "${String.format(Locale.US, "%.2f", r.slopePercent)},${r.isValidSlope},${String.format(Locale.US, "%.2f", r.peakG)}," +
              "${r.densityAltitudeFt ?: ""},${r.ambientTempC ?: ""},${r.barometricPressureHpa ?: ""},${r.rolloutEnabled},${String.format(Locale.US, "%.1f", r.gpsHz)}"
  )
        }

        runs.firstOrNull()?.let { latest ->
  val trace = TelemetrySerializer.decodeTrace(latest.telemetryJson)
  if (trace.isNotEmpty()) {
      sb.appendLine()
      sb.appendLine("# HIGH-FREQUENCY TELEMETRY TRACE (RUN #${latest.id} - ${latest.vehicleName})")
      sb.appendLine("Time_s,Speed_kmh,Speed_mph,Distance_m,Longitudinal_G,Lateral_G,OBD_RPM,Boost_PSI,Throttle_pct,Latitude,Longitude")
      for (p in trace) {
          sb.appendLine(
              String.format(
                  Locale.US,
                  "%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%.1f,%.0f,%.6f,%.6f",
                  p.tMs / 1000.0,
                  p.speedKmh,
                  p.speedKmh * UnitConverter.KMH_TO_MPH,
                  p.distM,
                  p.longG,
                  p.latG,
                  p.rpm,
                  p.boostPsi,
                  p.throttlePct,
                  p.lat,
                  p.lon
              )
          )
      }
  }
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
  type = "text/csv"
  putExtra(Intent.EXTRA_SUBJECT, "Apex Motorsport Telemetry Log CSV")
  putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        context.startActivity(Intent.createChooser(sendIntent, "Export Refined Telemetry CSV"))
    }

    suspend fun exportFullBackupJson(dao: RunDao): String {
        val root = JSONObject()
        root.put("backupVersion", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val runsArr = JSONArray()
        for (r in dao.getAllRunsSnapshot()) {
  val o = JSONObject()
  o.put("timestampEpochMs", r.timestampEpochMs)
  o.put("testMode", r.testMode)
  o.put("primaryTimeMs", r.primaryTimeMs)
  o.put("trapSpeedKmh", r.trapSpeedKmh)
  o.put("totalDistanceM", r.totalDistanceM)
  o.put("time60ftMs", r.time60ftMs)
  o.put("time330ftMs", r.time330ftMs)
  o.put("timeEighthMileMs", r.timeEighthMileMs)
  o.put("speedEighthMileKmh", r.speedEighthMileKmh)
  o.put("time1000ftMs", r.time1000ftMs)
  o.put("time0to100Ms", r.time0to100Ms)
  o.put("time100to150Ms", r.time100to150Ms)
  o.put("time150to200Ms", r.time150to200Ms)
  o.put("slopePercent", r.slopePercent)
  o.put("isValidSlope", r.isValidSlope)
  o.put("peakG", r.peakG)
  o.put("rolloutEnabled", r.rolloutEnabled)
  o.put("gpsHz", r.gpsHz)
  o.put("reactionTimeMs", r.reactionTimeMs)
  o.put("redLightJump", r.redLightJump)
  o.put("treeType", r.treeType)
  o.put("timeHalfMileMs", r.timeHalfMileMs)
  o.put("speedHalfMileKmh", r.speedHalfMileKmh)
  o.put("timeOneMileMs", r.timeOneMileMs)
  o.put("speedOneMileKmh", r.speedOneMileKmh)
  o.put("brakingStartSpeedKmh", r.brakingStartSpeedKmh)
  o.put("brakingDistanceM", r.brakingDistanceM)
  o.put("avgBrakingG", r.avgBrakingG)
  o.put("vehicleName", r.vehicleName)
  o.put("ambientTempC", r.ambientTempC)
  o.put("barometricPressureHpa", r.barometricPressureHpa)
  o.put("densityAltitudeFt", r.densityAltitudeFt)
  o.put("telemetryJson", r.telemetryJson)
  runsArr.put(o)
        }
        root.put("runs", runsArr)

        val tracksArr = JSONArray()
        for (t in dao.getAllTracksSnapshot()) {
  val o = JSONObject()
  o.put("name", t.name)
  o.put("startLat", t.startLat)
  o.put("startLon", t.startLon)
  o.put("sector1Lat", t.sector1Lat)
  o.put("sector1Lon", t.sector1Lon)
  o.put("sector2Lat", t.sector2Lat)
  o.put("sector2Lon", t.sector2Lon)
  o.put("finishLat", t.finishLat)
  o.put("finishLon", t.finishLon)
  o.put("isCircuit", t.isCircuit)
  o.put("bestLapMs", t.bestLapMs)
  tracksArr.put(o)
        }
        root.put("tracks", tracksArr)
        return root.toString(2)
    }

    suspend fun importBackupJson(dao: RunDao, jsonStr: String): Int {
        var importedCount = 0
        try {
  val root = JSONObject(jsonStr)
  val runsArr = root.optJSONArray("runs")
  if (runsArr != null) {
      for (i in 0 until runsArr.length()) {
          val o = runsArr.getJSONObject(i)
          dao.insertRun(
              RunRecord(
                  timestampEpochMs = o.optLong("timestampEpochMs", System.currentTimeMillis()),
                  testMode = o.optString("testMode", TestMode.QUARTER_MILE.name),
                  primaryTimeMs = o.optLong("primaryTimeMs", 0L),
                  trapSpeedKmh = o.optDouble("trapSpeedKmh", 0.0),
                  totalDistanceM = o.optDouble("totalDistanceM", 402.336),
                  time60ftMs = if (o.has("time60ftMs") && !o.isNull("time60ftMs")) o.getLong("time60ftMs") else null,
                  time330ftMs = if (o.has("time330ftMs") && !o.isNull("time330ftMs")) o.getLong("time330ftMs") else null,
                  timeEighthMileMs = if (o.has("timeEighthMileMs") && !o.isNull("timeEighthMileMs")) o.getLong("timeEighthMileMs") else null,
                  speedEighthMileKmh = if (o.has("speedEighthMileKmh") && !o.isNull("speedEighthMileKmh")) o.getDouble("speedEighthMileKmh") else null,
                  time1000ftMs = if (o.has("time1000ftMs") && !o.isNull("time1000ftMs")) o.getLong("time1000ftMs") else null,
                  time0to100Ms = if (o.has("time0to100Ms") && !o.isNull("time0to100Ms")) o.getLong("time0to100Ms") else null,
                  slopePercent = o.optDouble("slopePercent", 0.0),
                  isValidSlope = o.optBoolean("isValidSlope", true),
                  peakG = o.optDouble("peakG", 0.0),
                  rolloutEnabled = o.optBoolean("rolloutEnabled", true),
                  gpsHz = o.optDouble("gpsHz", 10.0),
                  reactionTimeMs = if (o.has("reactionTimeMs") && !o.isNull("reactionTimeMs")) o.getLong("reactionTimeMs") else null,
                  redLightJump = o.optBoolean("redLightJump", false),
                  treeType = o.optString("treeType", "NONE"),
                  vehicleName = o.optString("vehicleName", "Restored Vehicle"),
                  telemetryJson = o.optString("telemetryJson", "[]")
              )
          )
          importedCount++
      }
  }
        } catch (_: Exception) {}
        return importedCount
    }
}
