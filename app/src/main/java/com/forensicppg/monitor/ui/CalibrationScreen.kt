package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CalibrationScreen(
    viewModel: MonitorViewModel,
    onClose: () -> Unit
) {
    val reading by viewModel.reading.collectAsState()
    val profile by viewModel.calibrationProfile.collectAsState()
    val sensorZloStatus by viewModel.sensorZloStatus.collectAsState()
    val monitoring by viewModel.running.collectAsState()
    var refSpo2 by remember { mutableStateOf("") }
    var pendingCount by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text("CALIBRACIÓN FORENSE SpO₂",
            color = Color(0xFF22FFAA),
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Para mostrar un valor absoluto de SpO₂ este dispositivo debe calibrarse " +
                "contra un oxímetro de referencia (clase médica). Coloque ambos en simultáneo " +
                "y capture al menos 3 puntos con distintas condiciones de saturación.",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Perfil activo: ${profile?.profileId ?: "Ninguno"}",
            color = if (profile != null) Color(0xFF22FFAA) else Color(0xFFFFAA22),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Offset ZLO (nivel digital por modelo/cámara, Wang-style)",
            color = Color(0xFFFFAA22),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Cubra LED+lente con material opaco y mantenga quieto (~2 s) " +
                "para mediana en oscuridad (mejor que sólo tabla literatura).",
            color = Color(0xAACCFFEE),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = sensorZloStatus.ifBlank {
                if (monitoring) "Pulse «Iniciar ZLO oscuro» con tapón FIRME sobre el LED." else "Inicie monitor para usar captura ZLO."
            },
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.startSensorZloDarkHarvest() },
                enabled = monitoring,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Text("Iniciar ZLO oscuro", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Button(
                onClick = { viewModel.abortSensorZloDarkHarvest() },
                enabled = monitoring,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444))
            ) {
                Text("Cancelar", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Button(
                onClick = { viewModel.revertSensorZloToLiterature() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF883333))
            ) {
                Text("Ancla lit.", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Estado actual", color = Color(0xFF22FFAA), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Text("SQI: ${"%.2f".format(reading.sqi)}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Perfusion Index: ${"%.2f".format(reading.perfusionIndex)}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Motion Score: ${"%.2f".format(reading.motionScore)}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Validez: ${reading.validityState.labelEs}", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = refSpo2,
            onValueChange = { refSpo2 = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("SpO₂ de referencia (oxímetro)", color = Color(0xAACCFFEE)) },
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF0A1014),
                unfocusedContainerColor = Color(0xFF0A1014)
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val v = refSpo2.toDoubleOrNull()
                    if (v != null && v in 70.0..100.0) {
                        viewModel.captureCalibrationPoint(v)
                        pendingCount = viewModel.pendingCalibrationCount()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22FFAA))
            ) {
                Text("Capturar punto (${pendingCount})", color = Color.Black)
            }
            Button(
                onClick = {
                    viewModel.applyCalibration()
                    pendingCount = viewModel.pendingCalibrationCount()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAA22))
            ) { Text("Guardar perfil", color = Color.Black) }
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333844))
            ) { Text("Cerrar", color = Color.White) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "La app jamás mostrará un SpO₂ absoluto sin un perfil validado. " +
                "Si un punto se captura con SQI < 0.55, movimiento alto o perfusión baja, será rechazado.",
            color = Color(0xAACCFFEE),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}
