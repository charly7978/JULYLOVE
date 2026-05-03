package com.forensicppg.monitor.ui

import android.app.Application
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.forensicppg.monitor.forensic.CrashLogger
import java.io.File

/**
 * Pantalla de modo seguro: aparece cuando se detecta un crash reciente o
 * un error no fatal. NO toca Camera2 ni sensores ni audio. Sólo muestra
 * el stack trace y permite compartirlo. De este modo el usuario puede
 * reportar el bug aunque la app cierre apenas se intenta medir.
 */
@Composable
fun SafeModeScreen(
    title: String,
    report: String,
    crashFile: File?,
    onContinue: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120A0A))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            title,
            color = Color(0xFFFFAA22),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "La app detectó un cierre inesperado. Compartí el reporte (botón abajo) " +
                "para que pueda corregirse. La cámara NO se inicia mientras estés en " +
                "esta pantalla.",
            color = Color(0xFFE6E6E6),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A0E0A))
                .padding(8.dp)
        ) {
            Text(
                report.take(8000),
                color = Color(0xFFFFCCAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runCatching { shareReport(ctx, report, crashFile) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4488CC))
            ) {
                Text("Compartir reporte", color = Color.White, fontFamily = FontFamily.Monospace)
            }
            Button(
                onClick = { runCatching { onContinue() } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22FFAA))
            ) {
                Text("Continuar", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
            if (onDismiss != null) {
                Button(
                    onClick = { runCatching { onDismiss() } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333844))
                ) {
                    Text("Cerrar", color = Color.White, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "También guardado como archivo en /Android/data/${(ctx.applicationContext as? Application)?.packageName ?: "com.forensicppg.monitor"}/files/",
            color = Color(0x88AABBCC),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

private fun shareReport(ctx: Context, report: String, file: File?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Monitor PPG Forense — crash report")
        putExtra(Intent.EXTRA_TEXT, report.take(60_000))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (file != null && file.exists()) {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    ctx.startActivity(Intent.createChooser(intent, "Compartir reporte de error"))
}

/** Helper para uso en MainActivity sin compose. */
fun lastCrashReport(app: Application): Pair<String, File?>? {
    val nonFatal = CrashLogger.lastNonFatalReport()
    if (!nonFatal.isNullOrBlank()) {
        return nonFatal to CrashLogger.lastCrashFile(app)
    }
    val fatal = CrashLogger.lastCrashReport()
    if (!fatal.isNullOrBlank()) return fatal to CrashLogger.lastCrashFile(app)
    val file = CrashLogger.lastCrashFile(app) ?: return null
    val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return text to file
}
