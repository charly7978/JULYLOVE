package com.julylove.medical.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.julylove.medical.camera.Camera2Controller
import com.julylove.medical.signal.FingerDetectionEngine
import com.julylove.medical.signal.RhythmAnalysisEngine
import com.julylove.medical.ui.theme.*
import com.julylove.medical.viewmodel.MonitorViewModel

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: Camera2Controller
    private lateinit var viewModel: MonitorViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        cameraController = Camera2Controller(this)
        viewModel = MonitorViewModel(cameraController)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            JULYLOVETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MedicalBlack
                ) {
                    FullScreenMonitor(viewModel)
                }
            }
        }
    }
}

@Composable
fun FullScreenMonitor(viewModel: MonitorViewModel) {
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Main Waveform Background
        PPGWaveformCanvas(
            samples = state.ppgSamples,
            modifier = Modifier.fillMaxSize()
        )

        // Top Telemetry Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("FPS: ${state.technicalData.fps}", style = Typography.labelSmall, color = MedicalGreen)
                Text("CONF: ${(state.technicalData.signalConfidence * 100).toInt()}%", style = Typography.labelSmall, color = MedicalGreen)
            }
            Text("SYSTEM STATE: ${state.sqi}", style = Typography.labelSmall, color = MedicalAmber)
        }

        // Central HUD
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = if (state.bpm > 0) state.bpm.toString() else "--",
                style = Typography.displayLarge,
                color = if (state.rhythmStatus == RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA) MedicalRed else MedicalGreen
            )
            Text(
                text = "BPM",
                style = Typography.headlineMedium,
                color = MedicalTextGray
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            StatusBadge(state.rhythmStatus)
        }

        // Finger Position Guide
        if (state.fingerState != FingerDetectionEngine.FingerState.SENAL_VALIDA) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when(state.fingerState) {
                        FingerDetectionEngine.FingerState.NO_DEDO -> "POSICIONE EL DEDO SOBRE LA CÁMARA"
                        FingerDetectionEngine.FingerState.POSICIONANDO -> "AJUSTANDO SEÑAL..."
                        FingerDetectionEngine.FingerState.PRESION_EXCESIVA -> "REDUZCA LA PRESIÓN"
                        else -> ""
                    },
                    style = Typography.headlineMedium,
                    color = MedicalGreen,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }

        // Bottom Control / Technical Panel
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(MedicalDarkGray.copy(alpha = 0.8f))
                .padding(12.dp)
        ) {
            Text("TECHNICAL LOG", style = Typography.labelSmall, color = MedicalTextGray)
            Text("RMSSD: ${"%.2f".format(state.technicalData.rmssd)} ms", style = Typography.bodyMedium, color = MedicalCyan)
            Text("RHYTHM: ${state.rhythmStatus}", style = Typography.bodyMedium, color = if (state.rhythmStatus == RhythmAnalysisEngine.RhythmStatus.REGULAR) MedicalGreen else MedicalAmber)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { viewModel.toggleMeasurement() },
                colors = ButtonDefaults.buttonColors(containerColor = if (state.isMeasuring) MedicalRed else MedicalGreen)
            ) {
                Text(if (state.isMeasuring) "DETENER SCAN" else "INICIAR ANALISIS", color = MedicalBlack, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StatusBadge(status: RhythmAnalysisEngine.RhythmStatus) {
    val color = when(status) {
        RhythmAnalysisEngine.RhythmStatus.REGULAR -> MedicalGreen
        RhythmAnalysisEngine.RhythmStatus.IRREGULAR -> MedicalAmber
        RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA -> MedicalRed
        else -> MedicalTextGray
    }
    
    Surface(
        color = color.copy(alpha = 0.2f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    ) {
        Text(
            text = status.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = Typography.labelSmall,
            color = color
        )
    }
}
