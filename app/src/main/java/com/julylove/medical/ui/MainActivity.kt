package com.julylove.medical.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.julylove.medical.camera.Camera2PpgController
import com.julylove.medical.signal.*
import com.julylove.medical.ui.theme.*
import com.julylove.medical.viewmodel.MonitorViewModel

class MainActivity : ComponentActivity() {

    private lateinit var cameraController: Camera2PpgController

    private val viewModel: MonitorViewModel by viewModels(
        factoryProducer = {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MonitorViewModel(applicationContext, cameraController) as T
                }
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        cameraController = Camera2PpgController(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.CAMERA)
        }

        setContent {
            JULYLOVETheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MedicalBlack) {
                    FullScreenMonitor(viewModel = viewModel)
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

    Box(modifier = Modifier.fillMaxSize().background(MedicalBlack)) {
        
        // 1. Capa de Fondo: Monitor de Ondas Real
        MedicalForensicPPGCanvas(
            samples = state.ppgSamples,
            signalQuality = if (state.signalValid) 0.9f else 0.2f,
            fingerDetected = state.fingerPresent,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Capa de Información: Telemetría y Controles
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("UNIDAD FORENSE JULYLOVE", style = Typography.labelSmall, color = MedicalTextGray)
                    Text(
                        text = state.validityState.name.replace("_", " "),
                        style = Typography.bodySmall,
                        color = when {
                            state.signalValid -> MedicalGreen
                            state.validityState == PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL -> MedicalRed
                            else -> MedicalAmber
                        }
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text("FPS: ${state.fps}", style = Typography.labelSmall, color = MedicalTextGray)
                    Text("LAT: ${state.latencyMs}ms", style = Typography.labelSmall, color = MedicalTextGray)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Indicadores Principales
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                // BPM
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("BPM", style = Typography.labelSmall, color = MedicalTextGray)
                    Text(
                        text = if (state.bpm > 0) state.bpm.toString() else "--",
                        style = Typography.displayLarge.copy(fontSize = 72.sp),
                        color = if (state.signalValid) MedicalGreen else MedicalTextGray
                    )
                }

                // SpO2
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SpO₂", style = Typography.labelSmall, color = MedicalTextGray)
                    Text(
                        text = if (state.spo2 > 0) "${state.spo2.toInt()}%" else "--%",
                        style = Typography.displayLarge.copy(fontSize = 72.sp),
                        color = if (state.signalValid) MedicalCyan else MedicalTextGray
                    )
                    Text(state.spo2Status, style = Typography.labelSmall.copy(fontSize = 8.sp), color = MedicalCyan)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Panel de Control Inferior
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                
                // Switches de Haptics
                Row {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("BEEP", style = Typography.labelSmall.copy(fontSize = 8.sp), color = MedicalTextGray)
                        Switch(checked = state.beepEnabled, onCheckedChange = { viewModel.toggleBeep(it) })
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VIBRA", style = Typography.labelSmall.copy(fontSize = 8.sp), color = MedicalTextGray)
                        Switch(checked = state.vibrationEnabled, onCheckedChange = { viewModel.toggleVibration(it) })
                    }
                }

                // Botón Principal
                Button(
                    onClick = { viewModel.toggleMeasurement() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isMeasuring) MedicalRed else MedicalGreen
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (state.isMeasuring) "DETENER" else "INICIAR MONITOR",
                        color = MedicalBlack,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
