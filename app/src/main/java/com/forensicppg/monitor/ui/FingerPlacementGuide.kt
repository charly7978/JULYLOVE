package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Guía única de contacto óptimo cPPG: **pulgar**, yema distal, centrado sobre **lente + flash**,
 * firme pero sin clipping. Referencias técnicas: revisión método contact-based smartphone PPG
 * y calibración cPPG (Front. Digit. Health 2023, Wang et al.).
 */
@Composable
fun FingerPlacementGuide(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF0101820))
            .border(1.dp, Color(0xFF22FFAA), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CONTACTO ÓPTIMO — UNA SOLA POSICIÓN",
                modifier = Modifier.weight(1f),
                color = Color(0xFF22FFAA),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onDismissRequest) {
                Text(
                    "Ocultar",
                    color = Color(0xFFAAFFDD),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
        Text(
            "• Pulgar: yema (pulpa distal) centrada para cubrir el lente y el LED de flash; evita aristas donde entre luz ambiente.",
            color = Color(0xFFE8FFF4),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "• Si hay saturación u oscuridad extrema, ajusta presión lentamente hasta ver perfusión en la onda sin recortes.",
            color = Color(0xFFB8D8CC),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "• Apoya brazo y teléfono; evita mover el dedo hasta que BPM y la forma de onda se estabilicen (típicamente ~10–25 s a ~30 FPS en protocolos de literatura revisada).",
            color = Color(0xFFB8D8CC),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}
