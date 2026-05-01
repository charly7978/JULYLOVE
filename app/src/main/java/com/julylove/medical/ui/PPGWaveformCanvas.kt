package com.julylove.medical.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.julylove.medical.signal.PPGSample
import com.julylove.medical.signal.RhythmAnalysisEngine
import com.julylove.medical.ui.theme.MedicalGreen
import com.julylove.medical.ui.theme.MedicalGrid
import com.julylove.medical.ui.theme.MedicalRed

@Composable
fun PPGWaveformCanvas(
    samples: List<PPGSample>,
    isMeasuring: Boolean,
    rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
    modifier: Modifier = Modifier
) {
    // Use a secondary color for the "ghost" or background grid for medical feel
    val gridColor = MedicalGrid.copy(alpha = 0.5f)
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // 1. Draw Technical Grid (Medical Grade)
        val majorGridStep = 50.dp.toPx()
        val minorGridStep = majorGridStep / 5

        // Vertical lines
        for (x in 0..(width / minorGridStep).toInt()) {
            val color = if (x % 5 == 0) MedicalGrid else gridColor
            val stroke = if (x % 5 == 0) 1.5f else 0.5f
            drawLine(color, Offset(x * minorGridStep, 0f), Offset(x * minorGridStep, height), strokeWidth = stroke)
        }
        // Horizontal lines
        for (y in 0..(height / minorGridStep).toInt()) {
            val color = if (y % 5 == 0) MedicalGrid else gridColor
            val stroke = if (y % 5 == 0) 1.5f else 0.5f
            drawLine(color, Offset(0f, y * minorGridStep), Offset(width, y * minorGridStep), strokeWidth = stroke)
        }

        if (!isMeasuring || samples.isEmpty()) return@Canvas

        val traceColor = when (rhythmStatus) {
            RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA -> MedicalRed
            else -> MedicalGreen
        }

        // 2. Waveform Rendering
        val maxVisibleSamples = samples.size
        val dx = width / maxVisibleSamples

        val path = Path()
        
        // Auto-scaling logic with headroom
        val visibleValues = samples.map { it.filteredValue }
        val minVal = visibleValues.minOrNull() ?: -1f
        val maxVal = visibleValues.maxOrNull() ?: 1f
        val range = (maxVal - minVal).coerceAtLeast(0.1f)
        val scale = (height * 0.4f) / range // Waveform occupies 40% of height for clarity

        samples.forEachIndexed { index, sample ->
            val x = index * dx
            // Invert Y because Canvas origin is top-left
            val y = centerY - (sample.filteredValue * scale)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            
            // Draw Beat Detection Markers (Sistolic Peaks)
            if (sample.isPeak) {
                drawCircle(
                    color = MedicalRed,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y)
                )
                // Technical vertical marker for peak
                drawLine(
                    color = MedicalRed.copy(alpha = 0.3f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1f
                )
            }
        }

        // Draw the main PPG trace
        drawPath(
            path = path,
            color = traceColor,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )

        // Add a "glow" effect to the trace for a CRT/Monitor feel
        drawPath(
            path = path,
            color = traceColor.copy(alpha = 0.3f),
            style = Stroke(width = 6.dp.toPx())
        )
    }
}
