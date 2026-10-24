package com.performance.gpstimer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.*

@Composable
fun NhraDragLightingTree(
    stageState: TreeStageState,
    treeType: TreeType,
    reactionMs: Long?,
    redLight: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
  .clip(RoundedCornerShape(16.dp))
  .background(Color(0xFF0D1017))
  .border(1.5.dp, Color(0xFF263238), RoundedCornerShape(16.dp))
  .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
  text = treeType.label,
  color = Color(0xFF90A4AE),
  fontSize = 11.sp,
  fontFamily = FontFamily.Monospace,
  fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        val preStageLit = stageState != TreeStageState.OFF
        val stageLit = stageState.ordinal >= TreeStageState.STAGE.ordinal

        StagingBeamRow("PRE-STAGE", preStageLit)
        Spacer(modifier = Modifier.height(6.dp))
        StagingBeamRow("STAGE", stageLit)

        Spacer(modifier = Modifier.height(10.dp))

        val amber1Lit = stageState == TreeStageState.AMBER_1 ||
      (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val amber2Lit = stageState == TreeStageState.AMBER_2 ||
      (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val amber3Lit = stageState == TreeStageState.AMBER_3

        TreeBulb(color = Color(0xFFFFB300), isLit = amber1Lit)
        Spacer(modifier = Modifier.height(8.dp))
        TreeBulb(color = Color(0xFFFFB300), isLit = amber2Lit)
        Spacer(modifier = Modifier.height(8.dp))
        TreeBulb(color = Color(0xFFFFB300), isLit = amber3Lit)
        Spacer(modifier = Modifier.height(8.dp))

        TreeBulb(color = Color(0xFF00E676), isLit = stageState == TreeStageState.GREEN)
        Spacer(modifier = Modifier.height(8.dp))

        TreeBulb(color = Color(0xFFFF1744), isLit = stageState == TreeStageState.RED_FOUL || redLight)

        Spacer(modifier = Modifier.height(10.dp))

        val rtText = when {
  reactionMs != null && redLight -> String.format(Locale.US, "FOUL %+.3fs", reactionMs / 1000.0)
  reactionMs != null -> String.format(Locale.US, "R/T: %.3fs", reactionMs / 1000.0)
  else -> "R/T: --.---"
        }
        Text(
  text = rtText,
  color = if (redLight) Color(0xFFFF1744) else Color(0xFF00E676),
  fontSize = 15.sp,
  fontWeight = FontWeight.Black,
  fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StagingBeamRow(label: String, isLit: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  repeat(2) {
      Box(
          modifier = Modifier
              .size(14.dp)
              .clip(CircleShape)
              .background(if (isLit) Color(0xFFFFEA00) else Color(0xFF263238))
      )
  }
        }
    }
}

@Composable
private fun TreeBulb(color: Color, isLit: Boolean) {
    Box(
        modifier = Modifier
  .size(34.dp)
  .clip(CircleShape)
  .background(if (isLit) color else Color(0xFF1C222B))
  .border(2.dp, if (isLit) Color.White else Color(0xFF2D3748), CircleShape)
    )
}

@Composable
fun RunComparisonGraph(
    primaryRun: RunRecord,
    comparisonRun: RunRecord?,
    useMph: Boolean,
    modifier: Modifier = Modifier
) {
    val traceA = remember(primaryRun.id) { TelemetrySerializer.decodeTrace(primaryRun.telemetryJson) }
    val traceB = remember(comparisonRun?.id) {
        comparisonRun?.let { TelemetrySerializer.decodeTrace(it.telemetryJson) } ?: emptyList()
    }

    var cursorFraction by remember { mutableFloatStateOf(0.5f) }

    Column(
        modifier = modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(12.dp))
  .background(Color(0xFF141820))
  .padding(14.dp)
    ) {
        Text(
  text = if (comparisonRun != null) {
      "TELEMETRY OVERLAY: RUN #${primaryRun.id} (GREEN) vs RUN #${comparisonRun.id} (CYAN)"
  } else {
      "TELEMETRY GRAPH: RUN #${primaryRun.id} (${primaryRun.vehicleName})"
  },
  color = Color(0xFF00B0FF),
  fontSize = 12.sp,
  fontWeight = FontWeight.Bold,
  fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (traceA.size < 2) {
  Box(
      modifier = Modifier
          .fillMaxWidth()
          .height(160.dp),
      contentAlignment = Alignment.Center
  ) {
      Text("No high-rate trace stored for this pass.", color = Color.Gray, fontSize = 12.sp)
  }
        } else {
  val maxTimeMs = max(
      traceA.last().tMs,
      if (traceB.isNotEmpty()) traceB.last().tMs else 1L
  ).coerceAtLeast(1000L)
  val maxSpeed = max(
      traceA.maxOf { it.speedKmh },
      if (traceB.isNotEmpty()) traceB.maxOf { it.speedKmh } else 100.0
  ).coerceAtLeast(60.0)

  Canvas(
      modifier = Modifier
          .fillMaxWidth()
          .height(180.dp)
          .background(Color(0xFF0B0E14), RoundedCornerShape(8.dp))
  ) {
      val w = size.width
      val h = size.height

      for (i in 1..3) {
          val y = h * (i / 4f)
          drawLine(Color(0xFF1E293B), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
      }

      fun drawSpeedPath(points: List<TelemetryPoint>, color: Color) {
          if (points.size < 2) return
          val path = Path()
          points.forEachIndexed { idx, p ->
              val x = (p.tMs.toFloat() / maxTimeMs.toFloat()) * w
              val y = h - ((p.speedKmh / maxSpeed).toFloat() * (h * 0.88f)) - 8f
              if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
          }
          drawPath(path, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round))
      }

      drawSpeedPath(traceA, Color(0xFF00E676))
      if (traceB.isNotEmpty()) {
          drawSpeedPath(traceB, Color(0xFF00B0FF))
      }

      val cx = cursorFraction * w
      drawLine(Color.White.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
  }

  Spacer(modifier = Modifier.height(6.dp))

  val targetMs = (cursorFraction * maxTimeMs).toLong()
  val ptA = traceA.minByOrNull { abs(it.tMs - targetMs) }
  val ptB = traceB.minByOrNull { abs(it.tMs - targetMs) }

  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
  ) {
      Text(
          text = String.format(Locale.US, "T: %.2fs", targetMs / 1000.0),
          color = Color.White,
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace
      )
      ptA?.let {
          Text(
              text = "#${primaryRun.id}: ${UnitConverter.formatSpeed(it.speedKmh, useMph)} (${String.format(Locale.US, "%.2fG", it.longG)})",
              color = Color(0xFF00E676),
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
          )
      }
      ptB?.let {
          Text(
              text = "#${comparisonRun?.id}: ${UnitConverter.formatSpeed(it.speedKmh, useMph)}",
              color = Color(0xFF00B0FF),
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace
          )
      }
  }

  Slider(
      value = cursorFraction,
      onValueChange = { cursorFraction = it },
      modifier = Modifier.fillMaxWidth()
  )
        }
    }
}

@Composable
fun RunReplayPlayerCard(run: RunRecord, useMph: Boolean) {
    val trace = remember(run.id) { TelemetrySerializer.decodeTrace(run.telemetryJson) }
    var isPlaying by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }

    LaunchedEffect(isPlaying, trace.size) {
        while (isPlaying && trace.isNotEmpty()) {
  delay(80L)
  if (idx < trace.lastIndex) idx++ else isPlaying = false
        }
    }

    if (trace.isEmpty()) return
    val cur = trace[idx.coerceIn(0, trace.lastIndex)]

    Column(
        modifier = Modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(12.dp))
  .background(Color(0xFF141820))
  .padding(14.dp)
    ) {
        Row(
  modifier = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.SpaceBetween,
  verticalAlignment = Alignment.CenterVertically
        ) {
  Text(
      text = "INTERACTIVE RUN REPLAY (#${run.id})",
      color = Color(0xFFFFC400),
      fontWeight = FontWeight.Bold,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace
  )
  TextButton(onClick = {
      if (idx >= trace.lastIndex) idx = 0
      isPlaying = !isPlaying
  }) {
      Text(
          if (isPlaying) "PAUSE" else "PLAY REPLAY",
          color = Color(0xFF00E676),
          fontWeight = FontWeight.Bold
      )
  }
        }

        Row(
  modifier = Modifier.fillMaxWidth(),
  horizontalArrangement = Arrangement.SpaceAround
        ) {
  MetricPill("TIME", String.format(Locale.US, "%.2fs", cur.tMs / 1000.0))
  MetricPill("SPEED", UnitConverter.formatSpeed(cur.speedKmh, useMph))
  MetricPill("DIST", UnitConverter.formatDist(cur.distM, useMph))
  MetricPill("LONG G", String.format(Locale.US, "%.2fG", cur.longG))
  if (cur.rpm > 0) MetricPill("OBD RPM", "${cur.rpm}")
        }

        Slider(
  value = idx.toFloat(),
  onValueChange = { idx = it.toInt() },
  valueRange = 0f..(trace.lastIndex.coerceAtLeast(1)).toFloat()
        )
    }
}

@Composable
fun TrackCockpitGauges(
    uiState: TelemetryUiState,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val obd = uiState.obd
    val speedVal = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)

    Column(
        modifier = modifier
  .fillMaxWidth()
  .clip(RoundedCornerShape(14.dp))
  .background(Color(0xFF141820))
  .padding(14.dp)
    ) {
        val rpmRatio = (obd.rpm.toFloat() / settings.redlineRpm.toFloat()).coerceIn(0f, 1f)
        val shiftColor = when {
  rpmRatio >= 0.92f -> Color(0xFFFF1744)
  rpmRatio >= 0.75f -> Color(0xFFFFC400)
  else -> Color(0xFF00E676)
        }
        LinearProgressIndicator(
  progress = { rpmRatio },
  modifier = Modifier
      .fillMaxWidth()
      .height(10.dp)
      .clip(RoundedCornerShape(5.dp)),
  color = shiftColor,
  trackColor = Color(0xFF0B0E14)
        )

        Spacer(modifier = Modifier.height(10.dp))

        when (settings.gaugeLayout) {
  GaugeLayoutStyle.PRO_ANALOG_COCKPIT -> {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
      ) {
          RadialSweepGauge(
              title = "ENGINE RPM",
              valueText = "${obd.rpm}",
              unitText = "RPM",
              fraction = rpmRatio,
              accent = shiftColor
          )
          GForceBallWidget(longG = uiState.currentG, latG = uiState.lateralG)
          RadialSweepGauge(
              title = "GPS SPEED",
              valueText = String.format(Locale.US, "%.0f", speedVal),
              unitText = speedUnit,
              fraction = (uiState.currentSpeedKmh.toFloat() / 260f).coerceIn(0f, 1f),
              accent = Color(0xFF00B0FF)
          )
      }
      Spacer(modifier = Modifier.height(8.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
      ) {
          MetricPill("BOOST / MAP", String.format(Locale.US, "%+.1f PSI", obd.boostPsi))
          MetricPill("COOLANT", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph))
          MetricPill("IAT", UnitConverter.formatTemp(obd.intakeTempC.toDouble(), settings.useMph))
          MetricPill("THROTTLE", String.format(Locale.US, "%.0f%%", obd.throttlePct))
      }
  }

  GaugeLayoutStyle.SECTOR_DELTA_RACER -> {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
          Column {
              Text("CURRENT LAP (#${uiState.currentLapNumber})", color = Color.Gray, fontSize = 11.sp)
              Text(
                  text = formatMsToSec(uiState.currentLapMs),
                  color = Color.White,
                  fontSize = 38.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.Monospace
              )
          }
          Column(horizontalAlignment = Alignment.End) {
              val deltaColor = if (uiState.liveDeltaMs <= 0L) Color(0xFF00E676) else Color(0xFFFF5252)
              Text("BEST LAP DELTA", color = Color.Gray, fontSize = 11.sp)
              Text(
                  text = String.format(Locale.US, "%+.3fs", uiState.liveDeltaMs / 1000.0),
                  color = deltaColor,
                  fontSize = 26.sp,
                  fontWeight = FontWeight.Black,
                  fontFamily = FontFamily.Monospace
              )
          }
      }
      Spacer(modifier = Modifier.height(8.dp))
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
      ) {
          MetricPill("S1 SPLIT", formatNullableMs(uiState.lastSector1Ms))
          MetricPill("S2 SPLIT", formatNullableMs(uiState.lastSector2Ms))
          MetricPill("S3 SPLIT", formatNullableMs(uiState.lastSector3Ms))
          MetricPill("SPEED", String.format(Locale.US, "%.1f %s", speedVal, speedUnit))
      }
  }

  GaugeLayoutStyle.OBD_MULTI_GRID -> {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              ObdGridBox("RPM", "${obd.rpm}", Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              ObdGridBox(speedUnit, String.format(Locale.US, "%.1f", speedVal), Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              ObdGridBox("BOOST", String.format(Locale.US, "%+.1f PSI", obd.boostPsi), Modifier.weight(1f))
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              ObdGridBox("ECT", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph), Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              ObdGridBox("IAT", UnitConverter.formatTemp(obd.intakeTempC.toDouble(), settings.useMph), Modifier.weight(1f))
              Spacer(Modifier.width(8.dp))
              ObdGridBox("TPS / VOLT", String.format(Locale.US, "%.0f%% • %.1fV", obd.throttlePct, obd.batteryVolts), Modifier.weight(1f))
          }
      }
  }
        }
    }
}

@Composable
private fun RadialSweepGauge(
    title: String,
    valueText: String,
    unitText: String,
    fraction: Float,
    accent: Color
) {
    Box(modifier = Modifier.size(115.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
  val stroke = 10.dp.toPx()
  drawArc(
      color = Color(0xFF263238),
      startAngle = 135f,
      sweepAngle = 270f,
      useCenter = false,
      style = Stroke(width = stroke, cap = StrokeCap.Round),
      size = Size(size.width, size.height)
  )
  drawArc(
      color = accent,
      startAngle = 135f,
      sweepAngle = 270f * fraction.coerceIn(0f, 1f),
      useCenter = false,
      style = Stroke(width = stroke, cap = StrokeCap.Round),
      size = Size(size.width, size.height)
  )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
  Text(title, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
  Text(
      valueText,
      color = Color.White,
      fontSize = 22.sp,
      fontWeight = FontWeight.Black,
      fontFamily = FontFamily.Monospace
  )
  Text(unitText, color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun GForceBallWidget(longG: Double, latG: Double) {
    Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
  val r = size.minDimension / 2f
  val center = Offset(size.width / 2f, size.height / 2f)
  drawCircle(Color(0xFF0B0E14), radius = r, center = center)
  drawCircle(Color(0xFF263238), radius = r, center = center, style = Stroke(width = 2f))
  drawCircle(Color(0xFF263238), radius = r * 0.5f, center = center, style = Stroke(width = 1.5f))
  drawLine(Color(0xFF263238), Offset(0f, center.y), Offset(size.width, center.y))
  drawLine(Color(0xFF263238), Offset(center.x, 0f), Offset(center.x, size.height))

  val maxG = 1.5f
  val bx = (center.x + (latG.toFloat() / maxG) * r).coerceIn(6f, size.width - 6f)
  val by = (center.y - (longG.toFloat() / maxG) * r).coerceIn(6f, size.height - 6f)
  drawCircle(Color(0xFFFF1744), radius = 7.dp.toPx(), center = Offset(bx, by))
        }
    }
}

@Composable
private fun ObdGridBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
  .clip(RoundedCornerShape(8.dp))
  .background(Color(0xFF0B0E14))
  .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(
  value,
  color = Color(0xFF00E676),
  fontSize = 16.sp,
  fontWeight = FontWeight.Black,
  fontFamily = FontFamily.Monospace
        )
    }
}
