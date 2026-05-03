package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.forensicppg.monitor.ppg.ContactPpgLiteratureAnchors
import com.forensicppg.monitor.ppg.RoiGeometryPreset

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

    val roiGeometryPreset by viewModel.roiGeometryPreset.collectAsState()

    var showDiagnostics by remember { mutableStateOf(false) }
    var showFingerPlacementGuide by remember { mutableStateOf(true) }

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
                    if (showFingerPlacementGuide) {
                        FingerPlacementGuide(
                            onDismissRequest = { showFingerPlacementGuide = false },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                        ) {
                            TextButton(onClick = { showFingerPlacementGuide = true }) {
                                Text(
                                    "Guía dedo",
                                    color = Color(0xFF22FFAA),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                if (running) {
                    FingerContactCoach(reading, modifier = Modifier.fillMaxWidth())
                }
                RoiGeometryPresetSelectorRow(
                    canPersist = cameraCfg != null,
                    running = running,
                    selected = roiGeometryPreset,
                    onSelect = viewModel::setRoiGeometryPreset,
                    modifier = Modifier.fillMaxWidth()
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
                VitalsColumn(reading, fps, calibration != null)
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
                    roiPresetSummary = cameraCfg?.let { roiGeometryPreset.labelEs },
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
private fun VitalsColumn(reading: VitalReading, fps: Double, profileLoaded: Boolean) {
    val bpmOk =
        reading.bpmSmoothed != null &&
            reading.bpmConfidence >= 0.28 &&
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
        VitalReadingEvidenceFooter(
            reading = reading,
            bpmShown = bpmOk,
            spo2Shown = spo2Show,
            profileLoaded = profileLoaded
        )
    }
}

@Composable
private fun VitalReadingEvidenceFooter(
    reading: VitalReading,
    bpmShown: Boolean,
    spo2Shown: Boolean,
    profileLoaded: Boolean
) {
    val msg = reading.messagePrimary.trim()
    if (!bpmShown && msg.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Estado BPM (evidencial): $msg",
            color = Color(0xFFB8DDE6),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
    if (!spo2Shown) {
        Spacer(Modifier.height(6.dp))
        val spoilne =
            when {
                !profileLoaded ->
                    "SpO₂ % absoluto: requiere perfil contra oxímetro de referencia (clínica). " +
                        "Oximetría por ratio exige modelo sensor linealizado + ZLO (Wang et al. doi " +
                        "${ContactPpgLiteratureAnchors.XUAN_WANG_CALIBRATION_FRONT_DIG_HEALTH_2023_DOI}); " +
                        "esta app corrige sólo dentro de ese marco físico-metrológico."
                reading.validityState < PpgValidityState.PPG_VALID ->
                    "SpO₂: clase PPG evidencial inferior a «válido» — ver mensaje BPM / contacto estable."
                else ->
                    "SpO₂ no mostrado: ventana (~10 s) con perfusión/SQI/movimiento/clip no aptos para índice clínico guardado."
            }
        Text(
            spoilne,
            color = Color(0x99FFCCAA),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Reporte método cPPG reposo vs ECG: checklist Mather et al. doi:${ContactPpgLiteratureAnchors.MATHER_SCOPING_FRONT_DIG_HEALTH_2024_DOI} · " +
            ContactPpgLiteratureAnchors.MATHER_SCOPING_FRONT_DIG_HEALTH_2024_URL,
        color = Color(0x66AABBCC),
        fontFamily = FontFamily.Monospace,
        fontSize = 8.sp,
        modifier = Modifier.fillMaxWidth()
    )
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

@Composable
private fun RoiGeometryPresetSelectorRow(
    canPersist: Boolean,
    running: Boolean,
    selected: RoiGeometryPreset,
    onSelect: (RoiGeometryPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF081018), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            "Preset ROI vs posición flash / LED lateral",
            color = Color(0xFFB8E0CC),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RoiGeometryPreset.entries.forEach { preset ->
                FilterChip(
                    selected = preset == selected,
                    onClick = {
                        if (canPersist) onSelect(preset)
                    },
                    enabled = canPersist,
                    label = {
                        Text(
                            preset.chipLabelEs,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1A2830),
                        labelColor = Color(0xFFCCDDEE),
                        selectedContainerColor = Color(0xFF1B5E4A),
                        selectedLabelColor = Color(0xFFEEFFFA),
                        disabledContainerColor = Color(0xFF151A1E),
                        disabledLabelColor = Color(0xFF556677)
                    )
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !canPersist ->
                    "Iniciá una medición una vez para recordar esta cámara; luego cambiás preset cuando quieras."
                running ->
                    "Activo · ${selected.labelEs} · aplica fotograma a fotograma."
                else ->
                    "Preset guardado por equipo/cámara. El próximo INICIAR cargará este ajuste."
            },
            color = Color(0x88CCEECC),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 5
        )
    }
}
