package com.julylove.medical.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.julylove.medical.signal.PPGSample
import com.julylove.medical.ui.theme.MedicalGreen
import com.julylove.medical.ui.theme.MedicalGrid
import com.julylove.medical.ui.theme.MedicalRed

@Composable
fun PPGWaveformCanvas(
    samples: List<PPGSample>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Draw Grid
        val gridStep = 40.dp.toPx()
        for (x in 0 until (width / gridStep).toInt()) {
            drawLine(MedicalGrid, Offset(x * gridStep, 0f), Offset(x * gridStep, height), strokeWidth = 1f)
        }
        for (y in 0 until (height / gridStep).toInt()) {
            drawLine(MedicalGrid, Offset(0f, y * gridStep), Offset(width, y * gridStep), strokeWidth = 1f)
        }

        if (samples.isEmpty()) return@Canvas

        val maxVisibleSamples = samples.size
        val dx = width / maxVisibleSamples

        val path = Path()
        var lastX = 0f
        
        // Find local min/max for scaling within the visible window
        val minVal = samples.minOfOrNull { it.filteredValue } ?: -1f
        val maxVal = samples.maxOfOrNull { it.filteredValue } ?: 1f
        val range = (maxVal - minVal).coerceAtLeast(0.01f)
        val scale = (height * 0.6f) / range

        samples.forEachIndexed { index, sample ->
            val x = index * dx
            val y = centerY - (sample.filteredValue * scale)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            
            // Draw Beat Markers
            if (sample.isPeak) {
                drawCircle(
                    color = MedicalRed,
                    radius = 4.dp.toPx(),
                    center = Offset(x, y)
                )
            }
            
            lastX = x
        }

        drawPath(
            path = path,
            color = MedicalGreen,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
