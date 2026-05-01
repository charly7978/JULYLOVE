package com.julylove.medical.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide System Bars for Immersive Mode
        hideSystemUI()
        
        cameraController = Camera2Controller(this)
        viewModel = MonitorViewModel(applicationContext, cameraController)

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

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
fun FullScreenMonitor(viewModel: MonitorViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showTelemetry by remember { mutableStateOf(true) }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(MedicalBlack)) {
        
        // 1. MONITOR CARDIACO FULL SCREEN (Background Layer)
        PPGWaveformCanvas(
            samples = state.ppgSamples,
            isMeasuring = state.isMeasuring,
            modifier = Modifier.fillMaxSize()
        )

        // 2. SCANNING OVERLAY (CRT SCAN LINE EFFECT)
        if (state.isMeasuring) {
            ScanningOverlay()
        }

        // 3. TELEMETRÍA TÉCNICA (Top Layer)
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 20.dp, end = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("UNIT_ID: FORENSIC-7978", style = Typography.labelSmall, color = MedicalTextGray)
                    Text("STATUS: ${if (state.isMeasuring) "ACTIVE_ACQUISITION" else "STANDBY"}", 
                        style = Typography.labelSmall, 
                        color = if (state.isMeasuring) MedicalGreen else MedicalAmber)
                }
                Text("JULYLOVE BIO-MONITOR v1.0", style = Typography.labelSmall, color = MedicalTextGray)
            }
            HorizontalDivider(color = MedicalGrid, thickness = 1.dp, modifier = Modifier.padding(top = 4.dp))
        }

        // Main Clinical Indicators (Right Side)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp),
            horizontalAlignment = Alignment.End
        ) {
            // HEART RATE SECTION
            Text("HEART RATE", style = Typography.labelSmall, color = MedicalTextGray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state.bpm > 0 && state.sqiLevel != SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE) 
                        state.bpm.toString() else "--",
                    style = Typography.displayLarge.copy(fontSize = 100.sp, lineHeight = 100.sp),
                    color = when(state.rhythmStatus) {
                        RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA -> MedicalRed
                        RhythmAnalysisEngine.RhythmStatus.IRREGULAR -> MedicalAmber
                        else -> MedicalGreen
                    }
                )
                Text("BPM", style = Typography.headlineMedium, color = MedicalTextGray, modifier = Modifier.padding(bottom = 16.dp, start = 4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // SpO2 SECTION
            Text("OXYGEN SAT", style = Typography.labelSmall, color = MedicalTextGray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state.spo2 > 0 && state.sqiLevel != SignalQualityIndexEngine.QualityLevel.NO_INTERPRETABLE) 
                        state.spo2.toInt().toString() else "--",
                    style = Typography.displayLarge.copy(fontSize = 60.sp, lineHeight = 60.sp),
                    color = MedicalCyan
                )
                Text("%", style = Typography.headlineMedium.copy(fontSize = 24.sp), color = MedicalTextGray, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
            }
            Text(state.spo2Status, style = Typography.labelSmall.copy(fontSize = 9.sp), color = MedicalCyan)

            Spacer(modifier = Modifier.height(32.dp))
            
            StatusBadge(state.rhythmStatus)
        }

        // Technical Telemetry Panel (Bottom Left)
        if (showTelemetry) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp)
                    .width(240.dp)
                    .background(MedicalDarkGray.copy(alpha = 0.85f))
                    .padding(12.dp)
            ) {
                Column {
                    Text("SIGNAL_ANALYSIS", style = Typography.labelSmall, color = MedicalCyan, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    TechnicalValue("SQI_LEVEL", state.sqiLevel.name)
                    TechnicalValue("CONFIDENCE", "${(state.technicalData.signalConfidence * 100).toInt()}%")
                    TechnicalValue("RMSSD", "${"%.1f".format(state.technicalData.rmssd)} ms")
                    TechnicalValue("pNN50", "${"%.1f".format(state.technicalData.pnn50)}%")
                    TechnicalValue("VAR_COEFF", "${"%.1f".format(state.technicalData.cv)}%")
                    TechnicalValue("MOTION_IDX", "${"%.3f".format(state.technicalData.motionIntensity)}")
                    TechnicalValue("SAMPLE_FPS", "${state.technicalData.fps}")
                    TechnicalValue("DC_OFFSET", "${state.technicalData.greenDC.toInt()}")
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { viewModel.toggleMeasurement() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isMeasuring) MedicalRed else MedicalGreen
                        )
                    ) {
                        Text(
                            text = if (state.isMeasuring) "STOP ANALYZER" else "INIT ANALYZER",
                            color = MedicalBlack,
                            style = Typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Finger Position Warning
        if (state.isMeasuring && state.fingerState != FingerDetectionEngine.FingerState.SENAL_VALIDA) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MedicalBlack.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MedicalGreen)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = when(state.fingerState) {
                            FingerDetectionEngine.FingerState.NO_DEDO -> "PLACE INDEX FINGER ON CAMERA & FLASH"
                            FingerDetectionEngine.FingerState.POSICIONANDO -> "STABILIZING OPTICAL SIGNAL..."
                            FingerDetectionEngine.FingerState.PRESION_EXCESIVA -> "TOO MUCH PRESSURE - RELAX FINGER"
                            else -> "SIGNAL INTERRUPTED"
                        },
                        style = Typography.headlineMedium,
                        color = MedicalGreen,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ScanningOverlay() {
    // Simple vertical scan line animation could be added here
    Box(modifier = Modifier
        .fillMaxSize()
        .background(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    MedicalGreen.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        )
    )
}

@Composable
fun TechnicalValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = Typography.labelSmall.copy(fontSize = 9.sp), color = MedicalTextGray)
        Text(value, style = Typography.labelSmall.copy(fontSize = 9.sp), color = MedicalGreen)
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
