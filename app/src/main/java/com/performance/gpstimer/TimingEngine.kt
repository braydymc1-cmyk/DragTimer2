package com.performance.gpstimer

import kotlin.math.*

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
    val longitude: Double = 0.0,
    val bearingDeg: Float = 0f
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
    var fusedSpeedMps: Double = 0.0
        private set
    var longitudinalG: Double = 0.0
        private set
    var lateralG: Double = 0.0
        private set
    private var lastImuNanos: Long = 0L

    fun onImuLinearAcceleration(ax: Float, ay: Float, az: Float, timestampNanos: Long, enabled: Boolean) {
        if (!enabled) return
        val forwardAccelMps2 = sqrt((ay * ay + az * az).toDouble()) * if (ay + az >= 0) 1.0 else -1.0
        longitudinalG = (forwardAccelMps2 / 9.80665).coerceIn(-2.5, 2.5)
        lateralG = (ax / 9.80665).coerceIn(-2.5, 2.5)

        if (lastImuNanos != 0L) {
  val dt = (timestampNanos - lastImuNanos) / 1_000_000_000.0
  if (dt in 0.001..0.1) {
      if (fusedSpeedMps > 0.25 || abs(longitudinalG) > TimingConstants.LAUNCH_G_THRESHOLD) {
          fusedSpeedMps = max(0.0, fusedSpeedMps + forwardAccelMps2 * dt)
      }
  }
        }
        lastImuNanos = timestampNanos
    }

    fun onGpsSpeedFix(gpsSpeedMps: Double, gpsAccelG: Double, imuEnabled: Boolean): Double {
        if (!imuEnabled) {
  fusedSpeedMps = gpsSpeedMps
  longitudinalG = gpsAccelG
  return gpsSpeedMps
        }
        fusedSpeedMps = if (gpsSpeedMps < 0.35 && abs(longitudinalG) < 0.12) {
  0.0
        } else {
  0.78 * gpsSpeedMps + 0.22 * fusedSpeedMps
        }
        return fusedSpeedMps
    }

    fun reset() {
        fusedSpeedMps = 0.0
        longitudinalG = 0.0
        lateralG = 0.0
        lastImuNanos = 0L
    }
}

object TimingMath {
    fun interpolateTimeAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Long {
        val dv = s2.speedKmh - s1.speedKmh
        if (abs(dv) < 1e-6) return s2.elapsedNanos
        val fraction = ((targetSpeedKmh - s1.speedKmh) / dv).coerceIn(0.0, 1.0)
        val dt = s2.elapsedNanos - s1.elapsedNanos
        return s1.elapsedNanos + (dt * fraction).toLong()
    }

    fun interpolateDistanceAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Double {
        val crossNanos = interpolateTimeAtSpeed(s1, s2, targetSpeedKmh)
        val dt = (crossNanos - s1.elapsedNanos) / 1_000_000_000.0
        val totalDt = (s2.elapsedNanos - s1.elapsedNanos) / 1_000_000_000.0
        if (totalDt <= 0.0) return s2.cumulativeDistanceM
        val accelMps2 = (s2.speedMps - s1.speedMps) / totalDt
        return s1.cumulativeDistanceM + (s1.speedMps * dt) + (0.5 * accelMps2 * dt * dt)
    }

    fun interpolateAltitudeAtSpeed(s1: GpsSample, s2: GpsSample, targetSpeedKmh: Double): Double {
        val dv = s2.speedKmh - s1.speedKmh
        if (abs(dv) < 1e-6) return s2.altitudeM
        val fraction = ((targetSpeedKmh - s1.speedKmh) / dv).coerceIn(0.0, 1.0)
        return s1.altitudeM + (s2.altitudeM - s1.altitudeM) * fraction
    }

    fun interpolateTimeAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Long {
        val dx = s2.cumulativeDistanceM - s1.cumulativeDistanceM
        if (dx <= 1e-6) return s2.elapsedNanos
        val remDist = (targetDistanceM - s1.cumulativeDistanceM).coerceIn(0.0, dx)
        val dtSec = (s2.elapsedNanos - s1.elapsedNanos) / 1_000_000_000.0
        if (dtSec <= 0.0) return s2.elapsedNanos
        val v0 = s1.speedMps
        val a = 2.0 * (dx - v0 * dtSec) / (dtSec * dtSec)
        val tSec = if (abs(a) < 1e-4) {
  if (v0 > 1e-3) remDist / v0 else dtSec * (remDist / dx)
        } else {
  val disc = max(0.0, v0 * v0 + 2.0 * a * remDist)
  val root = (-v0 + sqrt(disc)) / a
  if (root in 0.0..dtSec) root else dtSec * (remDist / dx)
        }
        return s1.elapsedNanos + (tSec * 1_000_000_000.0).toLong()
    }

    fun interpolateSpeedAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Double {
        val crossNanos = interpolateTimeAtDistance(s1, s2, targetDistanceM)
        val totalNanos = (s2.elapsedNanos - s1.elapsedNanos).toDouble()
        if (totalNanos <= 0.0) return s2.speedKmh
        val fraction = ((crossNanos - s1.elapsedNanos) / totalNanos).coerceIn(0.0, 1.0)
        return s1.speedKmh + (s2.speedKmh - s1.speedKmh) * fraction
    }

    fun interpolateAltitudeAtDistance(s1: GpsSample, s2: GpsSample, targetDistanceM: Double): Double {
        val dx = s2.cumulativeDistanceM - s1.cumulativeDistanceM
        if (dx <= 1e-6) return s2.altitudeM
        val fraction = ((targetDistanceM - s1.cumulativeDistanceM) / dx).coerceIn(0.0, 1.0)
        return s1.altitudeM + (s2.altitudeM - s1.altitudeM) * fraction
    }

    fun calculateSlopePercent(startAltM: Double, endAltM: Double, distanceM: Double): Double {
        if (distanceM < 10.0) return 0.0
        return ((endAltM - startAltM) / distanceM) * 100.0
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return Double.MAX_VALUE
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2.0 * r * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun checkSectorPinCrossing(
        s1: GpsSample,
        s2: GpsSample,
        pinLat: Double,
        pinLon: Double,
        gateRadiusM: Double = 22.0
    ): Long? {
        if (pinLat == 0.0 && pinLon == 0.0) return null
        val d2 = haversineMeters(s2.latitude, s2.longitude, pinLat, pinLon)
        if (d2 > gateRadiusM) return null
        val d1 = haversineMeters(s1.latitude, s1.longitude, pinLat, pinLon)
        val total = (d1 + d2).coerceAtLeast(0.01)
        val ratio = (d1 / total).coerceIn(0.0, 1.0)
        return s1.elapsedNanos + ((s2.elapsedNanos - s1.elapsedNanos) * ratio).toLong()
    }
}
