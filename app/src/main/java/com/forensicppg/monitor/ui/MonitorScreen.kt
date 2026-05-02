package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.domain.VitalReading

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    onOpenCalibration: () -> Unit
) {
    val reading by viewModel.reading.collectAsState()
    val cameraCfg by viewModel.cameraConfig.collectAsState()
    val caps by viewModel.capabilities.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val running by viewModel.running.collectAsState()
    val exportMsg by viewModel.exportMessage.collectAsState()
    val calibration by viewModel.calibrationProfile.collectAsState()
    val audioOn by viewModel.feedbackAudioOn.collectAsState()
    val vibOn by viewModel.feedbackVibrationOn.collectAsState()

    var showDiagnostics by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(Color(0xFF02070A))
    ) {
        TopStatusBar(reading = reading, calibrated = calibration != null, running = running)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(6.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(0.66f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PpgWaveformCanvas(
                    sampleFlow = viewModel.samples,
                    beatFlow = viewModel.beats,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
                SignalQualityPanel(reading, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.fillMaxWidth(0.01f))
            Column(
                modifier = Modifier
                    .weight(0.34f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VitalsColumn(reading, fps)
                ArrhythmiaEventOverlay(reading, modifier = Modifier.fillMaxWidth())
                DiagnosticsToggleRow(
                    showDiagnostics = showDiagnostics,
                    onToggle = { showDiagnostics = !showDiagnostics }
                )
                CameraDiagnosticsPanel(
                    capabilities = caps,
                    config = cameraCfg,
                    fpsActual = fps,
                    diagnostics = if (showDiagnostics) reading.diagnostics else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        FeedbackRow(
            audioOn = audioOn,
            vibOn = vibOn,
            onAudio = viewModel::setFeedbackAudio,
            onVibration = viewModel::setFeedbackVibration
        )
        ControlBar(
            running = running,
            calibrated = calibration != null,
            onStart = { viewModel.start() },
            onStop = { viewModel.stop() },
            onCalibrate = onOpenCalibration,
            onExport = { viewModel.exportCurrentSession() }
        )
        exportMsg?.let {
            Box(Modifier.fillMaxWidth().background(Color(0xFF112428)).padding(8.dp)) {
                Text(
                    it,
                    color = Color(0xFF22FFAA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsToggleRow(showDiagnostics: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF070B0E), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = showDiagnostics,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFF22FFAA),
                uncheckedColor = Color(0xFF8899AA)
            )
        )
        Text(
            "Diagnóstico técnico (FPS, jitter, picos)",
            color = Color(0xFFAADDCC),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun FeedbackRow(
    audioOn: Boolean,
    vibOn: Boolean,
    onAudio: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF05080C))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Feedback latido:", color = Color(0xFF88BBAA), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = audioOn,
                onCheckedChange = onAudio,
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF22FFAA), uncheckedColor = Color(0xFF8899AA))
            )
            Text("Tono", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = vibOn,
                onCheckedChange = onVibration,
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF22FFAA), uncheckedColor = Color(0xFF8899AA))
            )
            Text("Vibración", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun TopStatusBar(reading: VitalReading, calibrated: Boolean, running: Boolean) {
    val color = when (reading.validityState) {
        PpgValidityState.BIOMETRIC_VALID, PpgValidityState.PPG_VALID -> Color(0xFF22FFAA)
        PpgValidityState.PPG_CANDIDATE -> Color(0xFFFFDD22)
        PpgValidityState.RAW_OPTICAL_ONLY -> Color(0xFFFFAA22)
        PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL -> Color(0xFFFF3344)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0F13))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MONITOR PPG FORENSE",
                color = Color(0xFF22FFAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                reading.validityState.labelEs,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.fillMaxWidth(0.02f))
            Text(
                if (calibrated) "CALIBRADO" else "SIN CALIBRACIÓN SpO₂",
                color = if (calibrated) Color(0xFF22FFAA) else Color(0xFFFFAA22),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.fillMaxWidth(0.02f))
            Text(
                if (running) "CAPTURA ACTIVA" else "CAPTURA INACTIVA",
                color = if (running) Color(0xFF22FFAA) else Color(0xFF8899AA),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun VitalsColumn(reading: VitalReading, fps: Double) {
    val bpmOk =
        reading.bpmSmoothed != null &&
            reading.bpmConfidence >= 0.35 &&
            reading.validityState.ordinal >= PpgValidityState.PPG_CANDIDATE.ordinal
    val bpmStr = reading.bpmSmoothed?.takeIf { bpmOk }?.let { "%.0f".format(it) } ?: "--"
    val spo2Show = reading.spo2 != null && reading.spo2Confidence >= 0.2
    val spo2Str = reading.spo2?.takeIf { spo2Show }?.let { "%.1f".format(it) } ?: "--"
    val rrStr = reading.rrMs?.let { "%.0f".format(it) } ?: "--"
    val piStr = "%.2f".format(reading.perfusionIndex)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalTile(
                label = "BPM",
                value = bpmStr,
                unit = "/min",
                color = Color(0xFF22FFAA),
                modifier = Modifier.weight(1f),
                subtitle = if (reading.bpmConfidence > 0.0)
                    "conf: ${"%.0f%%".format(reading.bpmConfidence * 100)}"
                else null
            )
            VitalTile(
                label = "SpO₂",
                value = spo2Str,
                unit = "%",
                color = if (reading.spo2 != null && spo2Show) Color(0xFF22FFAA) else Color(0xFFFFAA22),
                modifier = Modifier.weight(1f),
                subtitle = if (!spo2Show && reading.validityState.ordinal >= PpgValidityState.PPG_VALID.ordinal)
                    reading.spo2CalibrationStatus.takeIf { it.isNotBlank() } ?: "req. calibración"
                else null
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalTile("RR", rrStr, "ms", Color(0xFFAACCEE), modifier = Modifier.weight(1f))
            VitalTile("PI", piStr, "%", Color(0xFFAACCEE), modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalTile("FPS", "%.1f".format(fps), "", Color(0xFFAACCEE), modifier = Modifier.weight(1f))
            VitalTile("MOV.", "%.2f".format(reading.motionScore), "", Color(0xFFAACCEE), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlBar(
    running: Boolean,
    calibrated: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCalibrate: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF070B0E))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Button(
            onClick = if (running) onStop else onStart,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFFF3344) else Color(0xFF22FFAA)
            )
        ) { Text(if (running) "DETENER" else "INICIAR", color = Color.Black, fontFamily = FontFamily.Monospace) }
        Button(
            onClick = onCalibrate,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (calibrated) Color(0xFF4488CC) else Color(0xFFFFAA22)
            )
        ) { Text("CALIBRAR", color = Color.Black, fontFamily = FontFamily.Monospace) }
        Button(
            onClick = onExport,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4488CC))
        ) { Text("EXPORTAR", color = Color.White, fontFamily = FontFamily.Monospace) }
    }
}
