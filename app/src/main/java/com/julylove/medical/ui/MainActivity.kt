package com.julylove.medical.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.julylove.medical.camera.AmbientLightMonitor
import com.julylove.medical.camera.Camera2PpgController
import com.julylove.medical.signal.PpgValidityState
import com.julylove.medical.signal.RhythmAnalysisEngine
import com.julylove.medical.ui.theme.*
import com.julylove.medical.viewmodel.MonitorViewModel

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: Camera2PpgController
    private lateinit var ambientMonitor: AmbientLightMonitor

    private val luxHandler = Handler(Looper.getMainLooper())
    private val luxTicker = object : Runnable {
        override fun run() {
            if (::cameraController.isInitialized && ::ambientMonitor.isInitialized) {
                cameraController.notifyAmbientLux(ambientMonitor.lux)
            }
            luxHandler.postDelayed(this, 500L)
        }
    }

    private val viewModel: MonitorViewModel by viewModels(
        factoryProducer = {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass != MonitorViewModel::class.java) {
                        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
                    }
                    return MonitorViewModel(applicationContext, cameraController) as T
                }
            }
        }
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ambientMonitor = AmbientLightMonitor(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        cameraController = Camera2PpgController(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            JULYLOVETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MedicalBlack
                ) {
                    FullScreenMonitor(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ambientMonitor.start()
        luxHandler.post(luxTicker)
    }

    override fun onPause() {
        luxHandler.removeCallbacks(luxTicker)
        ambientMonitor.stop()
        super.onPause()
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
        if (!state.showCalibrationScreen) {
            PPGWaveformCanvas(
                samples = state.ppgSamples,
                isMeasuring = state.isMeasuring,
                rhythmStatus = state.rhythmStatus,
                classifiedBeats = state.classifiedBeats,
                arrhythmiaEvents = state.arrhythmiaEvents,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CalibrationScreen(
                onBackToMonitor = { viewModel.toggleCalibrationScreen(false) },
                modifier = Modifier.fillMaxSize()
            )
        }

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
                    Text("ID_UNIDAD: FORENSIC-7978", style = Typography.labelSmall, color = MedicalTextGray)
                    Text("ESTADO: ${translateValidity(state.validityState)}", 
                        style = Typography.labelSmall, 
                        color = when(state.validityState) {
                            PpgValidityState.PPG_VALID -> MedicalGreen
                            PpgValidityState.PPG_CANDIDATE, PpgValidityState.SEARCHING_PPG -> MedicalAmber
                            else -> MedicalRed
                        })
                }
                Text("BIO-MONITOR JULYLOVE v1.0", style = Typography.labelSmall, color = MedicalTextGray)
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
            // SECCIÓN FRECUENCIA CARDÍACA
            Text("RITMO CARDÍACO", style = Typography.labelSmall, color = MedicalTextGray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state.bpm > 0 && state.validityState == PpgValidityState.PPG_VALID) 
                        state.bpm.toString() else "--",
                    style = Typography.displayLarge.copy(fontSize = 100.sp, lineHeight = 100.sp),
                    color = when(state.rhythmStatus) {
                        RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA -> MedicalRed
                        RhythmAnalysisEngine.RhythmStatus.IRREGULAR -> MedicalAmber
                        else -> MedicalGreen
                    }
                )
                Text("LPM", style = Typography.headlineMedium, color = MedicalTextGray, modifier = Modifier.padding(bottom = 16.dp, start = 4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // SECCIÓN SpO2
            Text("SAT. OXÍGENO", style = Typography.labelSmall, color = MedicalTextGray)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (state.spo2 > 0 && state.validityState == PpgValidityState.PPG_VALID) 
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
                    Text("ANÁLISIS_DE_SEÑAL", style = Typography.labelSmall, color = MedicalCyan, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    TechnicalValue("VALIDEZ", translateValidity(state.validityState))
                    TechnicalValue("CONFIANZA", "${(state.technicalData.signalConfidence * 100).toInt()}%")
                    TechnicalValue("RMSSD", "${"%.1f".format(state.technicalData.rmssd)} ms")
                    TechnicalValue("pNN50", "${"%.1f".format(state.technicalData.pnn50)}%")
                    TechnicalValue("COEF_VAR", "${"%.1f".format(state.technicalData.cv)}%")
                    TechnicalValue("H_SHANNON", "${"%.2f".format(state.technicalData.shannonEntropyBits)} bit")
                    TechnicalValue(
                        "SAMPEN",
                        state.technicalData.sampleEntropy?.let { "%.3f".format(it) } ?: "—"
                    )
                    TechnicalValue("QBAL_R", "${"%.3f".format(state.technicalData.quadrantBalanceRed)}")
                    TechnicalValue("SIG_BLOK", "${"%.2f".format(state.technicalData.blockLumaStd)}")
                    TechnicalValue("GRAD_I", "${"%.1f".format(state.technicalData.interBlockGradient)}")
                    TechnicalValue("INDICE_MOV", "${"%.3f".format(state.technicalData.motionIntensity)}")
                    TechnicalValue("FPS_MUESTREO", "${state.technicalData.fps}")
                    TechnicalValue("LN_I_I0", "${"%.4f".format(state.technicalData.odPulseScaled)}")
                    TechnicalValue("I0_VERDE", "${"%.5f".format(state.technicalData.odBaselineGreen01)}")
                    TechnicalValue(
                        "OSC_CALIB",
                        when {
                            state.darkCalibrationCollecting -> "ADQUIER..."
                            state.darkCalibrationReady -> "LISTO"
                            else -> "---"
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("BEEP", style = Typography.labelSmall, color = MedicalTextGray)
                        Switch(
                            checked = state.beepEnabled,
                            onCheckedChange = { viewModel.toggleBeep(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = MedicalGreen)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("HÁPTICO", style = Typography.labelSmall, color = MedicalTextGray)
                        Switch(
                            checked = state.vibrationEnabled,
                            onCheckedChange = { viewModel.toggleVibration(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = MedicalGreen)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedButton(
                        onClick = { viewModel.toggleCalibrationScreen(true) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MedicalCyan)
                    ) {
                        Text(
                            text = "CALIBRACIÓN SpO₂",
                            style = Typography.labelSmall,
                            color = MedicalCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.startDarkFrameCalibration() },
                        enabled = state.isMeasuring && !state.darkCalibrationCollecting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MedicalGrid)
                    ) {
                        Text(
                            text = when {
                                state.darkCalibrationCollecting -> "CAPTURA OSCURA…"
                                state.darkCalibrationReady -> "CAL. OSCURO: OK"
                                else -> "CAL. OSCURO (~1 s tapado)"
                            },
                            style = Typography.labelSmall,
                            color = MedicalAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }

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
                            text = if (state.isMeasuring) "DETENER ANALIZADOR" else "INICIAR ANALIZADOR",
                            color = MedicalBlack,
                            style = Typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // State Overlays
        if (state.isMeasuring && state.validityState != PpgValidityState.PPG_VALID) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MedicalBlack.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.validityState == PpgValidityState.SEARCHING_PPG || state.validityState == PpgValidityState.PPG_CANDIDATE) {
                        CircularProgressIndicator(color = MedicalAmber)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = when(state.validityState) {
                            PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL -> "SEÑAL NO FISIOLÓGICA DETECTADA\n(Verifique la posición del dedo)"
                            PpgValidityState.SEARCHING_PPG -> "ANALIZANDO PULSATILIDAD..."
                            PpgValidityState.PPG_CANDIDATE -> "ESTABILIZANDO SEÑAL PPG..."
                            PpgValidityState.SATURATED -> "SEÑAL SATURADA - REDUZCA LA PRESIÓN"
                            PpgValidityState.MOTION_ARTIFACT -> "MOVIMIENTO EXCESIVO DETECTADO"
                            PpgValidityState.LOW_PERFUSION -> "BAJA PERFUSIÓN - CALIENTE EL DEDO"
                            else -> "LISTO PARA ADQUISICIÓN"
                        },
                        style = Typography.headlineMedium,
                        color = if (state.validityState == PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL) MedicalRed else MedicalAmber,
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
    val (color, text) = when(status) {
        RhythmAnalysisEngine.RhythmStatus.REGULAR -> MedicalGreen to "RITMO REGULAR"
        RhythmAnalysisEngine.RhythmStatus.IRREGULAR -> MedicalAmber to "RITMO IRREGULAR"
        RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA -> MedicalRed to "ARRITMIA DETECTADA"
        RhythmAnalysisEngine.RhythmStatus.CALIBRATING -> MedicalTextGray to "CALIBRANDO"
    }
    
    Surface(
        color = color.copy(alpha = 0.2f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = Typography.labelSmall,
            color = color
        )
    }
}

fun translateValidity(state: PpgValidityState): String {
    return when(state) {
        PpgValidityState.MEASURING_RAW_OPTICAL -> "ADQUISICIÓN ÓPTICA"
        PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL -> "SIN SEÑAL FISIOLÓGICA"
        PpgValidityState.SEARCHING_PPG -> "BUSCANDO PULSO"
        PpgValidityState.PPG_CANDIDATE -> "CANDIDATO PPG"
        PpgValidityState.PPG_VALID -> "SEÑAL VÁLIDA"
        PpgValidityState.SATURATED -> "SATURACIÓN"
        PpgValidityState.MOTION_ARTIFACT -> "ARTEFACTO DE MOVIMIENTO"
        PpgValidityState.LOW_PERFUSION -> "BAJA PERFUSIÓN"
        PpgValidityState.ERROR -> "ERROR DE SENSOR"
    }
}
