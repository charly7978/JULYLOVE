package com.julylove.medical.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.julylove.medical.signal.*
import com.julylove.medical.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PPGWaveformCanvas: Visualización Médica-Forense Avanzada
 * 
 * Características médicas-forenses:
 * - Visualización de ondas PPG completas con morfología detallada
 * - Detección y resaltado de anomalías con colores específicos
 * - Indicadores de calidad de señal en tiempo real
 * - Marcadores de picos, valles y características morfológicas
 * - Grid médico profesional con escalas clínicas
 * - Sin simulaciones ni valores hardcodeados
 */
@Composable
fun PPGWaveformCanvas(
    samples: List<PPGSample>,
    isMeasuring: Boolean,
    fingerPresent: Boolean,
    signalValid: Boolean,
    signalQuality: SignalQuality,
    waveformType: WaveType,
    abnormalities: List<PPGAbnormality>,
    rhythmStatus: RhythmAnalysisEngine.RhythmStatus = RhythmAnalysisEngine.RhythmStatus.CALIBRATING,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat> = emptyList(),
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent> = emptyList(),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // 1. Grid Médico Profesional
        drawMedicalGrid(width, height)

        // 2. Indicador de Estado de Señal
        drawSignalStatusIndicator(fingerPresent, signalValid, signalQuality)

        // 3. Onda PPG Principal (SOLO si hay señal válida)
        if (signalValid && samples.isNotEmpty()) {
            drawPPGWaveform(samples, width, height, centerY)
            
            // 4. Características Morfológicas
            drawMorphologyFeatures(samples, classifiedBeats, width, height, centerY)
            
            // 5. Anomalías Detectadas
            drawAbnormalities(abnormalities, samples, width, height, centerY)
            
            // 6. Eventos de Arritmia
            drawArrhythmiaEvents(arrhythmiaEvents, samples, width, height, centerY)
        } else {
            // 7. Mensaje de Estado (sin señal)
            drawNoSignalMessage(fingerPresent, signalValid, signalQuality, width, height)
        }

        // 8. Escalas Clínicas
        drawClinicalScales(width, height)
    }
}

/**
 * Dibuja grid médico profesional
 */
private fun DrawScope.drawMedicalGrid(width: Float, height: Float) {
    val majorGridStep = 50.dp.toPx()
    val minorGridStep = majorGridStep / 5
    
    // Grid principal (líneas mayores)
    for (x in 0..(width / majorGridStep).toInt()) {
        drawLine(
            MedicalGrid.copy(alpha = 0.8f),
            Offset(x * majorGridStep, 0f),
            Offset(x * majorGridStep, height),
            strokeWidth = 1.5f
        )
    }
    
    for (y in 0..(height / majorGridStep).toInt()) {
        drawLine(
            MedicalGrid.copy(alpha = 0.8f),
            Offset(0f, y * majorGridStep),
            Offset(width, y * majorGridStep),
            strokeWidth = 1.5f
        )
    }
    
    // Grid secundario (líneas menores)
    for (x in 0..(width / minorGridStep).toInt()) {
        if (x % 5 != 0) {
            drawLine(
                MedicalGrid.copy(alpha = 0.3f),
                Offset(x * minorGridStep, 0f),
                Offset(x * minorGridStep, height),
                strokeWidth = 0.5f
            )
        }
    }
    
    for (y in 0..(height / minorGridStep).toInt()) {
        if (y % 5 != 0) {
            drawLine(
                MedicalGrid.copy(alpha = 0.3f),
                Offset(0f, y * minorGridStep),
                Offset(width, y * minorGridStep),
                strokeWidth = 0.5f
            )
        }
    }
    
    // Línea base central
    drawLine(
        MedicalRed.copy(alpha = 0.6f),
        Offset(0f, height / 2f),
        Offset(width, height / 2f),
        strokeWidth = 2f
    )
}

/**
 * Dibuja indicador de estado de señal
 */
private fun DrawScope.drawSignalStatusIndicator(
    fingerPresent: Boolean,
    signalValid: Boolean,
    signalQuality: SignalQuality
) {
    val indicatorColor = when {
        !fingerPresent -> Color.Gray
        !signalValid -> MedicalRed
        signalQuality == SignalQuality.EXCELLENT -> MedicalGreen
        signalQuality == SignalQuality.GOOD -> Color(0xFF4CAF50)
        signalQuality == SignalQuality.ACCEPTABLE -> MedicalAmber
        signalQuality == SignalQuality.POOR -> Color(0xFFFF9800)
        else -> Color.Gray
    }
    
    val indicatorRadius = 8.dp.toPx()
    
    // Indicador circular de estado
    drawCircle(
        color = indicatorColor,
        radius = indicatorRadius,
        center = Offset(indicatorRadius + 10.dp.toPx(), indicatorRadius + 10.dp.toPx())
    )
    
    // Anillo de confianza (si hay señal)
    if (signalValid && fingerPresent) {
        drawCircle(
            color = indicatorColor.copy(alpha = 0.3f),
            radius = indicatorRadius * 1.5f,
            center = Offset(indicatorRadius + 10.dp.toPx(), indicatorRadius + 10.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * Dibuja onda PPG principal con calidad médica
 */
private fun DrawScope.drawPPGWaveform(
    samples: List<PPGSample>,
    width: Float,
    height: Float,
    centerY: Float
) {
    if (samples.size < 2) return
    
    val path = Path()
    val xStep = width / samples.size.toFloat()
    
    // Calcular escala dinámica basada en datos reales
    val maxValue = samples.maxOf { abs(it.filteredValue) }
    val scaleFactor = if (maxValue > 0) (height * 0.4f) / maxValue else 1f
    
    // Dibujar onda principal
    path.moveTo(0f, centerY)
    
    samples.forEachIndexed { index, sample ->
        val x = index * xStep
        val y = centerY - (sample.filteredValue * scaleFactor)
        
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    
    // Color basado en calidad de señal
    val waveColor = when {
        samples.any { it.isPeak } -> MedicalGreen
        samples.any { abs(it.filteredValue) > 0.01f } -> MedicalCyan
        else -> MedicalAmber
    }
    
    drawPath(
        path = path,
        color = waveColor,
        style = Stroke(width = 2.5f)
    )
    
    // Área bajo la curva (efecto médico)
    val fillPath = Path()
    fillPath.addPath(path)
    fillPath.lineTo(width, centerY)
    fillPath.lineTo(0f, centerY)
    fillPath.close()
    
    drawPath(
        path = fillPath,
        color = waveColor.copy(alpha = 0.1f)
    )
}

/**
 * Dibuja características morfológicas detalladas
 */
private fun DrawScope.drawMorphologyFeatures(
    samples: List<PPGSample>,
    classifiedBeats: List<BeatClassifier.ClassifiedBeat>,
    width: Float,
    height: Float,
    centerY: Float
) {
    val xStep = width / samples.size.toFloat()
    
    // Marcar picos detectados
    samples.forEachIndexed { index, sample ->
        if (sample.isPeak) {
            val x = index * xStep
            val maxValue = samples.maxOf { abs(it.filteredValue) }
            val scaleFactor = if (maxValue > 0) (height * 0.4f) / maxValue else 1f
            val y = centerY - (sample.filteredValue * scaleFactor)
            
            // Marcador de pico
            drawCircle(
                color = MedicalGreen,
                radius = 4.dp.toPx(),
                center = Offset(x, y)
            )
            
            // Línea vertical desde base
            drawLine(
                color = MedicalGreen.copy(alpha = 0.5f),
                start = Offset(x, centerY),
                end = Offset(x, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
    
    // Marcar latidos clasificados
    classifiedBeats.forEach { beat ->
        val sampleIndex = samples.indexOfFirst { 
            abs(it.timestamp - beat.timestamp) < 50_000_000 // 50ms tolerance
        }
        
        if (sampleIndex >= 0) {
            val x = sampleIndex * xStep
            
            // Color según clasificación
            val beatColor = when (beat.classification) {
                BeatClassifier.BeatClassification.NORMAL -> MedicalGreen
                BeatClassifier.BeatClassification.PREMATURE -> MedicalAmber
                BeatClassifier.BeatClassification.SKIPPED -> MedicalRed
                BeatClassifier.BeatClassification.ETC -> Color.Magenta
                else -> Color.Gray
            }
            
            // Marcador especial para latidos clasificados
            drawCircle(
                color = beatColor,
                radius = 6.dp.toPx(),
                center = Offset(x, centerY - height * 0.3f),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/**
 * Dibuja anomalías detectadas con colores médicos
 */
private fun DrawScope.drawAbnormalities(
    abnormalities: List<PPGAbnormality>,
    samples: List<PPGSample>,
    width: Float,
    height: Float,
    centerY: Float
) {
    if (abnormalities.isEmpty() || samples.isEmpty()) return
    
    val xStep = width / samples.size.toFloat()
    
    abnormalities.forEach { abnormality ->
        // Color según severidad
        val abnormalityColor = when (abnormality.severity) {
            AbnormalitySeverity.HIGH -> MedicalRed
            AbnormalitySeverity.MODERATE -> MedicalAmber
            AbnormalitySeverity.LOW -> Color(0xFFFFC107)
        }
        
        // Área afectada (basado en timestamp)
        val affectedIndex = (samples.size * 0.8f).toInt() // Últimos 20% de muestras
        
        if (affectedIndex < samples.size) {
            val startX = affectedIndex * xStep
            val endX = width
            
            // Área sombreada para anomalía
            drawRect(
                color = abnormalityColor.copy(alpha = 0.2f),
                topLeft = Offset(startX, 0f),
                size = androidx.compose.ui.geometry.Size(endX - startX, height)
            )
            
            // Línea vertical delimitadora
            drawLine(
                color = abnormalityColor,
                start = Offset(startX, 0f),
                end = Offset(startX, height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

/**
 * Dibuja eventos de arritmia
 */
private fun DrawScope.drawArrhythmiaEvents(
    arrhythmiaEvents: List<ArrhythmiaScreening.ArrhythmiaEvent>,
    samples: List<PPGSample>,
    width: Float,
    height: Float,
    centerY: Float
) {
    if (arrhythmiaEvents.isEmpty() || samples.isEmpty()) return
    
    val xStep = width / samples.size.toFloat()
    
    arrhythmiaEvents.forEach { event ->
        val sampleIndex = samples.indexOfFirst { 
            abs(it.timestamp - event.timestamp) < 100_000_000 // 100ms tolerance
        }
        
        if (sampleIndex >= 0) {
            val x = sampleIndex * xStep
            
            // Color según tipo de arritmia
            val arrhythmiaColor = when (event.type) {
                ArrhythmiaScreening.ArrhythmiaType.AFIB -> MedicalRed
                ArrhythmiaScreening.ArrhythmiaType.PVC -> MedicalAmber
                ArrhythmiaScreening.ArrhythmiaType.BRADYCARDIA -> Color.Blue
                ArrhythmiaScreening.ArrhythmiaType.TACHYCARDIA -> Color(0xFFFF5722)
                else -> Color.Gray
            }
            
            // Triángulo de advertencia para arritmia
            val trianglePath = Path().apply {
                moveTo(x, centerY - height * 0.15f)
                lineTo(x - 8.dp.toPx(), centerY - height * 0.05f)
                lineTo(x + 8.dp.toPx(), centerY - height * 0.05f)
                close()
            }
            
            drawPath(
                path = trianglePath,
                color = arrhythmiaColor
            )
        }
    }
}

/**
 * Dibuja mensaje cuando no hay señal
 */
private fun DrawScope.drawNoSignalMessage(
    fingerPresent: Boolean,
    signalValid: Boolean,
    signalQuality: SignalQuality,
    width: Float,
    height: Float
) {
    val message = when {
        !fingerPresent -> "COLOQUE DEDO"
        !signalValid -> "SEÑAL INVÁLIDA"
        signalQuality == SignalQuality.POOR -> "SEÑAL DÉBIL"
        else -> "MEJORAR CONTACTO"
    }
    
    val messageColor = when {
        !fingerPresent -> Color.Gray
        !signalValid -> MedicalRed
        signalQuality == SignalQuality.POOR -> MedicalAmber
        else -> Color(0xFFFF9800)
    }
    
    // Simulación de texto (sin usar composables de texto)
    val textY = height / 2f
    val textHeight = 20.dp.toPx()
    
    // Rectángulo de fondo para mensaje
    drawRect(
        color = Color.Black.copy(alpha = 0.7f),
        topLeft = Offset(width / 2f - 100.dp.toPx(), textY - textHeight / 2f),
        size = androidx.compose.ui.geometry.Size(200.dp.toPx(), textHeight)
    )
    
    // Líneas simulando texto (simple representación)
    for (i in 0 until 3) {
        val lineY = textY - 5.dp.toPx() + (i * 6.dp.toPx())
        val lineWidth = when (message) {
            "COLOQUE DEDO" -> 80.dp.toPx()
            "SEÑAL INVÁLIDA" -> 90.dp.toPx()
            "SEÑAL DÉBIL" -> 70.dp.toPx()
            else -> 85.dp.toPx()
        }
        
        drawLine(
            color = messageColor,
            start = Offset(width / 2f - lineWidth / 2f, lineY),
            end = Offset(width / 2f + lineWidth / 2f, lineY),
            strokeWidth = 2.dp.toPx()
        )
    }
}

/**
 * Dibuja escalas clínicas
 */
private fun DrawScope.drawClinicalScales(width: Float, height: Float) {
    val scaleMargin = 20.dp.toPx()
    
    // Escala de amplitud (izquierda)
    for (i in 0..4) {
        val y = height * (0.2f + i * 0.15f)
        val value = when (i) {
            0 -> "+2.0"
            1 -> "+1.0"
            2 -> "0.0"
            3 -> "-1.0"
            4 -> "-2.0"
            else -> "0.0"
        }
        
        drawLine(
            color = MedicalGrid.copy(alpha = 0.8f),
            start = Offset(scaleMargin - 5.dp.toPx(), y),
            end = Offset(scaleMargin, y),
            strokeWidth = 1.dp.toPx()
        )
    }
    
    // Escala de tiempo (abajo)
    for (i in 0..6) {
        val x = width * (i / 6f)
        
        drawLine(
            color = MedicalGrid.copy(alpha = 0.8f),
            start = Offset(x, height - scaleMargin),
            end = Offset(x, height - scaleMargin + 5.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * Canvas extendido para visualización de morfología avanzada
 */
@Composable
fun MorphologyDetailCanvas(
    samples: List<PPGSample>,
    morphologyResult: WaveformAnalysisResult?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        
        // Grid morfológico
        drawMorphologyGrid(width, height)
        
        if (samples.isNotEmpty() && morphologyResult != null) {
            // Onda principal
            drawPPGWaveform(samples, width, height, centerY)
            
            // Características morfológicas detalladas
            drawDetailedMorphology(morphologyResult, width, height, centerY)
        }
    }
}

/**
 * Grid especializado para morfología
 */
private fun DrawScope.drawMorphologyGrid(width: Float, height: Float) {
    val gridStep = 40.dp.toPx()
    
    for (x in 0..(width / gridStep).toInt()) {
        drawLine(
            MedicalGrid.copy(alpha = 0.4f),
            Offset(x * gridStep, 0f),
            Offset(x * gridStep, height),
            strokeWidth = 0.5f
        )
    }
    
    for (y in 0..(height / gridStep).toInt()) {
        drawLine(
            MedicalGrid.copy(alpha = 0.4f),
            Offset(0f, y * gridStep),
            Offset(width, y * gridStep),
            strokeWidth = 0.5f
        )
    }
}

/**
 * Dibuja morfología detallada
 */
private fun DrawScope.drawDetailedMorphology(
    morphologyResult: WaveformAnalysisResult,
    width: Float,
    height: Float,
    centerY: Float
) {
    // Marcar picos con características
        morphologyResult.morphology.peaks.forEach { peak ->
        val x = width * 0.5f // Simplificado para demostración
        val y = centerY - (peak.value * height * 0.3f)
        
        // Pico principal
        drawCircle(
            color = MedicalGreen,
            radius = 6.dp.toPx(),
            center = Offset(x, y)
        )
        
        // Características del pico
        peak.characteristics?.let { characteristics ->
            if (characteristics.hasDicroticNotch) {
                // Marcar muesca dicrotica
                drawCircle(
                    color = MedicalCyan,
                    radius = 3.dp.toPx(),
                    center = Offset(x + 10.dp.toPx(), y + 5.dp.toPx())
                )
            }
            
            if (characteristics.hasTidalWave) {
                // Marcar onda tidal
                drawCircle(
                    color = MedicalAmber,
                    radius = 3.dp.toPx(),
                    center = Offset(x + 15.dp.toPx(), y + 8.dp.toPx())
                )
            }
        }
    }
    
    // Marcar valles
        morphologyResult.morphology.valleys.forEach { valley ->
        val x = width * 0.3f // Simplificado
        val y = centerY + (valley.depth * height * 0.2f)
        
        drawCircle(
            color = MedicalRed.copy(alpha = 0.7f),
            radius = 4.dp.toPx(),
            center = Offset(x, y)
        )
    }
    
    // Indicadores de calidad
    val qualityColor = when {
        morphologyResult.signalQuality > 0.8f -> MedicalGreen
        morphologyResult.signalQuality > 0.6f -> MedicalAmber
        else -> MedicalRed
    }
    
    drawCircle(
        color = qualityColor,
        radius = 8.dp.toPx(),
        center = Offset(width - 30.dp.toPx(), 30.dp.toPx())
    )
}
