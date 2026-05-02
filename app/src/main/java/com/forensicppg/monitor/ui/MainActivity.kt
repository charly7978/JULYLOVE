package com.forensicppg.monitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels {
        MonitorViewModel.factory(application)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            var showCalibration by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (!hasCameraPermission()) {
                    PermissionScreen(
                        onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    )
                } else {
                    if (showCalibration) {
                        CalibrationScreen(
                            viewModel = viewModel,
                            onClose = { showCalibration = false }
                        )
                    } else {
                        FullScreenMonitorScreen(
                            viewModel = viewModel,
                            onOpenCalibration = { showCalibration = true }
                        )
                    }
                }
            }
        }

        if (hasCameraPermission()) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stop()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) viewModel.start()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stop()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun legacyImmersive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("MONITOR PPG FORENSE",
            color = Color(0xFF22FFAA),
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        Text(
            "Se requiere acceso a la cámara para capturar la señal PPG. El dedo índice " +
                "debe cubrir la cámara trasera y el flash.",
            color = Color.White,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22FFAA))
        ) {
            Text("Conceder permiso", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
    }
}
