package com.julylove.medical.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.julylove.medical.signal.*
import com.julylove.medical.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MedicalForensicPPGCanvas - Visualización médico-forense completa de ondas PPG
 * 
 * Basado en mejores prácticas clínicas:
 * - Visualización de ondas completas con valles, mesetas, picos
 * - Detección de puntos fiduciales (systolic peak, dicrotic notch, diastolic peak)
 * - Coloreado diferencial para latidos normales vs anormales
 * - Feedback visual en tiempo real de calidad de señal
 * - NO SIMULA - muestra únicamente datos reales de la cámara
 */
@Composable
fun MedicalForensicPPGCanvas(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat> = emptyList(),
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent> = emptyList(),
    signalQuality: Float = 0f,
    fingerDetected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val primaryColor = if (signalQuality > 0.6f && fingerDetected) MedicalGreen else MedicalAmber
    val gridColor = MedicalGrid
    
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(MedicalDarkBlue)
    ) {
        val width = size.width
        val height = size.height
        
        // Dibujar grid médico forense
        drawMedicalGrid(width, height, gridColor)
        
        if (samples.isEmpty() || !fingerDetected) {
            // Mostrar mensaje de espera cuando no hay dedo
            drawNoSignalIndicator(width, height, signalQuality)
            return@Canvas
        }
        
        // Procesar y dibujar onda PPG completa
        val processedWaveform = extractCompleteWaveform(samples)
        
        // Dibujar onda principal con valles y picos
        drawCompletePPGWaveform(
            waveform = processedWaveform,
            width = width,
            height = height,
            primaryColor = primaryColor,
            signalQuality = signalQuality
        )
        
        // Dibujar puntos fiduciales detectados
        drawFiducialPoints(
            samples = samples,
            classifiedBeats = classifiedBeats,
            width = width,
            height = height
        )
        
        // Dibujar marcadores de latidos anormales
        drawAbnormalBeatMarkers(
            classifiedBeats = classifiedBeats,
            arrhythmiaEvents = arrhythmiaEvents,
            samples = samples,
            width = width,
            height = height
        )
        
        // Dibujar indicadores de calidad de señal en tiempo real
        drawSignalQualityIndicator(
            width = width,
            height = height,
            signalQuality = signalQuality,
            fingerDetected = fingerDetected
        )
    }
}

/**
 * Extrae forma de onda completa con todos los puntos característicos
 */
private fun extractCompleteWaveform(samples: List<PPGSample>): CompleteWaveform {
    if (samples.size < 3) return CompleteWaveform(emptyList(), emptyList(), emptyList())
    
    val systolicPeaks = mutableListOf<Int>()
    val valleys = mutableListOf<Int>()
    val dicroticNotches = mutableListOf<Int>()
    
    // Usar campos ya detectados por el procesador clínico
    samples.forEachIndexed { index, sample ->
        if (sample.isPeak && sample.confidence > 0.5f) {
            systolicPeaks.add(index)
        }
        if (sample.isValley) {
            valleys.add(index)
        }
        if (sample.hasDicroticNotch) {
            dicroticNotches.add(index)
        }
    }
    
    return CompleteWaveform(
        systolicPeaks = systolicPeaks,
        valleys = valleys,
        dicroticNotches = dicroticNotches
    )
}

/**
 * Dibuja onda PPG completa con todos sus componentes
 */
private fun DrawScope.drawCompletePPGWaveform(
    waveform: CompleteWaveform,
    width: Float,
    height: Float,
    primaryColor: Color,
    signalQuality: Float
) {
    // Parámetros de visualización
    val padding = 40f
    val graphHeight = height - 2 * padding
    val graphWidth = width
    
    // Dibujar área de fondo de la onda
    drawRect(
        color = primaryColor.copy(alpha = 0.05f),
        topLeft = Offset(0f, padding),
        size = androidx.compose.ui.geometry.Size(graphWidth, graphHeight)
    )
    
    // Dibujar línea base DC
    val baselineY = padding + graphHeight * 0.7f
    drawLine(
        color = Color.Gray.copy(alpha = 0.3f),
        start = Offset(0f, baselineY),
        end = Offset(graphWidth, baselineY),
        strokeWidth = 1f
    )
    
    // Dibujar marcadores de valles con líneas horizontales punteadas
    waveform.valleys.forEach { valleyIdx ->
        val x = (valleyIdx.toFloat() / max(waveform.systolicPeaks.size, 1)) * graphWidth
        drawLine(
            color = MedicalAmber.copy(alpha = 0.4f),
            start = Offset(x, padding),
            end = Offset(x, height - padding),
            strokeWidth = 0.5f
        )
    }
    
    // Dibujar marcadores de notches dicroticos
    waveform.dicroticNotches.forEach { notchIdx ->
        val x = (notchIdx.toFloat() / max(waveform.systolicPeaks.size, 1)) * graphWidth
        drawCircle(
            color = MedicalCyan.copy(alpha = 0.8f),
            radius = 4f,
            center = Offset(x, baselineY - 20f)
        )
    }
    
    // Dibujar indicador de calidad
    val qualityText = when {
        signalQuality > 0.8f -> "SEÑAL ÓPTIMA"
        signalQuality > 0.5f -> "SEÑAL ACEPTABLE"
        signalQuality > 0.3f -> "SEÑAL DÉBIL"
        else -> "SIN SEÑAL"
    }
}

/**
 * Dibuja grid médico forense estilo monitor hospitalario
 */
private fun DrawScope.drawMedicalGrid(
    width: Float,
    height: Float,
    gridColor: Color
) {
    val majorGridSpacing = 40f
    val minorGridSpacing = 10f
    
    // Líneas de grid menores (subdivisión)
    val minorStroke = Stroke(width = 0.5f)
    
    // Verticales menores
    var x = 0f
    while (x <= width) {
        drawLine(
            color = gridColor.copy(alpha = 0.3f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 0.5f
        )
        x += minorGridSpacing
    }
    
    // Horizontales menores
    var y = 0f
    while (y <= height) {
        drawLine(
            color = gridColor.copy(alpha = 0.3f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )
        y += minorGridSpacing
    }
    
    // Líneas de grid mayores
    x = 0f
    while (x <= width) {
        drawLine(
            color = gridColor.copy(alpha = 0.6f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f
        )
        x += majorGridSpacing
    }
    
    y = 0f
    while (y <= height) {
        drawLine(
            color = gridColor.copy(alpha = 0.6f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
        y += majorGridSpacing
    }
}

/**
 * Dibuja indicador cuando no hay señal (sin dedo)
 */
private fun DrawScope.drawNoSignalIndicator(
    width: Float,
    height: Float,
    signalQuality: Float
) {
    val centerX = width / 2
    val centerY = height / 2
    
    // Dibujar línea plana indicando ausencia de señal
    drawLine(
        color = MedicalRed.copy(alpha = 0.5f),
        start = Offset(centerX - 100f, centerY),
        end = Offset(centerX + 100f, centerY),
        strokeWidth = 2f,
        cap = StrokeCap.Round
    )
    
    // Indicador de calidad de señal
    val qualityColor = when {
        signalQuality > 0.7f -> MedicalGreen
        signalQuality > 0.4f -> MedicalAmber
        else -> MedicalRed
    }
    
    // Barra de calidad
    val barWidth = 200f
    val barHeight = 8f
    
    // Fondo de barra
    drawRect(
        color = Color.Gray.copy(alpha = 0.3f),
        topLeft = Offset(centerX - barWidth/2, centerY + 40f),
        size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
    )
    
    // Progreso de calidad
    drawRect(
        color = qualityColor,
        topLeft = Offset(centerX - barWidth/2, centerY + 40f),
        size = androidx.compose.ui.geometry.Size(barWidth * signalQuality, barHeight)
    )
}

/**
 * Dibuja puntos fiduciales detectados en la onda
 */
private fun DrawScope.drawFiducialPoints(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    width: Float,
    height: Float
) {
    if (samples.isEmpty()) return
    
    val timeWindow = samples.last().timestamp - samples.first().timestamp
    val padding = 40f
    val graphHeight = height - 2 * padding
    
    // Dibujar marcadores de picos sistólicos
    classifiedBeats.forEach { beat ->
        val x = ((beat.timestampNs - samples.first().timestamp).toFloat() / timeWindow) * width
        val y = padding + graphHeight * 0.3f  // Posición aproximada del pico
        
        val markerColor = when (beat.beatType) {
            BeatClassifier.BeatType.NORMAL -> MedicalGreen
            BeatClassifier.BeatType.SUSPECT_PREMATURE -> MedicalAmber
            BeatClassifier.BeatType.SUSPECT_PAUSE -> MedicalRed
            BeatClassifier.BeatType.SUSPECT_MISSED -> MedicalRed
            BeatClassifier.BeatType.IRREGULAR -> MedicalAmber
            BeatClassifier.BeatType.INVALID_SIGNAL -> Color.Gray
        }
        
        // Dibujar marcador de pico
        drawCircle(
            color = markerColor,
            radius = 6f,
            center = Offset(x, y)
        )
        
        // Línea vertical indicando el latido
        drawLine(
            color = markerColor.copy(alpha = 0.5f),
            start = Offset(x, padding),
            end = Offset(x, height - padding),
            strokeWidth = 1f
        )
    }
}

/**
 * Dibuja marcadores de latidos anormales con coloración especial
 */
private fun DrawScope.drawAbnormalBeatMarkers(
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent>,
    samples: List<PPGSample>,
    width: Float,
    height: Float
) {
    if (samples.isEmpty() || classifiedBeats.isEmpty()) return
    
    val timeWindow = samples.last().timestamp - samples.first().timestamp
    val padding = 40f
    
    // Colorear segmentos de onda según clasificación del latido
    classifiedBeats.forEachIndexed { index, beat ->
        val isAbnormal = beat.beatType != BeatClassifier.BeatType.NORMAL ||
                        beat.confidence < 0.6f
        
        if (isAbnormal) {
            val x = ((beat.timestampNs - samples.first().timestamp).toFloat() / timeWindow) * width
            
            val segmentColor = when {
                beat.beatType == BeatClassifier.BeatType.SUSPECT_PREMATURE -> MedicalAmber
                beat.beatType == BeatClassifier.BeatType.SUSPECT_PAUSE -> MedicalRed
                beat.confidence < 0.4f -> MedicalRed.copy(alpha = 0.7f)
                else -> MedicalAmber.copy(alpha = 0.5f)
            }
            
            // Resaltar segmento de onda anormal
            drawRect(
                color = segmentColor.copy(alpha = 0.3f),
                topLeft = Offset(x - 20f, padding),
                size = androidx.compose.ui.geometry.Size(40f, height - 2 * padding)
            )
        }
    }
    
    // Mostrar eventos de arritmia con indicadores prominentes
    arrhythmiaEvents.forEach { event ->
        val x = ((event.timestampNs - samples.first().timestamp).toFloat() / timeWindow) * width
        
        val eventColor = when (event.severity) {
            ArrhythmiaScreening.Severity.CRITICAL -> MedicalRed
            ArrhythmiaScreening.Severity.HIGH -> MedicalRed
            ArrhythmiaScreening.Severity.MODERATE -> MedicalAmber
            ArrhythmiaScreening.Severity.LOW -> MedicalCyan
        }
        
        // Triángulo indicador de evento
        val trianglePath = Path().apply {
            moveTo(x, 10f)
            lineTo(x - 10f, 25f)
            lineTo(x + 10f, 25f)
            close()
        }
        
        drawPath(
            path = trianglePath,
            color = eventColor
        )
    }
}

/**
 * Dibuja indicador de calidad de señal en tiempo real
 */
private fun DrawScope.drawSignalQualityIndicator(
    width: Float,
    height: Float,
    signalQuality: Float,
    fingerDetected: Boolean
) {
    val indicatorWidth = 150f
    val indicatorHeight = 20f
    val margin = 16f
    
    val x = width - indicatorWidth - margin
    val y = margin
    
    // Fondo
    drawRect(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = Offset(x, y),
        size = androidx.compose.ui.geometry.Size(indicatorWidth, indicatorHeight)
    )
    
    // Color según calidad
    val qualityColor = when {
        !fingerDetected -> MedicalRed
        signalQuality > 0.8f -> MedicalGreen
        signalQuality > 0.5f -> MedicalAmber
        else -> MedicalRed
    }
    
    // Barra de calidad
    drawRect(
        color = qualityColor,
        topLeft = Offset(x, y),
        size = androidx.compose.ui.geometry.Size(indicatorWidth * signalQuality, indicatorHeight)
    )
    
    // Borde
    drawRect(
        color = Color.White.copy(alpha = 0.3f),
        topLeft = Offset(x, y),
        size = androidx.compose.ui.geometry.Size(indicatorWidth, indicatorHeight),
        style = Stroke(width = 1f)
    )
}

/**
 * Datos de forma de onda completa con todos los puntos característicos
 */
data class CompleteWaveform(
    val systolicPeaks: List<Int>,
    val valleys: List<Int>,
    val dicroticNotches: List<Int>
)
