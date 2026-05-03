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
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.domain.VitalReading

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
            "• Cobrí flash + lente a la vez: en muchos equipos el LED está ligeramente hacia ARRIBA " +
                "del centro físico — deslizá un poco el pulgar hasta notar pulsación en verde.",
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

/** Pistas en vivo mientras hay captura — menos error de uso. */
@Composable
fun FingerContactCoach(reading: VitalReading, modifier: Modifier = Modifier) {
    val motion = reading.motionScore.coerceIn(0.0, 1.08)
    val motionHint = when {
        motion > 0.56 -> Triple("MOVIM.", "Alto — apoyo doble del teléfono o codera.", Color(0xFFFF5577))
        motion > 0.34 -> Triple("MOVIM.", "Moderado — congelá 12–20 s sin deslizar el dedo.", Color(0xFFFFCC44))
        else -> Triple("MOVIM.", "Estable.", Color(0xFF66DDAA))
    }
    val pressHint = when {
        reading.clippingSuspectedHigh ->
            Triple(
                "PRES.",
                "Alto — aflojá la yema (clip / saturación bloqueando el pulsátil).",
                Color(0xFFFF5577)
            )
        reading.clippingSuspectedLow ->
            Triple(
                "PRES.",
                "Muy oscuro — apoyo firme sólo hasta ver ondas repetidas.",
                Color(0xFFFFCC44)
            )
        else -> Triple("PRES.", "Rango usable — microajustes lentos.", Color(0xFF66DDAA))
    }
    val signalHint = when (reading.validityState) {
        PpgValidityState.BIOMETRIC_VALID, PpgValidityState.PPG_VALID ->
            Triple("SEÑAL", "PPG válido.", Color(0xFF44FFBB))
        PpgValidityState.PPG_CANDIDATE ->
            Triple("SEÑAL", "Candidato — quedá quieto algunos fotogramas más.", Color(0xFFEEDD55))
        PpgValidityState.RAW_OPTICAL_ONLY ->
            Triple("SEÑAL", "Sólo óptico — centro yema cubriendo flash+lente junto.", Color(0xFFFF9933))
        PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL ->
            Triple("SEÑAL", "No tipo PPG — repetí cobertura sin huecos junto al LED.", Color(0xFFFF5577))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xDD060C12), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ASISTENTE contacto ",
                color = Color(0xFFFFCC77),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(0.36f),
                maxLines = 2
            )
            Text("|", color = Color(0xFF445566), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text(
                " SQI %.2f".format(reading.sqi),
                color = Color(0xFFAADDDD),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(0.62f),
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        CoachLine(symbol = motionHint.first, text = motionHint.second, color = motionHint.third)
        CoachLine(symbol = pressHint.first, text = pressHint.second, color = pressHint.third)
        CoachLine(symbol = signalHint.first, text = signalHint.second, color = signalHint.third)
    }
}

@Composable
private fun CoachLine(symbol: String, text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            symbol,
            color = color.copy(alpha = 0.92f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.24f),
            maxLines = 3
        )
        Text(
            text,
            color = Color(0xFFEAF6F8),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            modifier = Modifier.weight(1f),
            maxLines = 4
        )
    }
}
