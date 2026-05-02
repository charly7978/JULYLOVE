package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.domain.MeasurementState
import com.forensicppg.monitor.domain.VitalReading

@Composable
fun FullScreenMonitorScreen(
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02070A))
    ) {
        TopStatusBar(reading = reading, calibrated = calibration != null, running = running)

        Row(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(6.dp)) {
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
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VitalsColumn(reading, fps)
                ArrhythmiaEventOverlay(reading, modifier = Modifier.fillMaxWidth())
                CameraDiagnosticsPanel(
                    capabilities = caps,
                    config = cameraCfg,
                    fpsActual = fps,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

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
private fun TopStatusBar(reading: VitalReading, calibrated: Boolean, running: Boolean) {
    val color = when (reading.state) {
        MeasurementState.MEASURING -> Color(0xFF22FFAA)
        MeasurementState.WARMUP -> Color(0xFFFFDD22)
        MeasurementState.DEGRADED -> Color(0xFFFFAA22)
        MeasurementState.CALIBRATION_REQUIRED -> Color(0xFFFFAA22)
        MeasurementState.CONTACT_PARTIAL -> Color(0xFFFFAA22)
        MeasurementState.NO_CONTACT -> Color(0xFF8899AA)
        MeasurementState.INVALID -> Color(0xFFFF3344)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0F13))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MONITOR PPG FORENSE v1",
                color = Color(0xFF22FFAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(reading.state.labelEs(),
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold)
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
    val measuring = reading.state.allowsMetrics
    val bpmStr = reading.bpm?.takeIf { measuring && reading.bpmConfidence >= 0.35 }?.let { "%.0f".format(it) } ?: "--"
    val spo2Str = reading.spo2?.takeIf { measuring }?.let { "%.1f".format(it) } ?: "--"
    val rrStr = reading.rrMs?.takeIf { measuring }?.let { "%.0f".format(it) } ?: "--"
    val piStr = "%.2f".format(reading.perfusionIndex)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            VitalTile(
                label = "BPM",
                value = bpmStr,
                unit = "/min",
                color = Color(0xFF22FFAA),
                modifier = Modifier.weight(1f),
                subtitle = if (measuring && reading.bpmConfidence > 0.0)
                    "conf: ${"%.0f%%".format(reading.bpmConfidence * 100)}"
                else null
            )
            VitalTile(
                label = "SpO₂",
                value = spo2Str,
                unit = "%",
                color = if (reading.spo2 != null) Color(0xFF22FFAA) else Color(0xFFFFAA22),
                modifier = Modifier.weight(1f),
                subtitle = if (reading.spo2 == null && measuring) "req. calibración" else null
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
            colors = ButtonDefaults.buttonColors(containerColor = if (calibrated) Color(0xFF4488CC) else Color(0xFFFFAA22))
        ) { Text("CALIBRAR", color = Color.Black, fontFamily = FontFamily.Monospace) }
        Button(
            onClick = onExport,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4488CC))
        ) { Text("EXPORTAR", color = Color.White, fontFamily = FontFamily.Monospace) }
    }
}
