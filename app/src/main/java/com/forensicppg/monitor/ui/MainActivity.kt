package com.forensicppg.monitor.ui

import android.Manifest
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
        // Evitar que un theme inmersivo agresivo o un controller no-disponible
        // cause un crash en oem específicos: arrancamos con setup mínimo.
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Throwable) {
            // No bloqueamos por algo cosmético.
        }

        setContent {
            var showCalibration by remember { mutableStateOf(false) }
            val previousCrash = remember {
                CrashLogger.lastCrashFile(application)?.takeIf { it.exists() && it.length() > 0 }
                    ?.let { runCatching { it.readText() }.getOrNull() }
            }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (!hasCameraPermission()) {
                    PermissionScreen(
                        previousCrash = previousCrash,
                        onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    )
                } else if (showCalibration) {
                    CalibrationScreen(
                        viewModel = viewModel,
                        onClose = { showCalibration = false }
                    )
                } else {
                    MonitorScreen(
                        viewModel = viewModel,
                        onOpenCalibration = { showCalibration = true }
                    )
                }
            }
        }

        // YA NO arrancamos la cámara automáticamente. El usuario debe pulsar
        // INICIAR. Antes el `viewModel.start()` aquí podía crashear si el
        // permiso recién acababa de concederse y la cámara aún no estaba
        // libre, o si Camera2 fallaba en la apertura.
    }

    override fun onPause() {
        super.onPause()
        runCatching { viewModel.stop() }
    }

    override fun onResume() {
        super.onResume()
        // Sin reanudación automática: respetamos la decisión del usuario.
    }

    override fun onStop() {
        super.onStop()
        runCatching { viewModel.stop() }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}

@Composable
private fun PermissionScreen(previousCrash: String?, onGrant: () -> Unit) {
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

        if (!previousCrash.isNullOrBlank()) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Se detectó un cierre inesperado anterior. Reporte de diagnóstico:",
                color = Color(0xFFFFAA22),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A0F0A))
                    .padding(8.dp)
            ) {
                Text(
                    previousCrash.take(4000),
                    color = Color(0xFFFFCCAA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
        }
    }
}
