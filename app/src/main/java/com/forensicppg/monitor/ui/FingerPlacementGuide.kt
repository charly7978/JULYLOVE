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
 * Guía única: índice, yema cubriendo lente + flash; estabilización 8–12 s.
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
                "POSICIÓN ÚNICA — DEDO ÍNDICE",
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
            "Apoyá la yema del dedo índice cubriendo al mismo tiempo la lente trasera principal y el flash. " +
                "Presión suave, constante, sin aplastar, sin mover. Esperá 8–12 segundos de estabilización.",
            color = Color(0xFFE8FFF4),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Brazo apoyado a la altura del corazón; sin objetos ni superficies frente al sensor.",
            color = Color(0xFFB8D8CC),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

@Composable
fun FingerContactCoach(reading: VitalReading, modifier: Modifier = Modifier) {
    val motion = reading.motionScore.coerceIn(0.0, 1.08)
    val motionHint = when {
        motion >= 0.20 -> Triple("MOV.", "Alto — teléfono y dedo quietos.", Color(0xFFFF5577))
        motion > 0.12 -> Triple("MOV.", "Moderado — sin deslizar la yema.", Color(0xFFFFCC44))
        else -> Triple("MOV.", "Estable.", Color(0xFF66DDAA))
    }
    val pressHint = when {
        reading.clippingSuspectedHigh ->
            Triple(
                "SAT.",
                "Clip alto — aflojá presión.",
                Color(0xFFFF5577)
            )
        reading.clippingSuspectedLow ->
            Triple(
                "LUZ",
                "Oscuro o sin flash útil — cubrir lente y flash juntos.",
                Color(0xFFFFCC44)
            )
        else -> Triple("SAT.", "Rango usable.", Color(0xFF66DDAA))
    }
    val signalHint = when (reading.validityState) {
        PpgValidityState.BIOMETRIC_VALID, PpgValidityState.PPG_VALID ->
            Triple("PPG", "Confirmado.", Color(0xFF44FFBB))
        PpgValidityState.PPG_CANDIDATE ->
            Triple("PPG", "Acumulando latidos…", Color(0xFFEEDD55))
        PpgValidityState.QUIET_NO_PULSE ->
            Triple("PPG", "Quieto pero sin pulso verificable.", Color(0xFFFF9933))
        PpgValidityState.BAD_CONTACT ->
            Triple("PPG", "Contacto insuficiente — índice sobre lente+flash.", Color(0xFFFF5577))
        PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL ->
            Triple("PPG", "NO_PPG — no hay perfil de dedo pulsátil.", Color(0xFFFF5577))
        PpgValidityState.CLIPPING ->
            Triple("PPG", "Saturación — menos presión.", Color(0xFFFF6644))
        PpgValidityState.MOTION ->
            Triple("PPG", "Movimiento — pausa y volver a colocar.", Color(0xFFFF5588))
        PpgValidityState.LOW_LIGHT ->
            Triple("PPG", "Luz baja — flash cubierto por la yema.", Color(0xFFFFAA44))
        PpgValidityState.LOW_PERFUSION ->
            Triple("PPG", "Perfusión baja — ajustá contacto.", Color(0xFFFFAA44))
        PpgValidityState.SEARCHING ->
            Triple("PPG", "Colocando dedo / buscando máscara estable.", Color(0xFFFFAA22))
        PpgValidityState.RAW_OPTICAL_ONLY ->
            Triple("PPG", "Óptico sin periodicidad cardíaca.", Color(0xFFFF9933))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xDD060C12), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Estado ",
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
