package com.julylove.medical.signal

import kotlin.math.max
import kotlin.math.min

/**
 * PeakDetectorElgendi: Implementación completa del algoritmo de Elgendi et al. (2013)
 * "Optimal Systolic Peak Detection in Photoplethysmogram"
 * 
 * Características principales:
 * - Ventanas móviles corta y larga para detección de picos sistólicos
 * - Threshold adaptativo basado en la señal local
 * - Período refractario fisiológico (170 BPM max)
 * - Validación morfológica del pico
 * - Rechazo de picos de baja prominencia
 */
class PeakDetectorElgendi(initialSampleRate: Float) {
    
    private var sampleRate: Float = initialSampleRate.coerceIn(12f, 120f)
    
    // Ventanas móviles según paper Elgendi
    private var windowShort: Int = computeWindowShort(sampleRate)  // ~0.11s
    private var windowLong: Int = computeWindowLong(sampleRate)    // ~0.667s
    
    // Buffers para procesamiento
    private val signalBuffer = mutableListOf<Float>()
    private val squaredBuffer = mutableListOf<Float>()
    private val movingAverageShort = mutableListOf<Float>()
    private val movingAverageLong = mutableListOf<Float>()
    
    // Control de picos
    private var lastPeakTimestampNs: Long = 0
    private val minRefractoryNs: Long = 350_000_000L // ~170 BPM máximo
    
    // Parámetros configurables
    private var thresholdFactor: Float = 0.4f  // Factor para threshold adaptativo
    private var minProminence: Float = 0.1f    // Prominencia mínima del pico
    
    data class ElgendiPeak(
        val timestampNs: Long,
        val rrMs: Double?,
        val confidence: Double,
        val amplitude: Double,
        val prominence: Double,
        val morphologyScore: Double,
        val reason: String
    )
    
    init {
        updateSampleRate(initialSampleRate)
    }
    
    fun updateSampleRate(sr: Float) {
        val newRate = sr.coerceIn(12f, 120f)
        if (kotlin.math.abs(newRate - sampleRate) < 1.0f) return
        
        sampleRate = newRate
        windowShort = computeWindowShort(sampleRate)
        windowLong = computeWindowLong(sampleRate)
        
        // Resetear buffers al cambiar tasa de muestreo
        reset()
    }
    
    private fun computeWindowShort(sr: Float): Int =
        (sr * 0.11f).toInt().coerceIn(5, 30)
    
    private fun computeWindowLong(sr: Float): Int =
        (sr * 0.667f).toInt().coerceIn(20, 120)
    
    /**
     * Procesa una muestra y retorna pico detectado si cumple criterios
     */
    fun process(value: Float, timestampNs: Long): ElgendiPeak? {
        // Agregar a buffers
        signalBuffer.add(value)
        squaredBuffer.add(value * value)
        
        // Mantener tamaño de buffers
        val maxBuffer = windowLong * 2
        while (signalBuffer.size > maxBuffer) {
            signalBuffer.removeAt(0)
            squaredBuffer.removeAt(0)
        }
        
        // Necesitamos suficientes muestras para procesar
        if (signalBuffer.size < windowLong) return null
        
        // Calcular medias móviles
        updateMovingAverages()
        
        if (movingAverageShort.size < windowShort || movingAverageLong.size < windowLong) {
            return null
        }
        
        // Detectar pico usando algoritmo Elgendi
        return detectElgendiPeak(timestampNs)
    }
    
    private fun updateMovingAverages() {
        // Media móvil corta
        movingAverageShort.clear()
        for (i in windowShort until squaredBuffer.size) {
            val sum = squaredBuffer.subList(i - windowShort, i).sum()
            movingAverageShort.add(sum / windowShort)
        }
        
        // Media móvil larga
        movingAverageLong.clear()
        for (i in windowLong until squaredBuffer.size) {
            val sum = squaredBuffer.subList(i - windowLong, i).sum()
            movingAverageLong.add(sum / windowLong)
        }
    }
    
    private fun detectElgendiPeak(timestampNs: Long): ElgendiPeak? {
        if (movingAverageShort.size < 2 || movingAverageLong.size < 2) return null
        
        // Índice actual (última muestra)
        val currentIndex = signalBuffer.size - 1
        
        // Verificar que estamos en un pico local (derivada cambia de signo)
        if (currentIndex < 2) return null
        
        val currentValue = signalBuffer[currentIndex]
        val prevValue = signalBuffer[currentIndex - 1]
        val prev2Value = signalBuffer[currentIndex - 2]
        
        // Detectar cruce por zero de la primera derivada (pico local)
        val slopePrev = prevValue - prev2Value
        val slopeCurrent = currentValue - prevValue
        
        if (slopePrev <= 0 || slopeCurrent >= 0) return null // No es un pico
        
        // Calcular threshold adaptativo
        val threshold = calculateAdaptiveThreshold(currentIndex)
        
        // Verificar que el pico esté por encima del threshold
        if (currentValue <= threshold) return null
        
        // Verificar período refractario
        val timeSinceLast = timestampNs - lastPeakTimestampNs
        if (timeSinceLast < minRefractoryNs) return null
        
        // Calcular métricas del pico
        val prominence = calculateProminence(currentIndex)
        val morphologyScore = calculateMorphologyScore(currentIndex)
        
        // Validar criterios morfológicos
        if (prominence < minProminence) return null
        if (morphologyScore < 0.3) return null
        
        // Calcular RR interval
        val rrMs = if (lastPeakTimestampNs > 0) {
            timeSinceLast / 1_000_000.0
        } else null
        
        // Calcular confianza basada en múltiples factores
        val confidence = calculateConfidence(currentValue, threshold, prominence, morphologyScore)
        
        // Actualizar timestamp del último pico
        lastPeakTimestampNs = timestampNs
        
        return ElgendiPeak(
            timestampNs = timestampNs,
            rrMs = rrMs,
            confidence = confidence,
            amplitude = currentValue.toDouble(),
            prominence = prominence.toDouble(),
            morphologyScore = morphologyScore.toDouble(),
            reason = generateReasonString(confidence.toFloat(), morphologyScore.toFloat())
        )
    }
    
    private fun calculateAdaptiveThreshold(index: Int): Float {
        if (movingAverageLong.isEmpty()) return 0f
        
        // Usar media móvil larga como baseline
        val baseline = movingAverageLong.last()
        
        // Threshold adaptativo basado en variabilidad local
        val localVariability = calculateLocalVariability(index)
        
        return baseline + (thresholdFactor * localVariability)
    }
    
    private fun calculateLocalVariability(index: Int): Float {
        val windowSize = min(windowShort, index)
        if (windowSize < 3) return 0f
        
        val localWindow = signalBuffer.subList(index - windowSize, index)
        val mean = localWindow.average().toFloat()
        
        var variance = 0f
        for (value in localWindow) {
            val diff = value - mean
            variance += diff * diff
        }
        
        return kotlin.math.sqrt(variance / windowSize)
    }
    
    private fun calculateProminence(peakIndex: Int): Float {
        if (signalBuffer.size < 3) return 0f
        
        val peakValue = signalBuffer[peakIndex]
        
        // Buscar valles a izquierda y derecha del pico
        var leftValley = peakValue
        var rightValley = peakValue
        
        // Búsqueda izquierda
        for (i in peakIndex - 1 downTo max(0, peakIndex - windowShort)) {
            if (signalBuffer[i] < leftValley) {
                leftValley = signalBuffer[i]
            }
            if (signalBuffer[i] > peakValue * 0.8f) break // Romper si vuelve a subir
        }
        
        // Búsqueda derecha
        for (i in peakIndex + 1 until min(signalBuffer.size, peakIndex + windowShort)) {
            if (signalBuffer[i] < rightValley) {
                rightValley = signalBuffer[i]
            }
            if (signalBuffer[i] > peakValue * 0.8f) break // Romper si vuelve a subir
        }
        
        val referenceValley = max(leftValley, rightValley)
        return if (peakValue > referenceValley) {
            (peakValue - referenceValley) / peakValue
        } else 0f
    }
    
    private fun calculateMorphologyScore(peakIndex: Int): Float {
        if (signalBuffer.size < 5) return 0f
        
        // Evaluar morfología típica de pico PPG:
        // 1. Ascenso rápido
        // 2. Pico agudo
        // 3. Descenso más gradual
        
        val startIdx = max(0, peakIndex - 4)
        val endIdx = min(signalBuffer.size, peakIndex + 5)
        
        if (endIdx - startIdx < 5) return 0f
        
        val segment = signalBuffer.subList(startIdx, endIdx)
        val peakIdxInSegment = peakIndex - startIdx
        
        if (peakIdxInSegment < 2 || peakIdxInSegment >= segment.size - 2) return 0f
        
        // Calcular pendientes
        val slopeUp = segment[peakIdxInSegment] - segment[peakIdxInSegment - 2]
        val slopeDown = segment[peakIdxInSegment + 2] - segment[peakIdxInSegment]
        
        // Score basado en relación ascenso/descenso
        val slopeRatio = if (slopeDown != 0f) kotlin.math.abs(slopeUp / slopeDown) else 1f
        val idealRatio = 1.5f // Ascenso 1.5x más rápido que descenso
        
        val ratioScore = 1f - kotlin.math.abs(slopeRatio - idealRatio) / idealRatio
        return ratioScore.coerceIn(0f, 1f)
    }
    
    private fun calculateConfidence(
        amplitude: Float,
        threshold: Float,
        prominence: Float,
        morphologyScore: Float
    ): Double {
        // Factores de confianza
        val amplitudeFactor = (amplitude - threshold) / (amplitude + 1e-6f)
        val prominenceFactor = prominence
        val morphologyFactor = morphologyScore
        
        // Combinación ponderada
        return (amplitudeFactor * 0.4 + prominenceFactor * 0.3 + morphologyFactor * 0.3)
            .coerceIn(0.0, 1.0)
    }
    
    private fun generateReasonString(confidence: Float, morphologyScore: Float): String {
        return when {
            confidence > 0.8f && morphologyScore > 0.7f -> "Pico sistólico excelente"
            confidence > 0.6f && morphologyScore > 0.5f -> "Pico sistólico bueno"
            confidence > 0.4f -> "Pico sistólico aceptable"
            morphologyScore < 0.3f -> "Morfología atípica"
            else -> "Pico sistólico marginal"
        }
    }
    
    /**
     * Configura parámetros del detector
     */
    fun setThresholdFactor(factor: Float) {
        thresholdFactor = factor.coerceIn(0.1f, 1.0f)
    }
    
    fun setMinProminence(prominence: Float) {
        minProminence = prominence.coerceIn(0.01f, 0.5f)
    }
    
    /**
     * Reinicia estado del detector
     */
    fun reset() {
        signalBuffer.clear()
        squaredBuffer.clear()
        movingAverageShort.clear()
        movingAverageLong.clear()
        lastPeakTimestampNs = 0
    }
    
    /**
     * Obtiene estadísticas del detector
     */
    fun getStats(): DetectorStats {
        return DetectorStats(
            sampleRate = sampleRate,
            windowShort = windowShort,
            windowLong = windowLong,
            thresholdFactor = thresholdFactor,
            minProminence = minProminence
        )
    }
    
    data class DetectorStats(
        val sampleRate: Float,
        val windowShort: Int,
        val windowLong: Int,
        val thresholdFactor: Float,
        val minProminence: Float
    )
}
