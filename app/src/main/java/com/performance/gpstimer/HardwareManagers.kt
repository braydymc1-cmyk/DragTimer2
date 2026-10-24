package com.performance.gpstimer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
object BluetoothHelper {
    fun getPairedDevices(context: Context): List<Pair<String, String>> {
        return try {
  val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  val adapter: BluetoothAdapter? = bm?.adapter
  adapter?.bondedDevices?.map { (it.name ?: "Unknown Device") to it.address } ?: emptyList()
        } catch (_: SecurityException) {
  emptyList()
        }
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
      val adapter = bm?.adapter ?: return@launch
      val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
      val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      btSocket.connect()
      socket = btSocket

      val out = btSocket.outputStream
      val reader = btSocket.inputStream

      sendCommand(out, reader, "ATZ")
      sendCommand(out, reader, "ATE0")
      sendCommand(out, reader, "ATL0")
      sendCommand(out, reader, "ATS0")
      sendCommand(out, reader, "ATH0")
      sendCommand(out, reader, "ATSP0")

      _metrics.value = _metrics.value.copy(
          connected = true,
          deviceName = device.name ?: macAddress
      )

      var slowTick = 0
      while (isActive && btSocket.isConnected) {
          val rpmHex = sendCommand(out, reader, "010C")
          val mapHex = sendCommand(out, reader, "010B")
          val thrHex = sendCommand(out, reader, "0111")
          val spdHex = sendCommand(out, reader, "010D")

          val rpm = parsePid0C(rpmHex) ?: _metrics.value.rpm
          val mapKpa = parseSingleBytePid(mapHex, "410B") ?: _metrics.value.mapKpa
          val boostPsi = (mapKpa - 101.325) * 0.145038
          val thrPct = parseSingleBytePid(thrHex, "4111")?.let { (it * 100.0) / 255.0 }
              ?: _metrics.value.throttlePct
          val obdSpd = parseSingleBytePid(spdHex, "410D") ?: _metrics.value.obdSpeedKmh

          var ect = _metrics.value.coolantTempC
          var iat = _metrics.value.intakeTempC
          var volts = _metrics.value.batteryVolts

          if (slowTick++ % 8 == 0) {
              val ectHex = sendCommand(out, reader, "0105")
              val iatHex = sendCommand(out, reader, "010F")
              val vStr = sendCommand(out, reader, "ATRV")
              ect = parseSingleBytePid(ectHex, "4105")?.minus(40) ?: ect
              iat = parseSingleBytePid(iatHex, "410F")?.minus(40) ?: iat
              volts = vStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull() ?: volts
          }

          _metrics.value = _metrics.value.copy(
              connected = true,
              rpm = rpm,
              obdSpeedKmh = obdSpd,
              mapKpa = mapKpa,
              boostPsi = boostPsi,
              throttlePct = thrPct,
              coolantTempC = ect,
              intakeTempC = iat,
              batteryVolts = volts
          )
      }
  } catch (_: Exception) {
      _metrics.value = ObdLiveMetrics(connected = false, deviceName = "Disconnected")
  }
        }
    }

    private fun sendCommand(out: OutputStream, input: java.io.InputStream, cmd: String): String {
        out.write("$cmd\r".toByteArray())
        out.flush()
        val sb = StringBuilder()
        val startMs = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startMs < 220L) {
  if (input.available() > 0) {
      val c = input.read().toChar()
      if (c == '>') break
      if (!c.isWhitespace()) sb.append(c)
  }
        }
        return sb.toString().uppercase()
    }

    private fun parsePid0C(resp: String): Int? {
        val idx = resp.indexOf("410C")
        if (idx == -1 || resp.length < idx + 8) return null
        val a = resp.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return null
        val b = resp.substring(idx + 6, idx + 8).toIntOrNull(16) ?: return null
        return ((a * 256) + b) / 4
    }

    private fun parseSingleBytePid(resp: String, prefix: String): Int? {
        val idx = resp.indexOf(prefix)
        if (idx == -1 || resp.length < idx + 6) return null
        return resp.substring(idx + 4, idx + 6).toIntOrNull(16)
    }

    fun disconnect() {
        job?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        _metrics.value = ObdLiveMetrics(connected = false)
    }
}

@SuppressLint("MissingPermission")
class ExternalGnssManager(
    private val context: Context,
    private val onExternalFix: (lat: Double, lon: Double, speedMps: Double, altM: Double, sats: Int) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    var isConnected: Boolean = false
        private set

    fun connect(macAddress: String) {
        disconnect()
        if (macAddress.isBlank()) return

        job = scope.launch {
  try {
      val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      val device = bm?.adapter?.getRemoteDevice(macAddress) ?: return@launch
      val btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
      btSocket.connect()
      socket = btSocket
      isConnected = true

      val reader = BufferedReader(InputStreamReader(btSocket.inputStream))
      var lastAltM = 0.0
      var lastSats = 12

      while (isActive && btSocket.isConnected) {
          val line = reader.readLine() ?: break
          if (line.startsWith("\$GNGGA") || line.startsWith("\$GPGGA")) {
              val p = line.split(",")
              if (p.size > 9) {
                  lastSats = p[7].toIntOrNull() ?: lastSats
                  lastAltM = p[9].toDoubleOrNull() ?: lastAltM
              }
          } else if (line.startsWith("\$GNRMC") || line.startsWith("\$GPRMC")) {
              val p = line.split(",")
              if (p.size > 8 && p[2] == "A") {
                  val lat = nmeaToDecimal(p[3], p[4])
                  val lon = nmeaToDecimal(p[5], p[6])
                  val knots = p[7].toDoubleOrNull() ?: 0.0
                  val speedMps = knots * 0.514444
                  onExternalFix(lat, lon, speedMps, lastAltM, lastSats)
              }
          }
      }
  } catch (_: Exception) {
      isConnected = false
  }
        }
    }

    private fun nmeaToDecimal(coord: String, hemi: String): Double {
        if (coord.isBlank()) return 0.0
        val dot = coord.indexOf('.')
        if (dot < 2) return 0.0
        val deg = coord.substring(0, dot - 2).toDoubleOrNull() ?: 0.0
        val min = coord.substring(dot - 2).toDoubleOrNull() ?: 0.0
        val dec = deg + (min / 60.0)
        return if (hemi == "S" || hemi == "W") -dec else dec
    }

    fun disconnect() {
        job?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        isConnected = false
    }
}
