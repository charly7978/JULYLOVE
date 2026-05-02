package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.domain.HypertensionRiskBand
import com.forensicppg.monitor.domain.VitalReading

@Composable
fun ArrhythmiaEventOverlay(reading: VitalReading, modifier: Modifier = Modifier) {
    val risk = reading.hypertensionRisk
    val color = when (risk) {
        HypertensionRiskBand.NORMOTENSE -> Color(0xFF22FFAA)
        HypertensionRiskBand.BORDERLINE -> Color(0xFFFFAA22)
        HypertensionRiskBand.HYPERTENSIVE_PATTERN -> Color(0xFFFF3344)
        HypertensionRiskBand.UNCERTAIN, null -> Color(0x99AAAAAA)
    }
    Box(
        modifier = modifier
            .background(Color(0xFF080D10), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("CRIBADO ARRITMIA / PRESIÓN",
                    color = Color(0xFF22FFAA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallStat("LATIDOS", "${reading.beatsDetected}", Color(0xFF22FFAA))
                SmallStat("ANÓMALOS", "${reading.abnormalBeats}", if (reading.abnormalBeats > 0) Color(0xFFFF3344) else Color(0xFF22FFAA))
                SmallStat("SDNN",
                    reading.rrSdnnMs?.let { "%.0f ms".format(it) } ?: "—",
                    Color(0xFFAACCEE))
                SmallStat("pNN50",
                    reading.pnn50?.let { "%.0f%%".format(it * 100) } ?: "—",
                    Color(0xFFAACCEE))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Presión / cribado: " + (risk?.labelEs ?: "—"),
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = risk?.descEs ?: "Señal insuficiente para cribado",
                color = color.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, color: Color) {
    Column(modifier = Modifier
        .background(Color(0xFF0F171C), RoundedCornerShape(4.dp))
        .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text(label, color = color.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text(value, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
