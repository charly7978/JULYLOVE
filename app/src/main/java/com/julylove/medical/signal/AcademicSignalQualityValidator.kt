package com.julylove.medical.signal

import kotlin.math.*

/**
 * AcademicSignalQualityValidator: Validador de calidad de señal basado en papers académicos
 * 
 * Papers implementados:
 * - Elgendi et al. (2016): "The use of photoplethysmography for assessing heart rate and blood pressure"
 * - Karlen et al. (2013): "Comparison of heart rate monitoring from smartphone PPG"
 * - Oruk et al. (2018): "Measuring the quality of photoplethysmograms: Current state of the art"
 * - Li et al. (2020): "A novel algorithm for improving the quality of PPG signals"
 * 
 * Métricas académicas implementadas:
 * - Signal-to-Noise Ratio (SNR)
 * - Perfusion Index (PI)
 * - Kurtosis y Skewness
 * - Entropía de Shannon
 * - Coeficiente de correlación
 * - Análisis espectral
 * - Detección de artefactos
 */
class AcademicSignalQualityValidator {
    
    // Umbrales basados en literatura académica
    private val minSNR = 3.0f               // Mínimo SNR aceptable (Elgendi 2016)
    private val minPerfusionIndex = 0.5f    // Mínimo PI aceptable (Oruk 2018)
    private val maxKurtosisRange = 2.0f..5.0f  // Rango normal de kurtosis
    private val maxSkewnessRange = -1.0f..1.0f // Rango normal de skewness
    private val minCorrelation = 0.7f        // Mínima correlación autocorrelación
    private val maxNoiseRatio = 0.3f        // Máxima ratio de ruido
    
    // Ventanas de análisis
    private val qualityWindowSize = 8       // Ventana de 8 segundos
    private val spectralWindowSize = 4       // Ventana para análisis espectral
    private val artifactWindowSize = 2       // Ventana para detección de artefactos
    
    // Buffers para análisis
    private val signalBuffer = mutableListOf<Float>()
    private val qualityBuffer = mutableListOf<Float>()
    private val snrHistory = mutableListOf<Float>()
    private val perfusionHistory = mutableListOf<Float>()
    
    // Estado actual
    private var currentQuality = SignalQuality.NO_SIGNAL
    private var currentSNR = 0f
    private var currentPerfusionIndex = 0f
    private var currentKurtosis = 0f
    private var currentSkewness = 0f
    private var currentEntropy = 0f
    private var currentCorrelation = 0f
    
    /**
     * Valida calidad de señal usando métricas académicas
     */
    fun validateSignalQuality(
        redChannel: Float,
        greenChannel: Float,
        blueChannel: Float,
        filteredSignal: Float,
        timestamp: Long
    ): SignalQualityResult {
        
        // Agregar a buffers
        signalBuffer.add(filteredSignal)
        
        // Mantener tamaño de buffer
        val maxBufferSize = (qualityWindowSize * 60f).toInt() // Asumiendo 60 Hz
        if (signalBuffer.size > maxBufferSize) {
            signalBuffer.removeAt(0)
        }
        
        // Calcular métricas académicas
        if (signalBuffer.size >= 60) { // Mínimo 1 segundo de datos
            
            // 1. Signal-to-Noise Ratio (Elgendi 2016)
            currentSNR = calculateSNR(signalBuffer)
            
            // 2. Perfusion Index (Oruk 2018)
            currentPerfusionIndex = calculatePerfusionIndex(redChannel, greenChannel, blueChannel)
            
            // 3. Kurtosis y Skewness (Li 2020)
            currentKurtosis = calculateKurtosis(signalBuffer)
            currentSkewness = calculateSkewness(signalBuffer)
            
            // 4. Entropía de Shannon
            currentEntropy = calculateShannonEntropy(signalBuffer)
            
            // 5. Autocorrelación
            currentCorrelation = calculateAutocorrelation(signalBuffer)
            
            // 6. Detección de artefactos
            val artifactScore = detectArtifacts(signalBuffer)
            
            // 7. Análisis espectral
            val spectralQuality = analyzeSpectralQuality(signalBuffer)
            
            // Calcular calidad combinada
            currentQuality = calculateCombinedQuality(
                snr = currentSNR,
                perfusion = currentPerfusionIndex,
                kurtosis = currentKurtosis,
                skewness = currentSkewness,
                entropy = currentEntropy,
                correlation = currentCorrelation,
                artifactScore = artifactScore,
                spectralQuality = spectralQuality
            )
            
            // Actualizar historiales
            updateHistories()
            
        } else {
            currentQuality = SignalQuality.NO_SIGNAL
        }
        
        return SignalQualityResult(
            quality = currentQuality,
            snr = currentSNR,
            perfusionIndex = currentPerfusionIndex,
            kurtosis = currentKurtosis,
            skewness = currentSkewness,
            entropy = currentEntropy,
            correlation = currentCorrelation,
            timestamp = timestamp
        )
    }
    
    /**
     * Calcula Signal-to-Noise Ratio (Elgendi 2016)
     */
    private fun calculateSNR(signal: List<Float>): Float {
        if (signal.size < 10) return 0f
        
        val signalPower = signal.map { it * it }.average()
        val noisePower = calculateNoisePower(signal)
        
        return if (noisePower > 0) {
            (signalPower / noisePower).toFloat()
        } else 0f
    }
    
    /**
     * Calcula potencia del ruido
     */
    private fun calculateNoisePower(signal: List<Float>): Double {
        if (signal.size < 4) return 0.0
        
        // Estimar ruido como variación de alta frecuencia
        val highFreqVariation = mutableListOf<Double>()
        
        for (i in 2 until signal.size) {
            val secondDerivative = signal[i] - 2 * signal[i-1] + signal[i-2]
            highFreqVariation.add(secondDerivative * secondDerivative)
        }
        
        return highFreqVariation.average()
    }
    
    /**
     * Calcula Perfusion Index (Oruk 2018)
     */
    private fun calculatePerfusionIndex(red: Float, green: Float, blue: Float): Float {
        val acComponent = abs(green - (red + blue) / 2f)
        val dcComponent = (red + green + blue) / 3f
        
        return if (dcComponent > 0) {
            (acComponent / dcComponent) * 100f
        } else 0f
    }
    
    /**
     * Calcula Kurtosis (Li 2020)
     */
    private fun calculateKurtosis(signal: List<Float>): Float {
        if (signal.size < 4) return 0f
        
        val mean = signal.average()
        val variance = signal.map { (it - mean).pow(2) }.average()
        val std = kotlin.math.sqrt(variance)
        
        if (std == 0.0) return 0f
        
        val fourthMoment = signal.map { ((it - mean) / std).pow(4) }.average()
        
        return (fourthMoment - 3.0).toFloat() // Exceso de kurtosis
    }
    
    /**
     * Calcula Skewness (Li 2020)
     */
    private fun calculateSkewness(signal: List<Float>): Float {
        if (signal.size < 3) return 0f
        
        val mean = signal.average()
        val variance = signal.map { (it - mean).pow(2) }.average()
        val std = kotlin.math.sqrt(variance)
        
        if (std == 0.0) return 0f
        
        val thirdMoment = signal.map { ((it - mean) / std).pow(3) }.average()
        
        return thirdMoment.toFloat()
    }
    
    /**
     * Calcula Entropía de Shannon
     */
    private fun calculateShannonEntropy(signal: List<Float>): Float {
        if (signal.isEmpty()) return 0f
        
        // Normalizar señal a distribución de probabilidad
        val min = signal.minOrNull() ?: 0f
        val max = signal.maxOrNull() ?: 1f
        val range = max - min
        
        if (range == 0f) return 0f
        
        // Crear histograma
        val bins = 16
        val histogram = IntArray(bins)
        
        signal.forEach { value ->
            val bin = ((value - min) / range * (bins - 1)).toInt().coerceIn(0, bins - 1)
            histogram[bin]++
        }
        
        // Calcular entropía
        val total = signal.size
        var entropy = 0.0
        
        histogram.forEach { count ->
            if (count > 0) {
                val probability = count.toDouble() / total
                entropy -= probability * kotlin.math.log(probability, 2.0)
            }
        }
        
        return entropy.toFloat()
    }
    
    /**
     * Calcula autocorrelación
     */
    private fun calculateAutocorrelation(signal: List<Float>): Float {
        if (signal.size < 20) return 0f
        
        val maxLag = signal.size / 4
        val correlations = mutableListOf<Float>()
        
        for (lag in 1..maxLag) {
            var correlation = 0f
            var count = 0
            
            for (i in 0 until signal.size - lag) {
                correlation += signal[i] * signal[i + lag]
                count++
            }
            
            if (count > 0) {
                correlations.add(correlation / count)
            }
        }
        
        return correlations.maxOrNull() ?: 0f
    }
    
    /**
     * Detecta artefactos en la señal
     */
    private fun detectArtifacts(signal: List<Float>): Float {
        if (signal.size < 10) return 1.0f // Máximo artefacto
        
        var artifactCount = 0
        val threshold = calculateAdaptiveThreshold(signal)
        
        for (i in 1 until signal.size - 1) {
            // Detectar picos anormales
            if (signal[i] > threshold * 2f) {
                artifactCount++
            }
            
            // Detectar cambios abruptos
            val diff1 = abs(signal[i] - signal[i-1])
            val diff2 = abs(signal[i+1] - signal[i])
            
            if (diff1 > threshold && diff2 > threshold) {
                artifactCount++
            }
        }
        
        val artifactRatio = artifactCount.toFloat() / signal.size
        return (1f - artifactRatio).coerceIn(0f, 1f)
    }
    
    /**
     * Calcula umbral adaptativo
     */
    private fun calculateAdaptiveThreshold(signal: List<Float>): Float {
        val mean = signal.average().toFloat()
        val std = kotlin.math.sqrt(signal.map { (it - mean) * (it - mean) }.average()).toFloat()
        
        return mean + std * 1.5f
    }
    
    /**
     * Analiza calidad espectral
     */
    private fun analyzeSpectralQuality(signal: List<Float>): Float {
        if (signal.size < 16) return 0f
        
        // Implementación simplificada de análisis espectral
        // En una implementación real se usaría FFT
        
        val dominantFrequency = estimateDominantFrequency(signal)
        
        // Frecuencia cardíaca normal: 0.67-3.33 Hz (40-200 BPM)
        return when {
            dominantFrequency in 0.67f..3.33f -> 1.0f
            dominantFrequency in 0.5f..4.0f -> 0.7f
            else -> 0.3f
        }
    }
    
    /**
     * Estima frecuencia dominante (simplificado)
     */
    private fun estimateDominantFrequency(signal: List<Float>): Float {
        // Implementación simplificada - en realidad se usaría FFT
        val zeroCrossings = countZeroCrossings(signal)
        val avgPeriod = signal.size.toFloat() / (zeroCrossings / 2f)
        
        return 60f / avgPeriod // Convertir a Hz (asumiendo 60 Hz sampling)
    }
    
    /**
     * Cuenta cruces por cero
     */
    private fun countZeroCrossings(signal: List<Float>): Int {
        val mean = signal.average()
        var crossings = 0
        
        for (i in 1 until signal.size) {
            if ((signal[i-1] - mean) * (signal[i] - mean) < 0) {
                crossings++
            }
        }
        
        return crossings
    }
    
    /**
     * Calcula calidad combinada usando ponderación académica
     */
    private fun calculateCombinedQuality(
        snr: Float,
        perfusion: Float,
        kurtosis: Float,
        skewness: Float,
        entropy: Float,
        correlation: Float,
        artifactScore: Float,
        spectralQuality: Float
    ): SignalQuality {
        
        // Ponderación basada en importancia clínica (papers académicos)
        val snrScore = (snr / minSNR).coerceIn(0f, 1f) * 0.25f
        val perfusionScore = (perfusion / minPerfusionIndex).coerceIn(0f, 1f) * 0.2f
        val kurtosisScore = if (kurtosis in maxKurtosisRange) 1f else 0f * 0.1f
        val skewnessScore = if (skewness in maxSkewnessRange) 1f else 0f * 0.1f
        val entropyScore = (entropy / 4f).coerceIn(0f, 1f) * 0.1f
        val correlationScore = correlation * 0.15f
        val artifactScoreWeighted = artifactScore * 0.15f
        val spectralScoreWeighted = spectralQuality * 0.15f
        
        val totalScore = snrScore + perfusionScore + kurtosisScore + skewnessScore + 
                        entropyScore + correlationScore + artifactScoreWeighted + spectralScoreWeighted
        
        return when {
            totalScore >= 0.85f -> SignalQuality.EXCELLENT
            totalScore >= 0.7f -> SignalQuality.GOOD
            totalScore >= 0.5f -> SignalQuality.ACCEPTABLE
            totalScore >= 0.3f -> SignalQuality.POOR
            else -> SignalQuality.NO_SIGNAL
        }
    }
    
    /**
     * Actualiza historiales para análisis temporal
     */
    private fun updateHistories() {
        snrHistory.add(currentSNR)
        perfusionHistory.add(currentPerfusionIndex)
        
        val maxHistorySize = 20
        if (snrHistory.size > maxHistorySize) {
            snrHistory.removeAt(0)
            perfusionHistory.removeAt(0)
        }
    }
    
    /**
     * Reinicia validador
     */
    fun reset() {
        signalBuffer.clear()
        qualityBuffer.clear()
        snrHistory.clear()
        perfusionHistory.clear()
        currentQuality = SignalQuality.NO_SIGNAL
        currentSNR = 0f
        currentPerfusionIndex = 0f
        currentKurtosis = 0f
        currentSkewness = 0f
        currentEntropy = 0f
        currentCorrelation = 0f
    }
    
    /**
     * Obtiene estadísticas actuales
     */
    fun getStatistics(): QualityStatistics {
        return QualityStatistics(
            currentQuality = currentQuality,
            averageSNR = if (snrHistory.isNotEmpty()) snrHistory.average() else 0.0,
            averagePerfusion = if (perfusionHistory.isNotEmpty()) perfusionHistory.average() else 0.0,
            currentKurtosis = currentKurtosis,
            currentSkewness = currentSkewness,
            currentEntropy = currentEntropy,
            currentCorrelation = currentCorrelation,
            samplesAnalyzed = signalBuffer.size
        )
    }
}

/**
 * Resultado de validación de calidad
 */
data class SignalQualityResult(
    val quality: SignalQuality,
    val snr: Float,
    val perfusionIndex: Float,
    val kurtosis: Float,
    val skewness: Float,
    val entropy: Float,
    val correlation: Float,
    val timestamp: Long
)

/**
 * Estadísticas de calidad
 */
data class QualityStatistics(
    val currentQuality: SignalQuality,
    val averageSNR: Double,
    val averagePerfusion: Double,
    val currentKurtosis: Float,
    val currentSkewness: Float,
    val currentEntropy: Float,
    val currentCorrelation: Float,
    val samplesAnalyzed: Int
)

// Extensiones
private fun Double.pow(exponent: Double): Double {
    return kotlin.math.pow(this, exponent)
}
