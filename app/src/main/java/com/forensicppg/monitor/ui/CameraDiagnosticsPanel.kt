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

@Composable
fun CameraDiagnosticsPanel(
    capabilities: CameraCapabilities?,
    config: CameraSessionConfig?,
    fpsActual: Double,
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
        }
    }
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
