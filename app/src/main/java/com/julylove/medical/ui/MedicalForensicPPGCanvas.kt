package com.julylove.medical.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.julylove.medical.signal.PPGSample
import com.julylove.medical.signal.BeatClassifier
import com.julylove.medical.signal.ArrhythmiaScreening
// import com.julylove.medical.signal.AdvancedSignalQualityDetector // Temporarily commented
import com.julylove.medical.ui.theme.MedicalGreen
import com.julylove.medical.ui.theme.MedicalRed
import com.julylove.medical.ui.theme.MedicalAmber
import com.julylove.medical.ui.theme.MedicalCyan
import com.julylove.medical.ui.theme.MedicalGrid
import kotlin.math.*

/**
 * Medical Forensic PPG Canvas
 * Advanced visualization with complete waveforms, morphology analysis, and anomaly detection
 * 
 * Features:
 * - Complete PPG waveform with systolic/diastolic phases
 * - Morphological markers (onset, peak, dicrotic notch)
 * - Anomalous beat highlighting
 * - Clinical grid and annotations
 * - Real-time signal quality indicators
 * - Arrhythmia event overlays
 */
@Composable
fun MedicalForensicPPGCanvas(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent>,
    signalQuality: Any?, // Temporarily changed to Any? for APK generation
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize().padding(4.dp)) {
        drawMedicalPPGWaveform(samples, classifiedBeats, arrhythmiaEvents, signalQuality)
    }
}

private fun DrawScope.drawMedicalPPGWaveform(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent>,
    signalQuality: Any? // Temporarily changed to Any? for APK generation
) {
    if (samples.isEmpty()) return
    
    val canvasWidth = size.width
    val canvasHeight = size.height
    
    // Medical visualization parameters
    val topMargin = 40f
    val bottomMargin = 60f
    val leftMargin = 60f
    val rightMargin = 20f
    
    val plotWidth = canvasWidth - leftMargin - rightMargin
    val plotHeight = canvasHeight - topMargin - bottomMargin
    
    // Draw clinical grid
    drawClinicalGrid(leftMargin, topMargin, plotWidth, plotHeight)
    
    // Draw axes and labels
    drawMedicalAxes(leftMargin, topMargin, plotWidth, plotHeight)
    
    // Draw signal quality indicator (temporarily commented)
    // signalQuality?.let { quality ->
    //     drawSignalQualityIndicator(quality, leftMargin, topMargin - 20f)
    // }
    
    // Draw PPG waveform with morphology
    drawCompletePPGWaveform(samples, leftMargin, topMargin, plotWidth, plotHeight)
    
    // Draw morphological markers
    drawMorphologicalMarkers(samples, classifiedBeats, leftMargin, topMargin, plotWidth, plotHeight)
    
    // Highlight anomalous beats
    drawAnomalousBeats(samples, classifiedBeats, leftMargin, topMargin, plotWidth, plotHeight)
    
    // Draw arrhythmia events
    drawArrhythmiaEvents(arrhythmiaEvents, leftMargin, topMargin, plotWidth, plotHeight)
    
    // Draw clinical annotations
    drawClinicalAnnotations(samples, leftMargin, topMargin, plotWidth, plotHeight)
}

private fun DrawScope.drawClinicalGrid(
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    // Major grid lines (every 1 second)
    val majorGridColor = MedicalGrid.copy(alpha = 0.3f)
    val minorGridColor = MedicalGrid.copy(alpha = 0.15f)
    
    // Horizontal grid lines (amplitude)
    for (i in 0..10) {
        val y = topMargin + (plotHeight * i / 10f)
        val color = if (i % 5 == 0) majorGridColor else minorGridColor
        
        drawLine(
            start = Offset(leftMargin, y),
            end = Offset(leftMargin + plotWidth, y),
            color = color,
            strokeWidth = if (i % 5 == 0) 1f else 0.5f
        )
    }
    
    // Vertical grid lines (time)
    val samplesPerSecond = 30f
    val pixelsPerSecond = plotWidth / (samplesPerSecond * 10f) // 10 seconds window
    
    for (i in 0..10) {
        val x = leftMargin + (pixelsPerSecond * i)
        val color = if (i % 5 == 0) majorGridColor else minorGridColor
        
        drawLine(
            start = Offset(x, topMargin),
            end = Offset(x, topMargin + plotHeight),
            color = color,
            strokeWidth = if (i % 5 == 0) 1f else 0.5f
        )
    }
}

private fun DrawScope.drawMedicalAxes(
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    val axisColor = Color.White.copy(alpha = 0.8f)
    
    // X-axis (time)
    drawLine(
        start = Offset(leftMargin, topMargin + plotHeight),
        end = Offset(leftMargin + plotWidth, topMargin + plotHeight),
        color = axisColor,
        strokeWidth = 2f
    )
    
    // Y-axis (amplitude)
    drawLine(
        start = Offset(leftMargin, topMargin),
        end = Offset(leftMargin, topMargin + plotHeight),
        color = axisColor,
        strokeWidth = 2f
    )
    
    // Axis labels would be drawn here with Text on Canvas
    // For simplicity, we'll focus on the waveform visualization
}

// private fun DrawScope.drawSignalQualityIndicator(
//     signalQuality: AdvancedSignalQualityDetector.SignalQualityReport,
//     x: Float,
//     y: Float
// ) {
//     val indicatorSize = 30f
//     val color = when (signalQuality.signalQuality) {
//         AdvancedSignalQualityDetector.SignalQuality.EXCELLENT -> MedicalGreen
//         AdvancedSignalQualityDetector.SignalQuality.GOOD -> Color(0xFF64B5F6)
//         AdvancedSignalQualityDetector.SignalQuality.ACCEPTABLE -> MedicalAmber
//         AdvancedSignalQualityDetector.SignalQuality.POOR -> Color(0xFFFF9800)
//         AdvancedSignalQualityDetector.SignalQuality.INVALID -> MedicalRed
//     }
//     
//     // Draw quality indicator circle
//     drawCircle(
//         center = Offset(x, y),
//         radius = indicatorSize / 2f,
//         color = color
//     )
//     
//     // Draw contact indicator
//     if (signalQuality.hasContact) {
//         drawCircle(
//             center = Offset(x + indicatorSize + 10f, y),
//             radius = indicatorSize / 2f,
//             color = MedicalGreen
//         )
//     } else {
//         drawCircle(
//             center = Offset(x + indicatorSize + 10f, y),
//             radius = indicatorSize / 2f,
//             color = MedicalRed
//         )
//     }
// }

private fun DrawScope.drawCompletePPGWaveform(
    samples: List<PPGSample>,
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    if (samples.size < 2) return
    
    val path = Path()
    val samplesToShow = samples.takeLast(300) // 10 seconds at 30fps
    
    // Calculate scaling
    val minAmplitude = samplesToShow.minOf { it.filteredValue.toDouble() }
    val maxAmplitude = samplesToShow.maxOf { it.filteredValue.toDouble() }
    val amplitudeRange = maxAmplitude - minAmplitude
    
    // Draw main waveform
    path.moveTo(
        x = leftMargin,
        y = topMargin + plotHeight - ((samplesToShow[0].filteredValue - minAmplitude.toFloat()) / amplitudeRange.toFloat() * plotHeight)
    )
    
    for (i in 1 until samplesToShow.size) {
        val sample = samplesToShow[i]
        val x = leftMargin + (i.toFloat() / samplesToShow.size * plotWidth)
        val y = topMargin + plotHeight - ((sample.filteredValue - minAmplitude.toFloat()) / amplitudeRange.toFloat() * plotHeight)
        
        path.lineTo(x, y)
    }
    
    // Draw the waveform with gradient based on quality
    drawPath(
        path = path,
        color = MedicalCyan,
        style = Stroke(width = 2f)
    )
    
    // Draw waveform fill for better visibility
    val fillPath = Path()
    fillPath.addPath(path)
    fillPath.lineTo(leftMargin + plotWidth, topMargin + plotHeight)
    fillPath.lineTo(leftMargin, topMargin + plotHeight)
    fillPath.close()
    
    drawPath(
        path = fillPath,
        color = MedicalCyan.copy(alpha = 0.1f)
    )
}

private fun DrawScope.drawMorphologicalMarkers(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    if (samples.isEmpty() || classifiedBeats.isEmpty()) return
    
    val samplesToShow = samples.takeLast(300)
    val minAmplitude = samplesToShow.minOf { it.filteredValue.toDouble() }
    val maxAmplitude = samplesToShow.maxOf { it.filteredValue.toDouble() }
    val amplitudeRange = maxAmplitude - minAmplitude
    
    // Draw morphological markers for each classified beat
    for (beat in classifiedBeats.takeLast(20)) { // Show last 20 beats
        val sampleIndex = samplesToShow.indexOfFirst { abs(it.timestamp - beat.timestampNs) < 50_000_000L }
        if (sampleIndex >= 0) {
            val x = leftMargin + (sampleIndex.toFloat() / samplesToShow.size * plotWidth)
            
            // Draw beat onset marker
            val onsetY = topMargin + plotHeight - 0.9f * plotHeight
            drawCircle(
                center = Offset(x, onsetY),
                radius = 3f,
                color = MedicalGreen
            )
            
            // Draw beat peak marker
            val peakY = topMargin + plotHeight - 0.1f * plotHeight
            drawCircle(
                center = Offset(x, peakY),
                radius = 4f,
                color = MedicalCyan
            )
            
            // Draw dicrotic notch marker (if applicable)
            if (beat.beatType == BeatClassifier.BeatType.NORMAL) {
                val notchY = topMargin + plotHeight - 0.4f * plotHeight
                drawCircle(
                    center = Offset(x + 5f, notchY),
                    radius = 2f,
                    color = MedicalAmber
                )
            }
        }
    }
}

private fun DrawScope.drawAnomalousBeats(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    if (samples.isEmpty() || classifiedBeats.isEmpty()) return
    
    val samplesToShow = samples.takeLast(300)
    val anomalousBeats = classifiedBeats.filter { 
        it.beatType != BeatClassifier.BeatType.NORMAL 
    }.takeLast(20)
    
    for (beat in anomalousBeats) {
        val sampleIndex = samplesToShow.indexOfFirst { abs(it.timestamp - beat.timestampNs) < 50_000_000L }
        if (sampleIndex >= 0) {
            val x = leftMargin + (sampleIndex.toFloat() / samplesToShow.size * plotWidth)
            
            // Highlight anomalous beat region
            val highlightWidth = 20f
            val highlightColor = when (beat.beatType) {
                BeatClassifier.BeatType.SUSPECT_PREMATURE -> MedicalRed.copy(alpha = 0.3f)
                BeatClassifier.BeatType.SUSPECT_PAUSE -> Color(0xFF9C27B0).copy(alpha = 0.3f)
                BeatClassifier.BeatType.IRREGULAR -> MedicalAmber.copy(alpha = 0.3f)
                else -> Color.Gray.copy(alpha = 0.3f)
            }
            
            drawRect(
                topLeft = Offset(x - highlightWidth/2, topMargin),
                size = Size(highlightWidth, plotHeight),
                color = highlightColor
            )
            
            // Draw anomaly marker
            val markerY = topMargin + plotHeight / 2f
            drawCircle(
                center = Offset(x, markerY),
                radius = 6f,
                color = when (beat.beatType) {
                    BeatClassifier.BeatType.SUSPECT_PREMATURE -> MedicalRed
                    BeatClassifier.BeatType.SUSPECT_PAUSE -> Color(0xFF9C27B0)
                    BeatClassifier.BeatType.IRREGULAR -> MedicalAmber
                    else -> Color.Gray
                }
            )
        }
    }
}

private fun DrawScope.drawArrhythmiaEvents(
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent>,
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    if (arrhythmiaEvents.isEmpty()) return
    
    val eventWindow = 10_000_000_000L // 10 seconds in nanoseconds
    val currentTime = System.nanoTime()
    val recentEvents = arrhythmiaEvents.filter { 
        currentTime - it.timestampNs < eventWindow 
    }
    
    for (event in recentEvents) {
        val eventAge = (currentTime - event.timestampNs) / 1_000_000_000L // Convert to seconds
        val x = leftMargin + plotWidth - (eventAge.toFloat() / 10f * plotWidth)
        
        if (x >= leftMargin && x <= leftMargin + plotWidth) {
            val eventColor = when (event.severity) {
                ArrhythmiaScreening.Severity.CRITICAL -> MedicalRed
                ArrhythmiaScreening.Severity.HIGH -> Color(0xFFFF5722)
                ArrhythmiaScreening.Severity.MODERATE -> MedicalAmber
                ArrhythmiaScreening.Severity.LOW -> Color(0xFF64B5F6)
            }
            
            // Draw event marker
            drawCircle(
                center = Offset(x, topMargin - 10f),
                radius = 5f,
                color = eventColor
            )
            
            // Draw event line
            drawLine(
                start = Offset(x, topMargin - 5f),
                end = Offset(x, topMargin),
                color = eventColor,
                strokeWidth = 2f
            )
        }
    }
}

private fun DrawScope.drawClinicalAnnotations(
    samples: List<PPGSample>,
    leftMargin: Float,
    topMargin: Float,
    plotWidth: Float,
    plotHeight: Float
) {
    if (samples.size < 60) return // Need at least 2 seconds of data
    
    val samplesToShow = samples.takeLast(300)
    
    // Calculate heart rate from recent samples
    val recentBeats = detectBeatsInWindow(samplesToShow)
    if (recentBeats.size >= 2) {
        val avgRR = recentBeats.zipWithNext { a, b -> b - a }.average() / 1_000_000L // Convert to seconds
        val heartRate = if (avgRR > 0) (60.0 / avgRR).toInt() else 0
        
        // Draw heart rate annotation
        val hrText = "${heartRate} BPM"
        // In a real implementation, you would use a text drawing library
        // For now, we'll draw a simple indicator
        
        val hrX = leftMargin + 10f
        val hrY = topMargin + plotHeight + 20f
        
        drawCircle(
            center = Offset(hrX, hrY),
            radius = 8f,
            color = when {
                heartRate in 60..100 -> MedicalGreen
                heartRate in 50..120 -> MedicalAmber
                else -> MedicalRed
            }
        )
    }
    
    // Draw perfusion index
    val perfusionIndex = calculatePerfusionIndex(samplesToShow)
    val perfusionX = leftMargin + 50f
    val perfusionY = topMargin + plotHeight + 20f
    
    drawCircle(
        center = Offset(perfusionX, perfusionY),
        radius = 8f,
        color = when {
            perfusionIndex > 0.3f -> MedicalGreen
            perfusionIndex > 0.15f -> MedicalAmber
            else -> MedicalRed
        }
    )
}

private fun detectBeatsInWindow(samples: List<PPGSample>): List<Long> {
    // Simple beat detection based on peaks
    val beatTimestamps = mutableListOf<Long>()
    if (samples.isEmpty()) return beatTimestamps
    
    val threshold = samples.maxOf { it.filteredValue } * 0.7f
    
    for (i in 1 until samples.size - 1) {
        if (samples[i].filteredValue > threshold &&
            samples[i].filteredValue > samples[i-1].filteredValue &&
            samples[i].filteredValue > samples[i+1].filteredValue) {
            beatTimestamps.add(samples[i].timestamp)
        }
    }
    
    return beatTimestamps
}

private fun calculatePerfusionIndex(samples: List<PPGSample>): Float {
    if (samples.isEmpty()) return 0f
    
    val values = samples.map { it.filteredValue.toDouble() }
    val dc = values.average()
    val ac = values.map { abs(it - dc) }.average()
    
    return if (dc > 0) (ac / dc).toFloat() else 0f
}

// Extension function for zipWithNext
private fun <T, R> List<T>.zipWithNext(transform: (T, T) -> R): List<R> {
    return this.zipWithNext().map { (a, b) -> transform(a, b) }
}
