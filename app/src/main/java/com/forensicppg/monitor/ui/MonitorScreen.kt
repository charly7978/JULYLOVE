package com.forensicppg.monitor.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.domain.HypertensionRiskBand
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.domain.RhythmPatternHint
import com.forensicppg.monitor.domain.VitalReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitor PPG Forense — pantalla full-screen, edge-to-edge, estilo ICU.
 *
 * Layout vertical (sin scroll):
 *   STATUS HEADER  ·  WAVEFORM (peso flexible)  ·  HR/SpO2/RR  ·
 *   métricas secundarias (8 chips)  ·  STATUS LINE  ·  CONTROL BAR.
 *
 * Sin paneles laterales scroll, sin selector ROI: todo eso vive en
 * Calibración. Esta pantalla ES el monitor.
 */
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    onOpenCalibration: () -> Unit
) {
    val reading by viewModel.reading.collectAsState()
    val cameraCfg by viewModel.cameraConfig.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val running by viewModel.running.collectAsState()
    val exportMsg by viewModel.exportMessage.collectAsState()
    val calibration by viewModel.calibrationProfile.collectAsState()

    var showGuide by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02060A))
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusHeader(reading = reading, running = running, calibrated = calibration != null)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                PpgWaveformCanvas(
                    sampleFlow = viewModel.samples,
                    beatFlow = viewModel.beats,
                    modifier = Modifier.fillMaxSize()
                )
                if (!running) NoSignalOverlay()
            }

            PrimaryVitals(reading = reading, calibrated = calibration != null)
            SecondaryVitals(reading = reading, fps = fps, cameraOk = cameraCfg != null)
            StatusLine(reading = reading, running = running)
            ControlBar(
                running = running,
                calibrated = calibration != null,
                onStart = { runCatching { viewModel.start() } },
                onStop = { runCatching { viewModel.stop() } },
                onCalibrate = onOpenCalibration,
                onExport = { runCatching { viewModel.exportCurrentSession() } },
                onShowGuide = { showGuide = true }
            )
            exportMsg?.let { msg ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F2024))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        msg,
                        color = Color(0xFF22FFAA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }

        if (showGuide) {
            FingerPlacementOverlay(onContinue = { showGuide = false })
        }
    }
}

@Composable
private fun StatusHeader(reading: VitalReading, running: Boolean, calibrated: Boolean) {
    val stateColor = stateColor(reading.validityState)
    val now = remember(reading) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090C))
            .border(width = 0.6.dp, color = Color(0xFF1B2C36))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "MONITOR PPG · FORENSE",
            color = Color(0xFF22FFAA),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.width(10.dp))
        StatusDot(if (running) Color(0xFFFF3355) else Color(0xFF445566), pulsing = running)
        Spacer(Modifier.width(4.dp))
        Text(
            if (running) "REC" else "STOP",
            color = if (running) Color(0xFFFF7788) else Color(0xFF8899AA),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(
            reading.validityState.labelEs,
            color = stateColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (calibrated) "CALIBRADO" else "NO CALIBR.",
            color = if (calibrated) Color(0xFF22FFAA) else Color(0xFFFFAA22),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            now,
            color = Color(0xFF88CCDD),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "rec")
    val alpha by transition.animateFloat(
        initialValue = if (pulsing) 0.3f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
        label = "rec-alpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = if (pulsing) alpha else 1f))
    )
}

@Composable
private fun PrimaryVitals(reading: VitalReading, calibrated: Boolean) {
    val bpmShown = reading.bpmSmoothed != null && reading.bpmConfidence >= 0.20 &&
        reading.validityState.ordinal >= PpgValidityState.PPG_CANDIDATE.ordinal
    val bpmStr = reading.bpmSmoothed?.takeIf { bpmShown }?.let { "%.0f".format(it) } ?: "--"
    val bpmColor = if (bpmShown) Color(0xFF22FFAA) else Color(0xFF455565)

    val spo2Shown = reading.spo2 != null && reading.spo2Confidence >= 0.20
    val spo2Str = reading.spo2?.takeIf { spo2Shown }?.let { "%.1f".format(it) } ?: "--"
    val spo2Color = when {
        !spo2Shown -> Color(0xFF455565)
        calibrated -> Color(0xFF44E0FF)
        else -> Color(0xFFFFD555)
    }

    val rrStr = reading.rrMs?.let { "%.0f".format(it) } ?: "--"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF050A0E))
            .border(width = 0.6.dp, color = Color(0xFF1B2C36))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1.4f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "HR",
                    color = bpmColor.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "lpm",
                    color = bpmColor.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
                Spacer(Modifier.weight(1f))
                if (bpmShown) HeartPulse(color = bpmColor, bpm = reading.bpmSmoothed ?: 0.0)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    bpmStr,
                    color = bpmColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        "conf ${"%.0f%%".format(reading.bpmConfidence * 100)}",
                        color = bpmColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                    Text(
                        "${reading.beatsDetected} beat",
                        color = bpmColor.copy(alpha = 0.55f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                }
            }
        }

        VerticalSeparator()

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SpO₂",
                    color = spo2Color.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Text("%", color = spo2Color.copy(alpha = 0.55f), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    spo2Str,
                    color = spo2Color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        if (calibrated) "clínico" else "provisional",
                        color = spo2Color.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp
                    )
                    reading.spo2RatioR?.let { ror ->
                        Text(
                            "R %.2f".format(ror),
                            color = spo2Color.copy(alpha = 0.55f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        VerticalSeparator()

        Column(modifier = Modifier.weight(0.8f)) {
            Text(
                "RR ms",
                color = Color(0xFFAACCEE),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                rrStr,
                color = Color(0xFFE0F0FA),
                fontFamily = FontFamily.Monospace,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                reading.rhythmPatternHint.labelEs.uppercase(),
                color = Color(0xAACCDDEE),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun HeartPulse(color: Color, bpm: Double) {
    val periodMs = if (bpm > 30) (60000.0 / bpm).toInt().coerceIn(280, 2000) else 1000
    val transition = rememberInfiniteTransition(label = "heart")
    val s by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs / 2, easing = androidx.compose.animation.core.EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heart-scale"
    )
    Box(
        modifier = Modifier
            .scale(s)
            .size(18.dp)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.radialGradient(colors = listOf(color, color.copy(alpha = 0.0f)))
            )
    )
}

@Composable
private fun SecondaryVitals(reading: VitalReading, fps: Double, cameraOk: Boolean) {
    val sdnn = reading.rrSdnnMs?.let { "%.0f".format(it) } ?: "--"
    val rmssd = reading.rmssdMs?.let { "%.0f".format(it) } ?: "--"
    val pi = "%.2f".format(reading.perfusionIndex)
    val mot = "%.2f".format(reading.motionScore)
    val sqi = "%.0f".format(reading.sqi * 100)
    val rhythm = when (reading.rhythmPatternHint) {
        RhythmPatternHint.REGULAR -> "REG"
        RhythmPatternHint.IRREGULAR -> "IRR"
        RhythmPatternHint.SUSPECT_ARRHYTHMIA -> "ARR?"
        RhythmPatternHint.INSUFFICIENT_DATA -> "—"
    }
    val rhythmColor = when (reading.rhythmPatternHint) {
        RhythmPatternHint.REGULAR -> Color(0xFF22FFAA)
        RhythmPatternHint.IRREGULAR -> Color(0xFFFFAA22)
        RhythmPatternHint.SUSPECT_ARRHYTHMIA -> Color(0xFFFF3344)
        RhythmPatternHint.INSUFFICIENT_DATA -> Color(0xFF8899AA)
    }
    val cribLabel = reading.hypertensionRisk?.let { hypertensionShortLabel(it) } ?: "—"
    val cribColor = hypertensionColor(reading.hypertensionRisk)
    val fpsColor = if (cameraOk && fps > 12) Color(0xFFAACCEE) else Color(0xFFFF7766)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF030708))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MetricChip("SDNN", sdnn, "ms", Color(0xFFAACCEE), Modifier.weight(1f))
        MetricChip("RMSSD", rmssd, "ms", Color(0xFFAACCEE), Modifier.weight(1f))
        MetricChip("PI", pi, "%", Color(0xFFFFCC55), Modifier.weight(1f))
        MetricChip("MOV", mot, "", Color(0xFFFF9999), Modifier.weight(1f))
        MetricChip("SQI", sqi, "%", sqiColor(reading.sqi), Modifier.weight(1f))
        MetricChip("FPS", "%.0f".format(fps), "", fpsColor, Modifier.weight(1f))
        MetricChip("RIT", rhythm, "", rhythmColor, Modifier.weight(1f))
        MetricChip("CRIB", cribLabel, "", cribColor, Modifier.weight(1.2f))
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0A1014), RoundedCornerShape(4.dp))
            .border(0.8.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            color = color.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    color = color.copy(alpha = 0.65f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun StatusLine(reading: VitalReading, running: Boolean) {
    val msg = if (!running) "Detenido — pulse INICIAR para abrir la cámara y comenzar la captura"
    else reading.messagePrimary.ifBlank { reading.validityState.labelEs }
    val color = stateColor(reading.validityState)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF050A0E))
            .border(0.6.dp, Color(0xFF1B2C36))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            msg,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
    }
}

@Composable
private fun ControlBar(
    running: Boolean,
    calibrated: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrate: () -> Unit,
    onExport: () -> Unit,
    onShowGuide: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF06090C))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ControlButton(
            label = if (running) "DETENER" else "INICIAR",
            color = if (running) Color(0xFFFF4455) else Color(0xFF22FFAA),
            modifier = Modifier.weight(1f),
            onClick = if (running) onStop else onStart
        )
        ControlButton(
            label = if (calibrated) "CALIBRACIÓN OK" else "CALIBRAR",
            color = if (calibrated) Color(0xFF44A0FF) else Color(0xFFFFAA22),
            modifier = Modifier.weight(1f),
            onClick = onCalibrate
        )
        ControlButton(
            label = "EXPORTAR",
            color = Color(0xFF4488CC),
            modifier = Modifier.weight(0.9f),
            onClick = onExport
        )
        ControlButton(
            label = "GUÍA",
            color = Color(0xFF888888),
            modifier = Modifier.weight(0.6f),
            onClick = onShowGuide
        )
    }
}

@Composable
private fun ControlButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(0.8.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .clickable { runCatching { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.Black,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun NoSignalOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88020608)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "MONITOR DETENIDO",
                color = Color(0xFFFFAA22),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pulse INICIAR para abrir la cámara",
                color = Color(0xFFCCDDEE),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(0.8.dp)
            .background(Color(0xFF1B2C36))
    )
}

private fun stateColor(state: PpgValidityState): Color = when (state) {
    PpgValidityState.BIOMETRIC_VALID -> Color(0xFF22FFAA)
    PpgValidityState.PPG_VALID -> Color(0xFF22FFAA)
    PpgValidityState.PPG_CANDIDATE -> Color(0xFFFFDD22)
    PpgValidityState.RAW_OPTICAL_ONLY -> Color(0xFFFFAA22)
    PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL -> Color(0xFFFF3344)
}

private fun sqiColor(v: Double): Color = when {
    v >= 0.7 -> Color(0xFF22FFAA)
    v >= 0.45 -> Color(0xFFAAEE55)
    v >= 0.25 -> Color(0xFFFFAA22)
    else -> Color(0xFFFF3344)
}

private fun hypertensionShortLabel(b: HypertensionRiskBand): String = when (b) {
    HypertensionRiskBand.NORMOTENSE -> "NORMO"
    HypertensionRiskBand.BORDERLINE -> "LIMIT"
    HypertensionRiskBand.HYPERTENSIVE_PATTERN -> "ALTA"
    HypertensionRiskBand.UNCERTAIN -> "INDET"
}

private fun hypertensionColor(b: HypertensionRiskBand?): Color = when (b) {
    HypertensionRiskBand.NORMOTENSE -> Color(0xFF22FFAA)
    HypertensionRiskBand.BORDERLINE -> Color(0xFFFFAA22)
    HypertensionRiskBand.HYPERTENSIVE_PATTERN -> Color(0xFFFF3344)
    HypertensionRiskBand.UNCERTAIN, null -> Color(0xFF8899AA)
}
