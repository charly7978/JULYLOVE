package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.camera.CameraCapabilities
import com.forensicppg.monitor.camera.CameraSessionConfig
import com.forensicppg.monitor.domain.DiagnosticsSnapshot

@Composable
fun CameraDiagnosticsPanel(
    capabilities: CameraCapabilities?,
    config: CameraSessionConfig?,
    fpsActual: Double,
    diagnostics: DiagnosticsSnapshot?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF070B0E), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x5522FFAA), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                "DIAGNÓSTICO CÁMARA",
                color = Color(0xFF22FFAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Row { Labeled("CamId", config?.cameraId ?: capabilities?.cameraId ?: "—") }
            Row { Labeled("HW", capabilities?.let { CameraCapabilities.levelName(it.hardwareLevel) } ?: "—") }
            Row {
                Labeled("Manual", if (config?.manualControlApplied == true) "SÍ" else "PARCIAL")
                Spacer(Modifier.fillMaxWidth(0.05f))
                Labeled("Torch", if (config?.torchEnabled == true) "ON" else "OFF")
            }
            Row {
                Labeled("Exp", config?.manualExposureNs?.let { "${it / 1_000_000.0} ms" } ?: "auto")
                Spacer(Modifier.fillMaxWidth(0.05f))
                Labeled("ISO", config?.manualIso?.toString() ?: "auto")
            }
            Row {
                Labeled("FPS obj", config?.targetFpsRange?.let { "${it.first}-${it.second}" } ?: "—")
                Spacer(Modifier.fillMaxWidth(0.05f))
                Labeled("FPS real", "%.1f".format(fpsActual))
            }
            Row { Labeled("Resolución", config?.previewSize?.let { "${it.width}×${it.height}" } ?: "—") }

            if (diagnostics != null) {
                Spacer(Modifier.height(8.dp))
                Text("TELEMETRÍA CAPTURA", color = Color(0xFFFFAA22), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row { Labeled("FPS medido", "%.2f Hz".format(diagnostics.measuredFps)) }
                Row { Labeled("FPS esperado", diagnostics.targetFps.toString()) }
                Row {
                    Labeled("Frames perd.", diagnostics.frameDropCount.toString())
                    Spacer(Modifier.fillMaxWidth(0.05f))
                    Labeled("Jitter", "%.2f ms".format(diagnostics.frameJitterMeanMs))
                }
                Row { Labeled("Picos sí / no", "${diagnostics.peakConfirmedCountSession} / ${diagnostics.peakRejectedCountSession}") }
                diagnostics.lastExposureNs?.let { Row { Labeled("Últ. exp(ns)", it.toString()) } }
                diagnostics.lastIso?.let { Row { Labeled("Últ. ISO", it.toString()) } }
                diagnostics.hardwareLimitNote?.takeIf { it.isNotBlank() }?.let { Row { Labeled("HW límit.", it.take(96)) } }
                Row { Labeled("SpO₂ cal.", diagnostics.spo2CalibrationStatus.take(28)) }
                LabeledDigest("Último digest rechazo:", diagnostics.lastRejectionDigest.take(240))
                LabeledDigest("Ritmo:", diagnostics.rhythmDigest.take(180))
            }
        }
    }
}

@Composable
private fun LabeledDigest(label: String, value: String) {
    Spacer(Modifier.height(6.dp))
    Text(label, color = Color(0x99E6FFF4), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    Text(value, color = Color(0xFFDDEEFF), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
}

@Composable
private fun Labeled(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            color = Color(0x99E6FFF4),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}
