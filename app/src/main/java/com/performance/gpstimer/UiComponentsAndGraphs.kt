package com.performance.gpstimer

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.*

/**
 * Renders user-uploaded Vehicle Image from URI or sleek fallback Motorsport Icon
 */
@Composable
fun VehicleAvatarBadge(
    photoUri: String,
    sizeDp: Dp = 48.dp,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val bitmap by remember(photoUri) {
        mutableStateOf(
            if (photoUri.isNotBlank()) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (_: Exception) { null }
            } else null
        )
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Vehicle Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = "Car",
                tint = Color(0xFF00E676),
                modifier = Modifier.size(sizeDp * 0.58f)
            )
        }
    }
}

/**
 * Interactive Esri World Satellite Imagery Map for Track Mode (Leaflet WebView)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SatelliteTrackMapView(
    currentLat: Double,
    currentLon: Double,
    activeTrack: TrackLayout?,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val html = remember {
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #07090D; }
            .pin-label { background: rgba(0,0,0,0.8); color: #00E676; border: 1px solid #00E676; border-radius: 4px; padding: 2px 5px; font-family: monospace; font-size: 10px; font-weight: bold; }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            var map = L.map('map', { zoomControl: true }).setView([-31.67432, 115.78685], 16);
            L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
              maxZoom: 19,
              attribution: 'Esri Satellite'
            }).addTo(map);

            var carMarker = L.circleMarker([-31.67432, 115.78685], {
              radius: 8, color: '#FFFFFF', weight: 2, fillColor: '#00E676', fillOpacity: 1.0
            }).addTo(map);

            var pinLayer = L.layerGroup().addTo(map);
            var firstCenter = true;

            function updateTelemetryMap(lat, lon, sLat, sLon, s1Lat, s1Lon, s2Lat, s2Lon, fLat, fLon) {
              if (lat !== 0 && lon !== 0) {
                carMarker.setLatLng([lat, lon]);
                if (firstCenter) { map.setView([lat, lon], 16); firstCenter = false; }
              }
              pinLayer.clearLayers();
              var pts = [];
              function addPin(la, lo, label, col) {
                if (la !== 0 && lo !== 0) {
                  L.circleMarker([la, lo], { radius: 7, color: col, weight: 2, fillColor: col, fillOpacity: 0.85 })
                    .bindTooltip(label, { permanent: true, direction: 'top', className: 'pin-label' })
                    .addTo(pinLayer);
                  pts.push([la, lo]);
                }
              }
              addPin(sLat, sLon, 'START', '#00E676');
              addPin(s1Lat, s1Lon, 'SEC 1', '#00B0FF');
              addPin(s2Lat, s2Lon, 'SEC 2', '#FFC400');
              addPin(fLat, fLon, 'FINISH', '#FF5252');
              if (pts.length > 1) {
                L.polyline(pts, { color: '#00B0FF', weight: 3, dashArray: '6, 6' }).addTo(pinLayer);
              }
            }
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    LaunchedEffect(currentLat, currentLon, activeTrack) {
        val t = activeTrack
        val js = String.format(
            Locale.US,
            "javascript:if(typeof updateTelemetryMap==='function'){updateTelemetryMap(%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f);}",
            currentLat, currentLon,
            t?.startLat ?: 0.0, t?.startLon ?: 0.0,
            t?.sector1Lat ?: 0.0, t?.sector1Lon ?: 0.0,
            t?.sector2Lat ?: 0.0, t?.sector2Lon ?: 0.0,
            t?.finishLat ?: 0.0, t?.finishLon ?: 0.0
        )
        webViewRef?.evaluateJavascript(js, null)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://server.arcgisonline.com", html, "text/html", "UTF-8", null)
                webViewRef = this
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
    )
}

/**
 * Dedicated Multi-Style Motorsport Dashboards (GT3 MoTeC, Twin Analog, Cyber Street, Custom 6-Slot)
 */
@Composable
fun MotorsportDashboardView(
    uiState: TelemetryUiState,
    settings: AppSettings,
    onSelectStyle: (DashStyle) -> Unit,
    onUpdateSlotMetric: (Int, DashMetricType) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingSlotIndex by remember { mutableStateOf<Int?>(null) }

    val speedVal = UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph)
    val speedUnit = UnitConverter.speedUnit(settings.useMph)
    val obd = uiState.obd
    val effectiveRpm = if (obd.connected && obd.rpm > 0) obd.rpm else (uiState.currentSpeedKmh * 38).toInt().coerceAtMost(settings.redlineRpm)
    val rpmRatio = (effectiveRpm.toFloat() / settings.redlineRpm.toFloat()).coerceIn(0f, 1f)

    // Estimate gear from speed/RPM ratio
    val estimatedGear = when {
        uiState.currentSpeedKmh < 2.0 -> "N"
        uiState.currentSpeedKmh < 38.0 -> "1"
        uiState.currentSpeedKmh < 72.0 -> "2"
        uiState.currentSpeedKmh < 112.0 -> "3"
        uiState.currentSpeedKmh < 155.0 -> "4"
        uiState.currentSpeedKmh < 205.0 -> "5"
        else -> "6"
    }

    if (editingSlotIndex != null) {
        AlertDialog(
            onDismissRequest = { editingSlotIndex = null },
            title = { Text("SELECT GAUGE SENSOR FOR SLOT #${editingSlotIndex!! + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashMetricType.entries.forEach { metric ->
                        Button(
                            onClick = {
                                onUpdateSlotMetric(editingSlotIndex!!, metric)
                                editingSlotIndex = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Text(metric.label, color = Color(0xFF00E676), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Style Selector Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DashStyle.entries.forEach { style ->
                val active = settings.dashStyle == style
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0xFF00E676) else Color(0xFF1E293B))
                        .clickable { onSelectStyle(style) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (style) {
                            DashStyle.GT3_MOTEC -> "GT3 PRO"
                            DashStyle.ANALOG_TWIN -> "ANALOG"
                            DashStyle.CYBER_STREET -> "CYBER"
                            DashStyle.CUSTOM_6_SLOT -> "CUSTOM"
                        },
                        color = if (active) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 14-LED Motorsport Shift Light Array across top of all dashboards
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0B0E14))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (i in 0 until 14) {
                val threshold = (i + 1) / 14f
                val lit = rpmRatio >= threshold
                val ledColor = when {
                    i < 6 -> Color(0xFF00E676)
                    i < 10 -> Color(0xFFFFC400)
                    else -> Color(0xFFFF1744)
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (lit) ledColor else Color(0xFF1C222B))
                        .border(1.dp, if (lit) Color.White else Color(0xFF263238), CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (settings.dashStyle) {
            DashStyle.GT3_MOTEC -> {
                // Authentic GT3 MoTeC / Bosch DDU Layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D1118))
                        .border(2.dp, Color(0xFF263238), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    // Top RPM Bar with numeric RPM
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("RPM $effectiveRpm", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("REDLINE ${settings.redlineRpm}", color = Color(0xFFFF5252), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { rpmRatio },
                        modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (rpmRatio > 0.88f) Color(0xFFFF1744) else Color(0xFF00E676),
                        trackColor = Color(0xFF1C222B)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: ECT & IAT
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Gt3Cell("COOLANT", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph), Color(0xFF00B0FF))
                            Gt3Cell("INTAKE", UnitConverter.formatTemp(obd.intakeTempC.toDouble(), settings.useMph), Color(0xFF00E676))
                            Gt3Cell("LONG G", String.format(Locale.US, "%+.2f G", uiState.currentG), Color(0xFFFFC400))
                        }

                        // Center GT3 Gear & Speed Box
                        Column(
                            modifier = Modifier
                                .weight(1.3f)
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF141B26))
                                .border(1.5.dp, Color(0xFF00E676).copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("GEAR", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                text = estimatedGear,
                                color = Color(0xFFFFC400),
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = String.format(Locale.US, "%.0f %s", speedVal, speedUnit),
                                color = Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "LAP ${formatMsToSec(uiState.currentLapMs)}",
                                color = Color(0xFF00B0FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Right Column: Boost, TPS, Volts
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Gt3Cell("MAP/BOOST", String.format(Locale.US, "%+.1f PSI", obd.boostPsi), Color(0xFFFF5252))
                            Gt3Cell("THROTTLE", String.format(Locale.US, "%.0f %%", obd.throttlePct), Color(0xFF00E676))
                            Gt3Cell("VOLTAGE", String.format(Locale.US, "%.1f V", obd.batteryVolts), Color(0xFF00B0FF))
                        }
                    }
                }
            }

            DashStyle.ANALOG_TWIN -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D1118))
                        .border(1.5.dp, Color(0xFF263238), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadialSweepGauge("TACHOMETER", "$effectiveRpm", "RPM", rpmRatio, Color(0xFFFF5252))
                        GForceBallWidget(longG = uiState.currentG, latG = uiState.lateralG)
                        RadialSweepGauge("SPEEDOMETER", String.format(Locale.US, "%.0f", speedVal), speedUnit, (uiState.currentSpeedKmh.toFloat() / 260f).coerceIn(0f, 1f), Color(0xFF00B0FF))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        MetricPill("BOOST", String.format(Locale.US, "%+.1f PSI", obd.boostPsi))
                        MetricPill("COOLANT", UnitConverter.formatTemp(obd.coolantTempC.toDouble(), settings.useMph))
                        MetricPill("THROTTLE", String.format(Locale.US, "%.0f%%", obd.throttlePct))
                        MetricPill("PEAK G", String.format(Locale.US, "%.2fG", uiState.peakG))
                    }
                }
            }

            DashStyle.CYBER_STREET -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D1118))
                        .border(1.5.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("DIGITAL VELOCITY", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                text = String.format(Locale.US, "%.1f", speedVal),
                                color = Color(0xFF00E676),
                                fontSize = 52.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(speedUnit, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Text("RPM: $effectiveRpm", color = Color(0xFFFFC400), fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Gt3Cell("60 FT SPLIT", formatNullableMs(uiState.latestRun?.time60ftMs), Color(0xFF00E676), Modifier.weight(1f))
                        Gt3Cell("0-100 / 0-60", formatNullableMs(uiState.latestRun?.time0to100Ms), Color(0xFF00B0FF), Modifier.weight(1f))
                        Gt3Cell("1/4 MILE ET", formatNullableMs(uiState.latestRun?.primaryTimeMs), Color(0xFFFFC400), Modifier.weight(1f))
                    }
                }
            }

            DashStyle.CUSTOM_6_SLOT -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0D1118))
                        .border(1.5.dp, Color(0xFF263238), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        "TAP ANY GAUGE SLOT BELOW TO CUSTOMISE SENSOR:",
                        color = Color(0xFF00B0FF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    for (row in 0..1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (col in 0..2) {
                                val slotIdx = row * 3 + col
                                val metric = settings.customDashSlots.getOrElse(slotIdx) { DashMetricType.GPS_SPEED }
                                val valueStr = formatDashMetricValue(metric, uiState, settings)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF141B26))
                                        .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
                                        .clickable { editingSlotIndex = slotIdx }
                                        .padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(metric.label, color = Color.Gray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(valueStr, color = Color(0xFF00E676), fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
                                }
                            }
                        }
                        if (row == 0) Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

private fun formatDashMetricValue(metric: DashMetricType, uiState: TelemetryUiState, settings: AppSettings): String {
    return when (metric) {
        DashMetricType.GPS_SPEED -> String.format(Locale.US, "%.1f", UnitConverter.speed(uiState.currentSpeedKmh, settings.useMph))
        DashMetricType.OBD_RPM -> "${uiState.obd.rpm}"
        DashMetricType.BOOST_PSI -> String.format(Locale.US, "%+.1f", uiState.obd.boostPsi)
        DashMetricType.COOLANT_TEMP -> UnitConverter.formatTemp(uiState.obd.coolantTempC.toDouble(), settings.useMph)
        DashMetricType.INTAKE_TEMP -> UnitConverter.formatTemp(uiState.obd.intakeTempC.toDouble(), settings.useMph)
        DashMetricType.THROTTLE_PCT -> String.format(Locale.US, "%.0f%%", uiState.obd.throttlePct)
        DashMetricType.BATTERY_VOLTS -> String.format(Locale.US, "%.1fV", uiState.obd.batteryVolts)
        DashMetricType.LONG_G -> String.format(Locale.US, "%+.2fG", uiState.currentG)
        DashMetricType.LAT_G -> String.format(Locale.US, "%+.2fG", uiState.lateralG)
        DashMetricType.DENSITY_ALT -> "${uiState.weather.densityAltitudeFt}ft"
    }
}

@Composable
private fun Gt3Cell(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141B26))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
fun NhraDragLightingTree(
    stageState: TreeStageState,
    treeType: TreeType,
    reactionMs: Long?,
    redLight: Boolean,
    compactLandscape: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bulbSize = if (compactLandscape) 26.dp else 32.dp
    val spacing = if (compactLandscape) 5.dp else 7.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0D1017))
            .border(1.5.dp, Color(0xFF263238), RoundedCornerShape(14.dp))
            .padding(if (compactLandscape) 8.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(treeType.label, color = Color(0xFF90A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(spacing))

        StagingBeamRow("PRE-STAGE", stageState != TreeStageState.OFF)
        Spacer(modifier = Modifier.height(4.dp))
        StagingBeamRow("STAGE", stageState.ordinal >= TreeStageState.STAGE.ordinal)
        Spacer(modifier = Modifier.height(spacing))

        val amber1Lit = stageState == TreeStageState.AMBER_1 || (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val amber2Lit = stageState == TreeStageState.AMBER_2 || (treeType.isSimultaneous && stageState == TreeStageState.AMBER_3)
        val amber3Lit = stageState == TreeStageState.AMBER_3

        TreeBulb(color = Color(0xFFFFB300), isLit = amber1Lit, sizeDp = bulbSize)
        Spacer(modifier = Modifier.height(spacing))
        TreeBulb(color = Color(0xFFFFB300), isLit = amber2Lit, sizeDp = bulbSize)
        Spacer(modifier = Modifier.height(spacing))
        TreeBulb(color = Color(0xFFFFB300), isLit = amber3Lit, sizeDp = bulbSize)
        Spacer(modifier = Modifier.height(spacing))
        TreeBulb(color = Color(0xFF00E676), isLit = stageState == TreeStageState.GREEN, sizeDp = bulbSize)
        Spacer(modifier = Modifier.height(spacing))
        TreeBulb(color = Color(0xFFFF1744), isLit = stageState == TreeStageState.RED_FOUL || redLight, sizeDp = bulbSize)
        Spacer(modifier = Modifier.height(spacing))

        val rtText = when {
            reactionMs != null && redLight -> String.format(Locale.US, "FOUL %+.3fs", reactionMs / 1000.0)
            reactionMs != null -> String.format(Locale.US, "R/T: %.3fs", reactionMs / 1000.0)
            else -> "R/T: --.---"
        }
        Text(rtText, color = if (redLight) Color(0xFFFF1744) else Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StagingBeamRow(label: String, isLit: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) {
                Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(if (isLit) Color(0xFFFFEA00) else Color(0xFF263238)))
            }
        }
    }
}

@Composable
private fun TreeBulb(color: Color, isLit: Boolean, sizeDp: Dp) {
    Box(
        modifier = Modifier
            .size(sizeDp)
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = if (comparisonRun != null) "TELEMETRY OVERLAY: #${primaryRun.id} (GREEN) vs #${comparisonRun.id} (CYAN)"
            else "VELOCITY & G-CURVE ANALYZER — PASS #${primaryRun.id}",
            color = Color(0xFF00B0FF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (traceA.size < 2) {
            Box(modifier = Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                Text("Complete a GPS pass to view high-rate velocity & G-force curves.", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            val maxTimeMs = max(traceA.last().tMs, if (traceB.isNotEmpty()) traceB.last().tMs else 1L).coerceAtLeast(1000L)
            val maxSpeed = max(traceA.maxOf { it.speedKmh }, if (traceB.isNotEmpty()) traceB.maxOf { it.speedKmh } else 100.0).coerceAtLeast(60.0)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
                    .background(Color(0xFF080B10), RoundedCornerShape(10.dp))
            ) {
                val w = size.width
                val h = size.height
                for (i in 1..3) {
                    val y = h * (i / 4f)
                    drawLine(Color(0xFF1E293B), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                fun drawSpeedPath(points: List<TelemetryPoint>, color: Color, fillAlpha: Float) {
                    if (points.size < 2) return
                    val path = Path()
                    val fillPath = Path()
                    points.forEachIndexed { idx, p ->
                        val x = (p.tMs.toFloat() / maxTimeMs.toFloat()) * w
                        val y = h - ((p.speedKmh / maxSpeed).toFloat() * (h * 0.86f)) - 6f
                        if (idx == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            path.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        if (idx == points.lastIndex) fillPath.lineTo(x, h)
                    }
                    drawPath(fillPath, brush = Brush.verticalGradient(listOf(color.copy(alpha = fillAlpha), Color.Transparent)))
                    drawPath(path, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
                drawSpeedPath(traceA, Color(0xFF00E676), 0.22f)
                if (traceB.isNotEmpty()) drawSpeedPath(traceB, Color(0xFF00B0FF), 0.14f)

                val cx = cursorFraction * w
                drawLine(Color.White.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
            }

            val targetMs = (cursorFraction * maxTimeMs).toLong()
            val ptA = traceA.minByOrNull { abs(it.tMs - targetMs) }
            val ptB = traceB.minByOrNull { abs(it.tMs - targetMs) }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.US, "T: %.2fs", targetMs / 1000.0), color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                ptA?.let {
                    Text("#${primaryRun.id}: ${UnitConverter.formatSpeed(it.speedKmh, useMph)} (${String.format(Locale.US, "%.2fG", it.longG)})", color = Color(0xFF00E676), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                ptB?.let {
                    Text("#${comparisonRun?.id}: ${UnitConverter.formatSpeed(it.speedKmh, useMph)}", color = Color(0xFF00B0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Slider(value = cursorFraction, onValueChange = { cursorFraction = it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun RadialSweepGauge(title: String, valueText: String, unitText: String, fraction: Float, accent: Color) {
    Box(modifier = Modifier.size(106.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            drawArc(Color(0xFF263238), 135f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(size.width, size.height))
            drawArc(accent, 135f, 270f * fraction.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(size.width, size.height))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.Gray, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Text(valueText, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
            Text(unitText, color = accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun GForceBallWidget(longG: Double, latG: Double) {
    Box(modifier = Modifier.size(86.dp), contentAlignment = Alignment.Center) {
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
            drawCircle(Color(0xFFFF1744), radius = 6.dp.toPx(), center = Offset(bx, by))
        }
    }
}
