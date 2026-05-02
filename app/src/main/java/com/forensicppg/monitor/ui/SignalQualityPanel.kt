package com.forensicppg.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.domain.VitalReading
import com.forensicppg.monitor.ppg.PpgSignalQuality

@Composable
fun SignalQualityPanel(reading: VitalReading, modifier: Modifier = Modifier) {
    val sqiComposer = remember { PpgSignalQuality() }
    val band = sqiComposer.band(reading.sqi)
    val color = when (band) {
        PpgSignalQuality.SqBand.EXCELLENT -> Color(0xFF22FFAA)
        PpgSignalQuality.SqBand.ACCEPTABLE -> Color(0xFFAAFF55)
        PpgSignalQuality.SqBand.DEGRADED -> Color(0xFFFFAA22)
        PpgSignalQuality.SqBand.INVALID -> Color(0xFFFF3344)
    }
    Box(
        modifier = modifier
            .background(Color(0xFF0A1014), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SQI",
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.fillMaxWidth(0.05f))
                Text(
                    text = PpgSignalQuality.bandLabelEs(band),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(Color(0xFF1A2128), RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(reading.sqi.toFloat().coerceIn(0f, 1f))
                        .height(10.dp)
                        .background(color, RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = reading.messagePrimary.ifBlank {
                    reading.validityState.labelEs
                },
                color = Color(0xFFE6FFF4),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
