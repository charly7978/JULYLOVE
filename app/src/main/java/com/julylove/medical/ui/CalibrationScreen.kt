package com.julylove.medical.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.julylove.medical.signal.Spo2Estimator
import com.julylove.medical.ui.theme.MedicalBlack
import com.julylove.medical.ui.theme.MedicalGreen
import com.julylove.medical.ui.theme.MedicalAmber
import com.julylove.medical.ui.theme.MedicalRed
import com.julylove.medical.ui.theme.MedicalCyan
import com.julylove.medical.ui.theme.MedicalTextGray
import com.julylove.medical.ui.theme.MedicalDarkGray
import com.julylove.medical.ui.theme.MedicalGrid

@Composable
fun CalibrationScreen(
    onBackToMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    var calibrationState by remember { mutableStateOf(CalibrationState.IDLE) }
    var referenceSpo2 by remember { mutableStateOf("") }
    var calibrationPoints by remember { mutableStateOf(listOf<CalibrationPoint>()) }
    var currentDeviceProfile by remember { mutableStateOf<DeviceCalibrationProfile?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MedicalBlack)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CALIBRACIÓN SpO₂",
                style = MaterialTheme.typography.headlineMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = onBackToMonitor,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MedicalDarkGray,
                    contentColor = MedicalTextGray
                )
            ) {
                Text("VOLVER")
            }
        }
        
        HorizontalDivider(color = MedicalGrid, thickness = 1.dp)
        
        // Device Information
        DeviceInfoCard(currentProfile = currentDeviceProfile)
        
        // Calibration Instructions
        CalibrationInstructionsCard(
            calibrationState = calibrationState,
            referenceSpo2 = referenceSpo2,
            onReferenceChange = { referenceSpo2 = it }
        )
        
        // Error Message
        if (errorMessage.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MedicalRed.copy(alpha = 0.1f))
            ) {
                Text(
                    text = errorMessage,
                    color = MedicalRed,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        
        // Calibration Points
        if (calibrationPoints.isNotEmpty()) {
            CalibrationPointsCard(points = calibrationPoints)
        }
        
        // Calibration Controls
        CalibrationControlsCard(
            calibrationState = calibrationState,
            referenceSpo2 = referenceSpo2,
            calibrationPoints = calibrationPoints,
            onStartCalibration = {
                if (referenceSpo2.isNotEmpty() && isValidSpo2(referenceSpo2)) {
                    calibrationState = CalibrationState.COLLECTING
                    errorMessage = ""
                } else {
                    errorMessage = "Valor SpO₂ inválido. Ingrese valor entre 70-100%"
                }
            },
            onStopCalibration = {
                calibrationState = CalibrationState.IDLE
            },
            onSaveProfile = { profile ->
                currentDeviceProfile = profile
                calibrationState = CalibrationState.COMPLETED
            },
            onClearPoints = {
                calibrationPoints = emptyList()
                calibrationState = CalibrationState.IDLE
            }
        )
        
        // Technical Information
        TechnicalInfoCard()
    }
}

@Composable
private fun DeviceInfoCard(currentProfile: DeviceCalibrationProfile?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalDarkGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "INFORMACIÓN DEL DISPOSITIVO",
                style = MaterialTheme.typography.titleMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Text("Modelo: ${android.os.Build.MODEL}", color = MedicalTextGray)
            Text("Fabricante: ${android.os.Build.MANUFACTURER}", color = MedicalTextGray)
            Text("Versión Android: ${android.os.Build.VERSION.RELEASE}", color = MedicalTextGray)
            
            currentProfile?.let { profile ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "PERFIL ACTIVO:",
                    color = MedicalGreen,
                    fontWeight = FontWeight.Bold
                )
                Text("Coeficiente A: ${"%.3f".format(profile.coefficientA)}", color = MedicalTextGray)
                Text("Coeficiente B: ${"%.3f".format(profile.coefficientB)}", color = MedicalTextGray)
                Text("Puntos de calibración: ${profile.pointCount}", color = MedicalTextGray)
                Text("Fecha: ${profile.createdDate}", color = MedicalTextGray)
            } ?: run {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "SIN PERFIL DE CALIBRACIÓN",
                    color = MedicalAmber,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CalibrationInstructionsCard(
    calibrationState: CalibrationState,
    referenceSpo2: String,
    onReferenceChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalDarkGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "INSTRUCCIONES DE CALIBRACIÓN",
                style = MaterialTheme.typography.titleMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            if (calibrationState == CalibrationState.IDLE) {
                Text(
                    text = "1. Coloque el dedo índice sobre la cámara y flash",
                    color = MedicalTextGray
                )
                Text(
                    text = "2. Espere a que la señal se estabilice (SQI > 0.7)",
                    color = MedicalTextGray
                )
                Text(
                    text = "3. Ingrese el valor SpO₂ de referencia del oxímetro clínico",
                    color = MedicalTextGray
                )
                Text(
                    text = "4. Inicie la recolección de datos",
                    color = MedicalTextGray
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "SpO₂ REFERENCIA (%):",
                    color = MedicalTextGray,
                    fontWeight = FontWeight.Bold
                )
                
                OutlinedTextField(
                    value = referenceSpo2,
                    onValueChange = onReferenceChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ej: 97") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MedicalCyan,
                        unfocusedBorderColor = MedicalGrid
                    )
                )
            } else if (calibrationState == CalibrationState.COLLECTING) {
                Text(
                    text = "RECOLECTANDO DATOS DE CALIBRACIÓN...",
                    color = MedicalAmber,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Mantenga el dedo inmóvil durante 15 segundos",
                    color = MedicalTextGray
                )
                Text(
                    text = "No cambie la presión ni posición del dedo",
                    color = MedicalTextGray
                )
                Text(
                    text = "La calidad de señal debe permanecer alta",
                    color = MedicalTextGray
                )
            } else if (calibrationState == CalibrationState.COMPLETED) {
                Text(
                    text = "CALIBRACIÓN COMPLETADA",
                    color = MedicalGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "El perfil de calibración ha sido guardado",
                    color = MedicalTextGray
                )
                Text(
                    text = "Ahora puede realizar mediciones SpO₂ precisas",
                    color = MedicalTextGray
                )
            }
        }
    }
}

@Composable
private fun CalibrationPointsCard(points: List<CalibrationPoint>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalDarkGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "PUNTOS DE CALIBRACIÓN (${points.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            points.forEachIndexed { index, point ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Punto ${index + 1}:",
                        color = MedicalTextGray
                    )
                    Text(
                        text = "${point.referenceSpo2}% → RoR: ${"%.4f".format(point.ratioOfRatios)}",
                        color = MedicalGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun CalibrationControlsCard(
    calibrationState: CalibrationState,
    referenceSpo2: String,
    calibrationPoints: List<CalibrationPoint>,
    onStartCalibration: () -> Unit,
    onStopCalibration: () -> Unit,
    onSaveProfile: (DeviceCalibrationProfile) -> Unit,
    onClearPoints: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalDarkGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CONTROLES DE CALIBRACIÓN",
                style = MaterialTheme.typography.titleMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (calibrationState) {
                    CalibrationState.IDLE -> {
                        Button(
                            onClick = onStartCalibration,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MedicalGreen,
                                contentColor = MedicalBlack
                            ),
                            enabled = referenceSpo2.isNotEmpty() && isValidSpo2(referenceSpo2)
                        ) {
                            Text("INICIAR RECOLECCIÓN")
                        }
                        
                        if (calibrationPoints.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onClearPoints,
                                modifier = Modifier.weight(1f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MedicalRed)
                            ) {
                                Text("LIMPIAR DATOS", color = MedicalRed)
                            }
                        }
                    }
                    
                    CalibrationState.COLLECTING -> {
                        Button(
                            onClick = onStopCalibration,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MedicalRed,
                                contentColor = Color.White
                            )
                        ) {
                            Text("DETENER")
                        }
                    }
                    
                    CalibrationState.COMPLETED -> {
                        Button(
                            onClick = {
                                // Generate profile from points
                                val profile = generateProfileFromPoints(calibrationPoints)
                                onSaveProfile(profile)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MedicalGreen,
                                contentColor = MedicalBlack
                            )
                        ) {
                            Text("GUARDAR PERFIL")
                        }
                        
                        OutlinedButton(
                            onClick = onClearPoints,
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MedicalAmber)
                        ) {
                            Text("NUEVA CALIBRACIÓN", color = MedicalAmber)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechnicalInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalDarkGray.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "INFORMACIÓN TÉCNICA",
                style = MaterialTheme.typography.titleMedium,
                color = MedicalCyan,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "• La calibración SpO₂ requiere referencia clínica válida",
                color = MedicalTextGray,
                fontSize = 12.sp
            )
            Text(
                text = "• Se recomiendan 3-5 puntos de calibración para precisión",
                color = MedicalTextGray,
                fontSize = 12.sp
            )
            Text(
                text = "• Los puntos deben cubrir rango 90-100% SpO₂",
                color = MedicalTextGray,
                fontSize = 12.sp
            )
            Text(
                text = "• La calibración es específica para dispositivo/cámara",
                color = MedicalTextGray,
                fontSize = 12.sp
            )
            Text(
                text = "• Los perfiles se guardan localmente en el dispositivo",
                color = MedicalTextGray,
                fontSize = 12.sp
            )
        }
    }
}

// Data classes and enums
data class CalibrationPoint(
    val referenceSpo2: Int,
    val ratioOfRatios: Double,
    val signalQuality: Float,
    val timestamp: Long
)

data class DeviceCalibrationProfile(
    val deviceId: String,
    val cameraId: String,
    val coefficientA: Double,
    val coefficientB: Double,
    val pointCount: Int,
    val createdDate: String,
    val validRange: Pair<Int, Int>
)

enum class CalibrationState {
    IDLE,
    COLLECTING,
    COMPLETED
}

// Helper functions
private fun isValidSpo2(value: String): Boolean {
    return try {
        val spo2 = value.toInt()
        spo2 in 70..100
    } catch (e: NumberFormatException) {
        false
    }
}

private fun generateProfileFromPoints(points: List<CalibrationPoint>): DeviceCalibrationProfile {
    // Simple linear regression for calibration coefficients
    // In a real implementation, this would use more sophisticated regression
    val n = points.size
    if (n < 2) {
        throw IllegalArgumentException("Se necesitan al menos 2 puntos de calibración")
    }
    
    val sumX = points.sumOf { it.ratioOfRatios }
    val sumY = points.sumOf { it.referenceSpo2.toDouble() }
    val sumXY = points.sumOf { it.ratioOfRatios * it.referenceSpo2.toDouble() }
    val sumX2 = points.sumOf { it.ratioOfRatios * it.ratioOfRatios }
    
    val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    val intercept = (sumY - slope * sumX) / n
    
    return DeviceCalibrationProfile(
        deviceId = "${android.os.Build.MODEL}_${android.os.Build.MANUFACTURER}",
        cameraId = "back_main", // In real implementation, get actual camera ID
        coefficientA = intercept,
        coefficientB = -slope,
        pointCount = points.size,
        createdDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date()),
        validRange = Pair(90, 100)
    )
}
