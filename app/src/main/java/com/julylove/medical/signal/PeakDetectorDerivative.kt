package com.julylove.medical.signal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PeakDetectorDerivative: Detector de picos basado en análisis de derivadas
 * Método independiente complementario al detector Elgendi
 * 
 * Principio:
 * - Detecta picos por cambio de signo en la primera derivada
 * - Valida con segunda derivada (concavidad negativa en pico)
 * - Aplica umbrales adaptativos basados en estadísticas locales
 * - Filtra por ancho de pico y prominencia
 */
class PeakDetectorDerivative(initialSampleRate: Float) {
    
    private var sampleRate: Float = initialSampleRate.coerceIn(12f, 120f)
    
    // Ventanas para análisis
    private var analysisWindow: Int = computeAnalysisWindow(sampleRate)
    private var minPeakWidth: Int = computeMinPeakWidth(sampleRate)
    private var maxPeakWidth: Int = computeMaxPeakWidth(sampleRate)
    
    // Buffers para señal y derivadas
    private val signalBuffer = mutableListOf<Float>()
    private val firstDerivative = mutableListOf<Float>()
    private val secondDerivative = mutableListOf<Float>()
    
    // Control de picos
    private var lastPeakTimestampNs: Long = 0
    private val minRefractoryNs: Long = 300_000_000L // ~200 BPM máximo
    
    // Parámetros configurables
    private var derivativeThreshold: Float = 0.15f
    private var prominenceThreshold: Float = 0.08f
    private var concavityThreshold: Float = -0.1f
    
    // Estadísticas para threshold adaptativo
    private var localMean: Float = 0f
    private var localStd: Float = 0f
    
    data class DerivativePeak(
        val timestampNs: Long,
        val rrMs: Double?,
        val confidence: Double,
        val amplitude: Double,
        val prominence: Double,
        val peakWidth: Int,
        val slopeRatio: Double,
        val concavity: Double,
        val reason: String
    )
    
    init {
        updateSampleRate(initialSampleRate)
    }
    
    fun updateSampleRate(sr: Float) {
        val newRate = sr.coerceIn(12f, 120f)
        if (abs(newRate - sampleRate) < 1.0f) return
        
        sampleRate = newRate
        analysisWindow = computeAnalysisWindow(sampleRate)
        minPeakWidth = computeMinPeakWidth(sampleRate)
        maxPeakWidth = computeMaxPeakWidth(sampleRate)
        
        reset()
    }
    
    private fun computeAnalysisWindow(sr: Float): Int =
        (sr * 2.0f).toInt().coerceIn(30, 180)
    
    private fun computeMinPeakWidth(sr: Float): Int =
        (sr * 0.05f).toInt().coerceIn(2, 8)
    
    private fun computeMaxPeakWidth(sr: Float): Int =
        (sr * 0.3f).toInt().coerceIn(8, 30)
    
    /**
     * Procesa una muestra y retorna pico detectado si cumple criterios
     */
    fun process(value: Float, timestampNs: Long): DerivativePeak? {
        // Agregar a buffer de señal
        signalBuffer.add(value)
        
        // Mantener tamaño del buffer
        while (signalBuffer.size > analysisWindow) {
            signalBuffer.removeAt(0)
        }
        
        // Necesitamos suficientes muestras para calcular derivadas
        if (signalBuffer.size < 5) return null
        
        // Calcular derivadas
        updateDerivatives()
        
        // Actualizar estadísticas locales
        updateLocalStatistics()
        
        // Detectar pico usando análisis de derivadas
        return detectDerivativePeak(timestampNs)
    }
    
    private fun updateDerivatives() {
        firstDerivative.clear()
        secondDerivative.clear()
        
        // Calcular primera derivada (diferencias finitas)
        for (i in 1 until signalBuffer.size) {
            firstDerivative.add(signalBuffer[i] - signalBuffer[i - 1])
        }
        
        // Calcular segunda derivada
        for (i in 1 until firstDerivative.size) {
            secondDerivative.add(firstDerivative[i] - firstDerivative[i - 1])
        }
    }
    
    private fun updateLocalStatistics() {
        if (signalBuffer.size < 3) return
        
        val recentWindow = signalBuffer.takeLast(min(20, signalBuffer.size))
        localMean = recentWindow.average().toFloat()
        
        var variance = 0f
        for (value in recentWindow) {
            val diff = value - localMean
            variance += diff * diff
        }
        localStd = kotlin.math.sqrt(variance / recentWindow.size)
    }
    
    private fun detectDerivativePeak(timestampNs: Long): DerivativePeak? {
        if (firstDerivative.size < 3 || secondDerivative.size < 2) return null
        
        // Índice actual (última muestra)
        val currentIndex = signalBuffer.size - 1
        val derivIndex = firstDerivative.size - 1
        
        // Verificar cambio de signo en primera derivada (cruce por zero)
        val currentDeriv = firstDerivative[derivIndex]
        val prevDeriv = firstDerivative[derivIndex - 1]
        
        // Pico detectado: derivada positiva a negativa
        if (prevDeriv <= 0 || currentDeriv >= 0) return null
        
        // Verificar concavidad negativa (segunda derivada < 0)
        val currentSecondDeriv = secondDerivative[derivIndex - 1]
        if (currentSecondDeriv > concavityThreshold) return null
        
        // Verificar umbral de derivada adaptativo
        val adaptiveThreshold = derivativeThreshold * (localStd + 1e-6f)
        if (abs(currentDeriv) < adaptiveThreshold) return null
        
        // Verificar período refractario
        val timeSinceLast = timestampNs - lastPeakTimestampNs
        if (timeSinceLast < minRefractoryNs) return null
        
        // Analizar características del pico
        val peakCharacteristics = analyzePeakCharacteristics(currentIndex)
        
        // Validar ancho del pico
        if (peakCharacteristics.width < minPeakWidth || peakCharacteristics.width > maxPeakWidth) {
            return null
        }
        
        // Validar prominencia
        if (peakCharacteristics.prominence < prominenceThreshold) return null
        
        // Calcular métricas adicionales
        val slopeRatio = calculateSlopeRatio(currentIndex)
        val concavity = calculateConcavityScore(currentIndex)
        
        // Calcular RR interval
        val rrMs = if (lastPeakTimestampNs > 0) {
            timeSinceLast / 1_000_000.0
        } else null
        
        // Calcular confianza
        val confidence = calculateConfidence(
            peakCharacteristics,
            slopeRatio,
            concavity,
            abs(currentDeriv)
        )
        
        // Actualizar timestamp
        lastPeakTimestampNs = timestampNs
        
        return DerivativePeak(
            timestampNs = timestampNs,
            rrMs = rrMs,
            confidence = confidence,
            amplitude = signalBuffer[currentIndex].toDouble(),
            prominence = peakCharacteristics.prominence.toDouble(),
            peakWidth = peakCharacteristics.width,
            slopeRatio = slopeRatio,
            concavity = concavity,
            reason = generateReasonString(confidence, peakCharacteristics, slopeRatio)
        )
    }
    
    private fun analyzePeakCharacteristics(peakIndex: Int): PeakCharacteristics {
        if (signalBuffer.size < 5) {
            return PeakCharacteristics(0, 0f)
        }
        
        val peakValue = signalBuffer[peakIndex]
        
        // Encontrar ancho del pico a mitad de altura
        val halfHeight = peakValue * 0.5f
        var leftBound = peakIndex
        var rightBound = peakIndex
        
        // Búsqueda hacia izquierda
        for (i in peakIndex - 1 downTo max(0, peakIndex - maxPeakWidth)) {
            if (signalBuffer[i] <= halfHeight) {
                leftBound = i
                break
            }
        }
        
        // Búsqueda hacia derecha
        for (i in peakIndex + 1 until min(signalBuffer.size, peakIndex + maxPeakWidth)) {
            if (signalBuffer[i] <= halfHeight) {
                rightBound = i
                break
            }
        }
        
        val width = rightBound - leftBound
        
        // Calcular prominencia
        val baseline = max(signalBuffer[leftBound], signalBuffer[rightBound])
        val prominence = if (peakValue > baseline) {
            (peakValue - baseline) / peakValue
        } else 0f
        
        return PeakCharacteristics(width, prominence)
    }
    
    private fun calculateSlopeRatio(peakIndex: Int): Double {
        if (peakIndex < 4 || peakIndex >= signalBuffer.size - 2) return 0.0
        
        // Pendiente ascendente (2 muestras antes del pico)
        val ascentSlope = (signalBuffer[peakIndex] - signalBuffer[peakIndex - 2]) / 2f
        
        // Pendiente descendente (2 muestras después del pico)
        val descentSlope = (signalBuffer[peakIndex + 2] - signalBuffer[peakIndex]) / 2f
        
        return if (descentSlope != 0f) {
            abs(ascentSlope / descentSlope).toDouble()
        } else 2.0 // Valor alto si descenso es muy abrupto
    }
    
    private fun calculateConcavityScore(peakIndex: Int): Double {
        if (secondDerivative.isEmpty()) return 0.0
        
        val derivIndex = min(peakIndex - 1, secondDerivative.size - 1)
        if (derivIndex < 0) return 0.0
        
        val concavity = secondDerivative[derivIndex]
        
        // Normalizar concavidad (valores más negativos = mejor)
        return (abs(concavity) / (localStd + 1e-6f)).coerceIn(0.0, 2.0)
    }
    
    private fun calculateConfidence(
        characteristics: PeakCharacteristics,
        slopeRatio: Double,
        concavity: Double,
        derivativeMagnitude: Float
    ): Double {
        // Factores de confianza
        val widthScore = when {
            characteristics.width in minPeakWidth..maxPeakWidth -> 1.0
            else -> 0.5
        }
        
        val prominenceScore = (characteristics.prominence / prominenceThreshold).coerceIn(0.0, 1.0)
        val slopeScore = (1.0 - abs(slopeRatio - 1.5) / 1.5).coerceIn(0.0, 1.0)
        val concavityScore = (concavity / 2.0).coerceIn(0.0, 1.0)
        val derivativeScore = (derivativeMagnitude / (localStd + 1e-6f)).coerceIn(0.0, 1.0)
        
        // Combinación ponderada
        return (widthScore * 0.2 + prominenceScore * 0.25 + slopeScore * 0.2 + 
                concavityScore * 0.2 + derivativeScore * 0.15).coerceIn(0.0, 1.0)
    }
    
    private fun generateReasonString(
        confidence: Double,
        characteristics: PeakCharacteristics,
        slopeRatio: Double
    ): String {
        return when {
            confidence > 0.8 -> "Pico derivativo excelente"
            confidence > 0.6 -> "Pico derivativo bueno"
            confidence > 0.4 -> "Pico derivativo aceptable"
            characteristics.width < minPeakWidth -> "Pico muy angosto"
            characteristics.width > maxPeakWidth -> "Pico muy ancho"
            slopeRatio < 0.8 || slopeRatio > 2.2 -> "Relación de pendientes atípica"
            else -> "Pico derivativo marginal"
        }
    }
    
    /**
     * Configura parámetros del detector
     */
    fun setDerivativeThreshold(threshold: Float) {
        derivativeThreshold = threshold.coerceIn(0.05f, 0.5f)
    }
    
    fun setProminenceThreshold(threshold: Float) {
        prominenceThreshold = threshold.coerceIn(0.02f, 0.2f)
    }
    
    fun setConcavityThreshold(threshold: Float) {
        concavityThreshold = threshold.coerceIn(-0.5f, 0f)
    }
    
    /**
     * Reinicia estado del detector
     */
    fun reset() {
        signalBuffer.clear()
        firstDerivative.clear()
        secondDerivative.clear()
        lastPeakTimestampNs = 0
        localMean = 0f
        localStd = 0f
    }
    
    /**
     * Obtiene estadísticas del detector
     */
    fun getStats(): DetectorStats {
        return DetectorStats(
            sampleRate = sampleRate,
            analysisWindow = analysisWindow,
            minPeakWidth = minPeakWidth,
            maxPeakWidth = maxPeakWidth,
            derivativeThreshold = derivativeThreshold,
            prominenceThreshold = prominenceThreshold,
            concavityThreshold = concavityThreshold
        )
    }
    
    data class DetectorStats(
        val sampleRate: Float,
        val analysisWindow: Int,
        val minPeakWidth: Int,
        val maxPeakWidth: Int,
        val derivativeThreshold: Float,
        val prominenceThreshold: Float,
        val concavityThreshold: Float
    )
    
    private data class PeakCharacteristics(
        val width: Int,
        val prominence: Float
    )
}
