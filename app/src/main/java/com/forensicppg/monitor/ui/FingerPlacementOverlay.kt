package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Overlay full-screen con la guía única de toma de muestra. Aparece al
 * abrir la app; el usuario debe pulsar "ENTENDIDO" para continuar.
 */
@Composable
fun FingerPlacementOverlay(onContinue: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF002080C))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF071018), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF22FFAA).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "POSICIÓN ÚNICA DE TOMA DE MUESTRA",
                color = Color(0xFF22FFAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Una sola forma correcta. No hay otras configuraciones.",
                color = Color(0xFFA8D5C5),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            BulletLine("1) Dedo ÍNDICE de la mano NO dominante.")
            BulletLine("2) La YEMA cubre AL MISMO TIEMPO la cámara y el LED de flash. Sin huecos por donde entre luz ambiente.")
            BulletLine("3) PRESIÓN LIGERA. La piel no debe blanquearse.")
            BulletLine("4) Mano apoyada. Codo apoyado. Antebrazo a la altura del corazón.")
            BulletLine("5) Inmóvil 3 segundos antes de iniciar y durante toda la captura. No hablar.")
            Spacer(Modifier.height(12.dp))
            Text(
                "El monitor publica BPM y SpO₂ sólo cuando hay evidencia óptica viva. " +
                    "Si ve SIN CONTACTO, BAJA PERFUSIÓN o SATURACIÓN ÓPTICA, ajuste presión o cobertura.",
                color = Color(0xFF99B0BB),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF22FFAA))
                    .clickable { runCatching { onContinue() } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "ENTENDIDO — IR AL MONITOR",
                    color = Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Text(
        text,
        color = Color(0xFFE5F4EE),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
