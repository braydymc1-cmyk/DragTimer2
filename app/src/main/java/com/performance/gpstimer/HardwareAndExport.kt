package com.performance.gpstimer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.*
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.hardware.usb.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

data class HardwareDeviceOption(val id: String, val name: String, val isCurrentlyConnected: Boolean, val isUsb: Boolean = false)

@SuppressLint("MissingPermission")
object HardwareDiscoveryHelper {
    fun getBluetoothDevices(context: Context, activeAddress: String): List<HardwareDeviceOption> = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter?.bondedDevices?.map { dev ->
            val isConn = try { (dev.javaClass.getMethod("isConnected").invoke(dev) as? Boolean) == true || dev.address == activeAddress } catch (_: Exception) { dev.address == activeAddress }
            HardwareDeviceOption(dev.address, dev.name ?: "BT (${dev.address})", isConn, false)
        } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }

    fun getUsbGnssDevices(context: Context): List<HardwareDeviceOption> = try {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        um?.deviceList?.values?.map { dev ->
            val label = dev.productName ?: dev.manufacturerName ?: "USB GNSS (${dev.deviceId})"
            HardwareDeviceOption("USB:${dev.deviceName}", "$label [USB Cable]", true, true)
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }
}

object NmeaParser {
    fun parseLine(line: String, curSats: Int, curAlt: Double, onFix: (Double, Double, Double, Double, Int) -> Unit, onMeta: (Int, Double) -> Unit) {
        val c = line.trim()
        if (c.startsWith("\$GNGGA") || c.startsWith("\$GPGGA")) {
            val p = c.split(",")
            if (p.size > 9) onMeta(p[7].toIntOrNull() ?: curSats, p[9].toDoubleOrNull() ?: curAlt)
        } else if (c.startsWith("\$GNRMC") || c.startsWith("\$GPRMC")) {
            val p = c.split(",")
            if (p.size > 8 && p[2] == "A") {
                val lat = nmeaToDec(p[3], p[4]); val lon = nmeaToDec(p[5], p[6])
                val spd = (p[7].toDoubleOrNull() ?: 0.0) * 0.514444
                if (lat != 0.0 || lon != 0.0) onFix(lat, lon, spd, curAlt, curSats)
            }
        }
    }
    private fun nmeaToDec(coord: String, hemi: String): Double {
        val dot = coord.indexOf('.')
        if (dot < 2) return 0.0
        val deg = coord.substring(0, dot - 2).toDoubleOrNull() ?: 0.0
        val min = coord.substring(dot - 2).toDoubleOrNull() ?: 0.0
        val dec = deg + (min / 60.0)
        return if (hemi == "S" || hemi == "W") -dec else dec
    }
}

@SuppressLint("MissingPermission")
class ObdElm327Manager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private val _metrics = MutableStateFlow(ObdLiveMetrics())
    val metrics: StateFlow<ObdLiveMetrics> = _metrics.asStateFlow()

    fun connect(macAddress: String) {
        disconnect()
        if (macAddress.isBlank()) return
        job = scope.launch {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val device = bm?.adapter?.getRemoteDevice(macAddress) ?: return@launch
                val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                btSocket.connect(); socket = btSocket
                val out = btSocket.outputStream; val reader = btSocket.inputStream
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { sendCmd(out, reader, it) }
                _metrics.value = _metrics.value.copy(connected = true, deviceName = device.name ?: macAddress)
                var slow = 0
                while (isActive && btSocket.isConnected) {
                    val rpm = parse0C(sendCmd(out, reader, "010C")) ?: _metrics.value.rpm
                    val map = parseByte(sendCmd(out, reader, "010B"), "410B") ?: _metrics.value.mapKpa
                    val thr = parseByte(sendCmd(out, reader, "0111"), "4111")?.let { (it * 100.0) / 255.0 } ?: _metrics.value.throttlePct
                    val spd = parseByte(sendCmd(out, reader, "010D"), "410D") ?: _metrics.value.obdSpeedKmh
                    var ect = _metrics.value.coolantTempC; var iat = _metrics.value.intakeTempC; var v = _metrics.value.batteryVolts
                    if (slow++ % 8 == 0) {
                        ect = parseByte(sendCmd(out, reader, "0105"), "4105")?.minus(40) ?: ect
                        iat = parseByte(sendCmd(out, reader, "010F"), "410F")?.minus(40) ?: iat
                        v = sendCmd(out, reader, "ATRV").replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: v
                    }
                    _metrics.value = _metrics.value.copy(connected = true, rpm = rpm, obdSpeedKmh = spd, mapKpa = map, boostPsi = (map - 101.325) * 0.145038, throttlePct = thr, coolantTempC = ect, intakeTempC = iat, batteryVolts = v)
                }
            } catch (_: Exception) { _metrics.value = ObdLiveMetrics(connected = false) }
        }
    }
    private fun sendCmd(out: OutputStream, input: InputStream, cmd: String): String {
        out.write("$cmd\r".toByteArray()); out.flush()
        val sb = StringBuilder(); val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < 200L) {
            if (input.available() > 0) {
                val c = input.read().toChar()
                if (c == '>') break
                if (!c.isWhitespace()) sb.append(c)
            }
        }
        return sb.toString().uppercase()
    }
    private fun parse0C(r: String): Int? {
        val i = r.indexOf("410C")
        if (i == -1 || r.length < i + 8) return null
        val a = r.substring(i + 4, i + 6).toIntOrNull(16) ?: return null
        val b = r.substring(i + 6, i + 8).toIntOrNull(16) ?: return null
        return ((a * 256) + b) / 4
    }
    private fun parseByte(r: String, pfx: String): Int? {
        val i = r.indexOf(pfx)
        return if (i == -1 || r.length < i + 6) null else r.substring(i + 4, i + 6).toIntOrNull(16)
    }
    fun disconnect() { job?.cancel(); try { socket?.close() } catch (_: Exception) {}; _metrics.value = ObdLiveMetrics(connected = false) }
}

@SuppressLint("MissingPermission")
class ExternalGnssManager(private val context: Context, private val onExternalFix: (Double, Double, Double, Double, Int) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var btSocket: BluetoothSocket? = null
    var isConnected: Boolean = false; private set

    fun connectBluetooth(mac: String) {
        disconnect()
        if (mac.isBlank()) return
        job = scope.launch {
            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val sock = bm?.adapter?.getRemoteDevice(mac)?.createRfcommSocketToServiceRecord(SPP_UUID) ?: return@launch
                sock.connect(); btSocket = sock; isConnected = true
                val reader = BufferedReader(InputStreamReader(sock.inputStream))
                var alt = 0.0; var sats = 12
                while (isActive && sock.isConnected) {
                    val line = reader.readLine() ?: break
                    NmeaParser.parseLine(line, sats, alt, onExternalFix) { s, a -> sats = s; alt = a }
                }
            } catch (_: Exception) { isConnected = false }
        }
    }
    fun connectUsbCable(deviceIdStr: String) {
        disconnect()
        job = scope.launch {
            try {
                val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return@launch
                val dev = um.deviceList[deviceIdStr.removePrefix("USB:")] ?: um.deviceList.values.firstOrNull() ?: return@launch
                if (!um.hasPermission(dev)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    um.requestPermission(dev, PendingIntent.getBroadcast(context, 0, Intent("com.performance.gpstimer.USB_PERMISSION"), flags))
                    delay(1500L)
                    if (!um.hasPermission(dev)) return@launch
                }
                val conn = um.openDevice(dev) ?: return@launch
                var intf: UsbInterface? = null; var epIn: UsbEndpoint? = null
                for (i in 0 until dev.interfaceCount) {
                    val iface = dev.getInterface(i)
                    for (e in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) { intf = iface; epIn = ep; break }
                    }
                    if (epIn != null) break
                }
                if (intf == null || epIn == null) return@launch
                conn.claimInterface(intf, true)
                val lineCoding = byteArrayOf(0x00, (0xC2).toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
                conn.controlTransfer(0x21, 0x20, 0, 0, lineCoding, lineCoding.size, 300)
                conn.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 300)
                isConnected = true
                val buf = ByteArray(512); val sb = StringBuilder(); var alt = 0.0; var sats = 14
                while (isActive) {
                    val n = conn.bulkTransfer(epIn, buf, buf.size, 500)
                    if (n > 0) {
                        for (ch in String(buf, 0, n, Charsets.US_ASCII)) {
                            if (ch == '\n' || ch == '\r') {
                                if (sb.isNotEmpty()) { NmeaParser.parseLine(sb.toString(), sats, alt, onExternalFix) { s, a -> sats = s; alt = a }; sb.setLength(0) }
                            } else sb.append(ch)
                        }
                    }
                }
            } catch (_: Exception) { isConnected = false }
        }
    }
    fun disconnect() { job?.cancel(); try { btSocket?.close() } catch (_: Exception) {}; isConnected = false }
}

object ExportEngine {
    fun renderTimeslipBitmap(run: RunRecord, useMph: Boolean): Bitmap {
        val w = 760; val h = 1120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#F5F3EB"))
        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 36f; textAlign = Paint.Align.CENTER }
        val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL); textSize = 20f; textAlign = Paint.Align.CENTER }
        val pLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 24f }
        val pVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 25f; textAlign = Paint.Align.RIGHT }
        val pDash = Paint().apply { color = Color.GRAY; strokeWidth = 2f }

        var y = 65f
        canvas.drawText("APEX TELEMETRY", w / 2f, y, pTitle); y += 34f
        canvas.drawText(SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.US).format(Date(run.timestampEpochMs)), w / 2f, y, pSub); y += 30f
        canvas.drawText("VEHICLE: ${run.vehicleName.uppercase()}", w / 2f, y, pSub); y += 26f
        canvas.drawLine(40f, y, w - 40f, y, pDash)

        fun row(l: String, v: String, hl: Boolean = false) {
            y += 45f
            if (hl) canvas.drawRect(36f, y - 32f, w - 36f, y + 12f, Paint().apply { color = Color.parseColor("#E2DFD2") })
            canvas.drawText(l, 52f, y, pLbl); canvas.drawText(v, w - 52f, y, pVal)
        }
        fun sec(ms: Long?): String = if (ms != null) String.format(Locale.US, "%.3f s", ms / 1000.0) else "--.---"

        row("RUN MODE", run.testMode)
        if (run.reactionTimeMs != null) row("R/T (${run.treeType})", "${if (run.redLightJump) "RED " else ""}${sec(run.reactionTimeMs)}", run.redLightJump)
        if (run.testMode == TestMode.BRAKE_100_0.name) {
            row("START SPEED", UnitConverter.formatSpeed(run.brakingStartSpeedKmh ?: 100.0, useMph))
            row("STOP TIME", sec(run.primaryTimeMs), true)
            row("STOP DISTANCE", UnitConverter.formatDist(run.brakingDistanceM ?: run.totalDistanceM, useMph), true)
        } else if (run.testMode == TestMode.TOP_SPEED_LOG.name) {
            row("PEAK TOP SPEED", UnitConverter.formatSpeed(run.trapSpeedKmh, useMph), true)
            row("LOG DURATION", sec(run.primaryTimeMs))
            row("TOTAL DISTANCE", UnitConverter.formatDist(run.totalDistanceM, useMph))
        } else {
            row("60 FT", sec(run.time60ftMs))
            row("330 FT", sec(run.time330ftMs))
            row("1/8 MILE ET", sec(run.timeEighthMileMs))
            row("1/8 MILE SPEED", run.speedEighthMileKmh?.let { UnitConverter.formatSpeed(it, useMph) } ?: "---")
            row("1000 FT", sec(run.time1000ftMs))
            row("PRIMARY ET", sec(run.primaryTimeMs), true)
            row("TRAP SPEED", UnitConverter.formatSpeed(run.trapSpeedKmh, useMph), true)
            row(if (useMph) "0 - 60 MPH" else "0 - 100 KM/H", sec(run.time0to100Ms))
        }
        y += 26f; canvas.drawLine(40f, y, w - 40f, y, pDash)
        row("SLOPE GRADE", String.format(Locale.US, "%.2f%% (%s)", run.slopePercent, if (run.isValidSlope) "VALID" else "INVALID"))
        row("PEAK G / GNSS", String.format(Locale.US, "%.2f G | %.1f Hz", run.peakG, run.gpsHz))
        if (run.densityAltitudeFt != null) row("DENSITY ALT", "${run.densityAltitudeFt} ft")
        y += 50f
        canvas.drawText("*** APEX TELEMETRY OFFICIAL RECORD ***", w / 2f, y, pSub)
        return bmp
    }

    fun saveTimeslipPngToGallery(context: Context, run: RunRecord, useMph: Boolean) {
        val bmp = renderTimeslipBitmap(run, useMph)
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Apex_Telemetry_Run_${run.id}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ApexTelemetry")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(context, "Saved PNG to Gallery (Pictures/ApexTelemetry)!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    fun shareTimeslipPdf(context: Context, run: RunRecord, useMph: Boolean) {
        val bmp = renderTimeslipBitmap(run, useMph)
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create())
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        pdf.finishPage(page)

        var sharedUri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "Apex_Telemetry_Run_${run.id}.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }
                    sharedUri = uri
                    Toast.makeText(context, "Saved PDF to Downloads/Apex_Telemetry_Run_${run.id}.pdf!", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, "Apex_Telemetry_Run_${run.id}.pdf")
                FileOutputStream(file).use { pdf.writeTo(it) }
                Toast.makeText(context, "Saved PDF to Downloads!", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Error writing PDF", Toast.LENGTH_SHORT).show()
        } finally {
            pdf.close()
        }

        if (sharedUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_SUBJECT, "Apex Telemetry Official Timeslip #${run.id}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Official Timeslip PDF"))
        }
    }

    fun shareRefinedCsv(context: Context, runs: List<RunRecord>, useMph: Boolean) {
        val sb = StringBuilder("RunID,Date,Vehicle,Mode,Reaction_s,60ft_s,330ft_s,1_8_s,1000ft_s,Primary_s,PeakOrTrapSpeed_${UnitConverter.speedUnit(useMph)},Distance_m,Slope_pct,PeakG,DA_ft\n")
        fun f(ms: Long?): String = if (ms != null) String.format(Locale.US, "%.3f", ms / 1000.0) else ""
        for (r in runs) {
            sb.appendLine("${r.id},${r.timestampEpochMs},\"${r.vehicleName}\",${r.testMode},${f(r.reactionTimeMs)},${f(r.time60ftMs)},${f(r.time330ftMs)},${f(r.timeEighthMileMs)},${f(r.time1000ftMs)},${f(r.primaryTimeMs)},${String.format(Locale.US, "%.2f", UnitConverter.speed(r.trapSpeedKmh, useMph))},${String.format(Locale.US, "%.1f", r.totalDistanceM)},${String.format(Locale.US, "%.2f", r.slopePercent)},${String.format(Locale.US, "%.2f", r.peakG)},${r.densityAltitudeFt ?: ""}")
        }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_TEXT, sb.toString())
        }, "Export CSV"))
    }

    suspend fun exportFullBackupJson(dao: RunDao): String {
        val root = JSONObject().put("backupVersion", 4)
        val arr = JSONArray()
        for (r in dao.getAllRunsSnapshot()) {
            arr.put(JSONObject().apply {
                put("timestampEpochMs", r.timestampEpochMs); put("testMode", r.testMode)
                put("primaryTimeMs", r.primaryTimeMs); put("trapSpeedKmh", r.trapSpeedKmh)
                put("totalDistanceM", r.totalDistanceM); put("time60ftMs", r.time60ftMs)
                put("time330ftMs", r.time330ftMs); put("timeEighthMileMs", r.timeEighthMileMs)
                put("speedEighthMileKmh", r.speedEighthMileKmh); put("time1000ftMs", r.time1000ftMs)
                put("time0to100Ms", r.time0to100Ms); put("slopePercent", r.slopePercent)
                put("isValidSlope", r.isValidSlope); put("peakG", r.peakG)
                put("reactionTimeMs", r.reactionTimeMs); put("redLightJump", r.redLightJump)
                put("vehicleName", r.vehicleName); put("telemetryJson", r.telemetryJson)
            })
        }
        return root.put("runs", arr).toString(2)
    }

    suspend fun importBackupJson(dao: RunDao, jsonStr: String): Int {
        var c = 0
        try {
            val arr = JSONObject(jsonStr).optJSONArray("runs") ?: return 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                dao.insertRun(RunRecord(
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
                    rolloutEnabled = true, gpsHz = 10.0,
                    vehicleName = o.optString("vehicleName", "Restored Vehicle"),
                    telemetryJson = o.optString("telemetryJson", "[]")
                ))
                c++
            }
        } catch (_: Exception) {}
        return c
    }
}
