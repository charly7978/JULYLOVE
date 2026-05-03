package com.forensicppg.monitor.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import com.forensicppg.monitor.forensic.CrashLogger

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels {
        MonitorViewModel.factory(application)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* sin autostart: el usuario decide cuándo iniciar */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        setContent {
            // Listener live: si una corrutina del ViewModel reporta un error
            // no fatal, refrescamos esta variable y mostramos SafeModeScreen.
            var liveError by remember {
                mutableStateOf(CrashLogger.lastNonFatalReport())
            }
            DisposableEffect(Unit) {
                val cb: (String) -> Unit = { liveError = it }
                CrashLogger.addLiveListener(cb)
                onDispose { CrashLogger.removeLiveListener(cb) }
            }

            // Reporte previo (crash o no-fatal de ejecución anterior).
            val previous = remember { lastCrashReport(application) }

            var showCalibration by remember { mutableStateOf(false) }
            var safeModeDismissed by remember { mutableStateOf(false) }

            val showSafeMode = (liveError != null) || (previous != null && !safeModeDismissed)

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                when {
                    showSafeMode -> {
                        val text = liveError ?: previous?.first ?: ""
                        val file = previous?.second
                        SafeModeScreen(
                            title = if (liveError != null) "Error en ejecución" else "Cierre inesperado anterior",
                            report = text,
                            crashFile = file,
                            onContinue = {
                                liveError = null
                                safeModeDismissed = true
                            }
                        )
                    }
                    !hasCameraPermission() -> {
                        PermissionScreen(
                            onGrant = {
                                runCatching {
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        )
                    }
                    showCalibration -> {
                        SafeContent {
                            CalibrationScreen(
                                viewModel = viewModel,
                                onClose = { showCalibration = false }
                            )
                        }
                    }
                    else -> {
                        SafeContent {
                            MonitorScreen(
                                viewModel = viewModel,
                                onOpenCalibration = { showCalibration = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { viewModel.stop() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { viewModel.stop() }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}

/** Wrap defensivo: si la composición lanza, muestra un fallback en lugar
 *  de matar el proceso. (Compose habitualmente recompone tras error en
 *  StrictMode/DEV, pero en producción una NPE recursiva tira la actividad.) */
@Composable
private fun SafeContent(content: @Composable () -> Unit) {
    val result = runCatching { content() }
    val err = result.exceptionOrNull()
    if (err != null) {
        CrashLogger.reportNonFatal("Compose.SafeContent", err)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A0E0A))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Error en la pantalla.",
                color = Color(0xFFFFAA22),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                err.stackTraceToString().take(8000),
                color = Color(0xFFFFCCAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "MONITOR PPG FORENSE",
            color = Color(0xFF22FFAA),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Se requiere acceso a la cámara para capturar la señal PPG. La yema del " +
                "dedo índice de la mano no dominante debe cubrir la cámara trasera y " +
                "el flash al mismo tiempo.",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22FFAA))
        ) {
            Text("Conceder permiso", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
    }
}

@Suppress("unused")
private fun unusedToKeepImports(app: Application) {
    // Mantener referencia a la clase Application para evitar warnings de
    // importaciones cuando se reorganice el archivo.
    @Suppress("UnusedExpression") app.toString()
}
