package com.performance.gpstimer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
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

data class HardwareDeviceOption(
    val id: String,
    val name: String,
    val isCurrentlyConnected: Boolean,
    val isUsb: Boolean = false
)

@SuppressLint("MissingPermission")
object HardwareDiscoveryHelper {
    fun getBluetoothDevices(context: Context, activeAddress: String): List<HardwareDeviceOption> {
        return try {
  val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  val adapter: BluetoothAdapter? = bm?.adapter
  adapter?.bondedDevices?.map { dev ->
      val isConn = try {
          val m = dev.javaClass.getMethod("isConnected")
          (m.invoke(dev) as? Boolean) == true || dev.address == activeAddress
      } catch (_: Exception) {
          dev.address == activeAddress
      }
      HardwareDeviceOption(
          id = dev.address,
          name = dev.name ?: "BT Device (${dev.address})",
          isCurrentlyConnected = isConn,
          isUsb = false
      )
  } ?: emptyList()
        } catch (_: SecurityException) {
  emptyList()
        }
    }

    fun getUsbGnssDevices(context: Context): List<HardwareDeviceOption> {
        return try {
  val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
  usbManager?.deviceList?.values?.map { dev ->
      val label = dev.productName ?: dev.manufacturerName ?: "USB Serial GNSS (${dev.deviceId})"
      HardwareDeviceOption(
          id = "USB:${dev.deviceName}",
          name = "$label [USB Cable]",
          isCurrentlyConnected = true,
          isUsb = true
      )
  } ?: emptyList()
        } catch (_: Exception) {
  emptyList()
        }
    }
}

object NmeaParser {
    fun parseLine(
        line: String,
        currentSats: Int,
        currentAlt: Double,
        onFix: (lat: Double, lon: Double, speedMps: Double, altM: Double, sats: Int) -> Unit,
        onMetaUpdate: (sats: Int, altM: Double) -> Unit
    ) {
        val clean = line.trim()
        if (clean.startsWith("\$GNGGA") || clean.startsWith("\$GPGGA")) {
  val p = clean.split(",")
  if (p.size > 9) {
      val sats = p[7].toIntOrNull() ?: currentSats
      val alt = p[9].toDoubleOrNull() ?: currentAlt
      onMetaUpdate(sats, alt)
  }
        } else if (clean.startsWith("\$GNRMC") || clean.startsWith("\$GPRMC")) {
  val p = clean.split(",")
  if (p.size > 8 && p[2] == "A") {
      val lat = nmeaToDecimal(p[3], p[4])
      val lon = nmeaToDecimal(p[5], p[6])
      val knots = p[7].toDoubleOrNull() ?: 0.0
      val speedMps = knots * 0.514444
      if (lat != 0.0 || lon != 0.0) {
          onFix(lat, lon, speedMps, currentAlt, currentSats)
      }
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

/**
 * Supports BOTH Wired USB Cable GNSS (u-blox / CDC-ACM / Serial NMEA via USB OTG)
 * and Bluetooth External GNSS receivers.
 */
@SuppressLint("MissingPermission")
class ExternalGnssManager(
    private val context: Context,
    private val onExternalFix: (lat: Double, lon: Double, speedMps: Double, altM: Double, sats: Int) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var btSocket: BluetoothSocket? = null
    var isConnected: Boolean = false
        private set

    fun connectBluetooth(macAddress: String) {
        disconnect()
        if (macAddress.isBlank()) return

        job = scope.launch {
  try {
      val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
      val device = bm?.adapter?.getRemoteDevice(macAddress) ?: return@launch
      val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
      sock.connect()
      btSocket = sock
      isConnected = true

      val reader = BufferedReader(InputStreamReader(sock.inputStream))
      var lastAltM = 0.0
      var lastSats = 12

      while (isActive && sock.isConnected) {
          val line = reader.readLine() ?: break
          NmeaParser.parseLine(
              line = line,
              currentSats = lastSats,
              currentAlt = lastAltM,
              onFix = onExternalFix,
              onMetaUpdate = { s, a -> lastSats = s; lastAltM = a }
          )
      }
  } catch (_: Exception) {
      isConnected = false
  }
        }
    }

    fun connectUsbCable(deviceIdStr: String) {
        disconnect()
        job = scope.launch {
  try {
      val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return@launch
      val cleanName = deviceIdStr.removePrefix("USB:")
      val device: UsbDevice = usbManager.deviceList[cleanName]
          ?: usbManager.deviceList.values.firstOrNull()
          ?: return@launch

      if (!usbManager.hasPermission(device)) {
          val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
          val pi = PendingIntent.getBroadcast(context, 0, Intent("com.performance.gpstimer.USB_PERMISSION"), flags)
          usbManager.requestPermission(device, pi)
          delay(1500L)
          if (!usbManager.hasPermission(device)) return@launch
      }

      val connection = usbManager.openDevice(device) ?: return@launch
      var targetInterface: UsbInterface? = null
      var inEndpoint: UsbEndpoint? = null

      for (i in 0 until device.interfaceCount) {
          val intf = device.getInterface(i)
          for (e in 0 until intf.endpointCount) {
              val ep = intf.getEndpoint(e)
              if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                  targetInterface = intf
                  inEndpoint = ep
                  break
              }
          }
          if (inEndpoint != null) break
      }

      val intf = targetInterface ?: return@launch
      val epIn = inEndpoint ?: return@launch
      connection.claimInterface(intf, true)

      // Send standard CDC-ACM line coding (115200 baud 8N1) & DTR/RTS enable
      val lineCoding = byteArrayOf(0x00, (0xC2).toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
      connection.controlTransfer(0x21, 0x20, 0, 0, lineCoding, lineCoding.size, 300)
      connection.controlTransfer(0x21, 0x22, 0x03, 0, null, 0, 300)

      isConnected = true
      val buffer = ByteArray(512)
      val lineBuilder = StringBuilder()
      var lastAltM = 0.0
      var lastSats = 14

      while (isActive) {
          val n = connection.bulkTransfer(epIn, buffer, buffer.size, 500)
          if (n > 0) {
              val chunk = String(buffer, 0, n, Charsets.US_ASCII)
              for (ch in chunk) {
                  if (ch == '\n' || ch == '\r') {
                      if (lineBuilder.isNotEmpty()) {
                          NmeaParser.parseLine(
                              line = lineBuilder.toString(),
                              currentSats = lastSats,
                              currentAlt = lastAltM,
                              onFix = onExternalFix,
                              onMetaUpdate = { s, a -> lastSats = s; lastAltM = a }
                          )
                          lineBuilder.setLength(0)
                      }
                  } else {
                      lineBuilder.append(ch)
                  }
              }
          }
      }
  } catch (_: Exception) {
      isConnected = false
  }
        }
    }

    fun disconnect() {
        job?.cancel()
        try { btSocket?.close() } catch (_: Exception) {}
        isConnected = false
    }
}
