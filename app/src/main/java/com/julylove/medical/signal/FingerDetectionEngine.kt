package com.julylove.medical.signal

import kotlin.math.*

/**
 * FingerDetectionEngine: Motor de detección de presencia de dedo y señal PPG real
 * Basado en mejores prácticas académicas para smartphone PPG
 * 
 * Algoritmos implementados:
 * - Detección de perfusión basada en ratio AC/DC
 * - Análisis de variabilidad de señal
 * - Detección de contacto físico vs ruido ambiental
 * - Validación de morfología PPG
 */
class FingerDetectionEngine {
    
    // Parámetros basados en literatura académica
    private val minPerfusionRatio = 0.01f      // Mínimo ratio AC/DC para perfusión
    private val maxNoiseThreshold = 0.3f        // Máximo ruido permitido
    private val minSignalStability = 0.7f       // Mínimo estabilidad de señal
    private val contactDetectionWindow = 30     // Ventana para detección de contacto
    
    // Historial para análisis
    private val signalHistory = mutableListOf<Float>()
    private val perfusionHistory = mutableListOf<Float>()
    private val noiseHistory = mutableListOf<Float>()
    private val contactHistory = mutableListOf<Boolean>()
    
    // Estado actual
    private var lastDetectionTime = 0L
    private var currentSignalQuality = SignalQuality.NO_SIGNAL
    private var fingerPresent = false
    private var signalValid = false
    
    /**
     * Procesa frame de cámara para detectar presencia de dedo
     */
    fun processFrame(
        redValue: Float,
        greenValue: Float,
        blueValue: Float,
        timestamp: Long
    ): FingerDetectionResult {
        
        // 1. Calcular métricas de señal
        val totalIntensity = redValue + greenValue + blueValue
        val acComponent = calculateACComponent(redValue, greenValue, blueValue)
        val dcComponent = totalIntensity
        val perfusionRatio = if (dcComponent > 0) acComponent / dcComponent else 0f
        
        // 2. Calcular nivel de ruido
        val noiseLevel = calculateNoiseLevel(redValue, greenValue, blueValue)
        
        // 3. Actualizar historiales
        updateHistories(perfusionRatio, noiseLevel, totalIntensity)
        
        // 4. Detectar presencia de dedo
        fingerPresent = detectFingerPresence(perfusionRatio, noiseLevel, totalIntensity)
        
        // 5. Validar señal PPG
        signalValid = validatePPGSignal(perfusionRatio, noiseLevel, signalStability)
        
        // 6. Determinar calidad de señal
        currentSignalQuality = determineSignalQuality()
        
        lastDetectionTime = timestamp
        
        return FingerDetectionResult(
            fingerPresent = fingerPresent,
            signalValid = signalValid,
            signalQuality = currentSignalQuality,
            perfusionRatio = perfusionRatio,
            noiseLevel = noiseLevel,
            signalStability = signalStability,
            confidence = calculateDetectionConfidence(),
            timestamp = timestamp
        )
    }
    
    /**
     * Calcula componente AC (variación pulsátil)
     */
    private fun calculateACComponent(red: Float, green: Float, blue: Float): Float {
        // Usar canal verde como principal (mejor perfusión PPG)
        val primarySignal = green
        
        // Calcular variación respecto a tendencia reciente
        if (signalHistory.size < 10) return 0f
        
        val recentTrend = signalHistory.takeLast(10).average().toFloat()
        return abs(primarySignal - recentTrend)
    }
    
    /**
     * Calcula nivel de ruido basado en variación entre canales
     */
    private fun calculateNoiseLevel(red: Float, green: Float, blue: Float): Float {
        // Ruido = variación no correlacionada entre canales
        val meanIntensity = (red + green + blue) / 3f
        val variance = ((red - meanIntensity).pow(2) + 
                      (green - meanIntensity).pow(2) + 
                      (blue - meanIntensity).pow(2)) / 3f
        
        return variance.coerceIn(0f, 1f)
    }
    
    /**
     * Actualiza historiales para análisis temporal
     */
    private fun updateHistories(perfusion: Float, noise: Float, signal: Float) {
        signalHistory.add(signal)
        perfusionHistory.add(perfusion)
        noiseHistory.add(noise)
        
        // Mantener tamaño de ventanas
        val maxHistory = 100
        if (signalHistory.size > maxHistory) {
            signalHistory.removeAt(0)
            perfusionHistory.removeAt(0)
            noiseHistory.removeAt(0)
        }
        
        contactHistory.add(fingerPresent)
        if (contactHistory.size > contactDetectionWindow) {
            contactHistory.removeAt(0)
        }
    }
    
    /**
     * Detecta presencia de dedo usando múltiples criterios
     */
    private fun detectFingerPresence(perfusion: Float, noise: Float, signal: Float): Boolean {
        // Criterio 1: Perfusión mínima
        if (perfusion < minPerfusionRatio) return false
        
        // Criterio 2: Nivel de señal adecuado
        if (signal < 50f || signal > 250f) return false
        
        // Criterio 3: Nivel de ruido aceptable
        if (noise > maxNoiseThreshold) return false
        
        // Criterio 4: Estabilidad temporal
        if (signalHistory.size >= 20) {
            val recentVariance = calculateVariance(signalHistory.takeLast(20))
            if (recentVariance > 0.5f) return false
        }
        
        return true
    }
    
    /**
     * Valida que la señal sea PPG real y no ruido
     */
    private fun validatePPGSignal(perfusion: Float, noise: Float, stability: Float): Boolean {
        if (!fingerPresent) return false
        
        // Validación de morfología PPG básica
        if (perfusionHistory.size < 30) return false
        
        val recentPerfusion = perfusionHistory.takeLast(30)
        
        // Buscar patrón pulsátil (variación periódica)
        val hasPulsatilePattern = detectPulsatilePattern(recentPerfusion)
        
        return hasPulsatilePattern && noise < maxNoiseThreshold && stability > minSignalStability
    }
    
    /**
     * Detecta patrón pulsátil en la señal
     */
    private fun detectPulsatilePattern(signal: List<Float>): Boolean {
        if (signal.size < 20) return false
        
        // Calcular autocorrelación para detectar periodicidad
        val correlations = mutableListOf<Float>()
        
        for (lag in 5..15) {
            val correlation = calculateAutocorrelation(signal, lag)
            correlations.add(correlation)
        }
        
        // Buscar pico de correlación (indicativo de periodicidad)
        val maxCorrelation = correlations.maxOrNull() ?: 0f
        return maxCorrelation > 0.3f
    }
    
    /**
     * Calcula autocorrelación para detectar periodicidad
     */
    private fun calculateAutocorrelation(signal: List<Float>, lag: Int): Float {
        if (signal.size <= lag) return 0f
        
        val n = signal.size - lag
        var sum = 0f
        
        for (i in 0 until n) {
            sum += signal[i] * signal[i + lag]
        }
        
        return sum / n
    }
    
    /**
     * Determina calidad general de la señal
     */
    private fun determineSignalQuality(): SignalQuality {
        if (!fingerPresent) return SignalQuality.NO_SIGNAL
        if (!signalValid) return SignalQuality.POOR
        
        val avgPerfusion = perfusionHistory.takeLast(20).average().toFloat()
        val avgNoise = noiseHistory.takeLast(20).average().toFloat()
        
        return when {
            avgPerfusion > 0.05f && avgNoise < 0.1f && signalStability > 0.9f -> SignalQuality.EXCELLENT
            avgPerfusion > 0.03f && avgNoise < 0.15f && signalStability > 0.8f -> SignalQuality.GOOD
            avgPerfusion > 0.02f && avgNoise < 0.2f && signalStability > 0.7f -> SignalQuality.ACCEPTABLE
            else -> SignalQuality.POOR
        }
    }
    
    /**
     * Calcula confianza en la detección
     */
    private fun calculateDetectionConfidence(): Float {
        if (!fingerPresent) return 0f
        
        val perfusionScore = (perfusionHistory.lastOrNull() ?: 0f) / minPerfusionRatio
        val noiseScore = 1f - ((noiseHistory.lastOrNull() ?: 0f) / maxNoiseThreshold)
        val stabilityScore = signalStability
        val consistencyScore = if (contactHistory.size >= contactDetectionWindow) {
            contactHistory.count { it }.toFloat() / contactDetectionWindow
        } else 0f
        
        return (perfusionScore * 0.3f + noiseScore * 0.2f + 
                stabilityScore * 0.3f + consistencyScore * 0.2f).coerceIn(0f, 1f)
    }
    
    /**
     * Calcula estabilidad de la señal
     */
    private val signalStability: Float
        get() {
            if (signalHistory.size < 20) return 0f
            
            val recent = signalHistory.takeLast(20)
            val mean = recent.average().toFloat()
            val variance = calculateVariance(recent)
            
            // Estabilidad = 1 - variabilidad normalizada
            return (1f - variance / mean).coerceIn(0f, 1f)
        }
    
    /**
     * Calcula varianza de una lista
     */
    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        
        val mean = values.average().toFloat()
        val sumSquaredDiff = values.sumOf { (it - mean).pow(2) }
        return sumSquaredDiff / values.size
    }
    
    /**
     * Reinicia el motor de detección
     */
    fun reset() {
        signalHistory.clear()
        perfusionHistory.clear()
        noiseHistory.clear()
        contactHistory.clear()
        lastDetectionTime = 0L
        currentSignalQuality = SignalQuality.NO_SIGNAL
        fingerPresent = false
        signalValid = false
    }
    
    /**
     * Obtiene estado actual
     */
    fun getCurrentState(): FingerDetectionState {
        return FingerDetectionState(
            fingerPresent = fingerPresent,
            signalValid = signalValid,
            signalQuality = currentSignalQuality,
            confidence = calculateDetectionConfidence(),
            avgPerfusion = if (perfusionHistory.isNotEmpty()) perfusionHistory.average() else 0.0,
            avgNoise = if (noiseHistory.isNotEmpty()) noiseHistory.average() else 0.0
        )
    }
}

/**
 * Resultado de detección de dedo
 */
data class FingerDetectionResult(
    val fingerPresent: Boolean,
    val signalValid: Boolean,
    val signalQuality: SignalQuality,
    val perfusionRatio: Float,
    val noiseLevel: Float,
    val signalStability: Float,
    val confidence: Float,
    val timestamp: Long
)

/**
 * Estado de detección
 */
data class FingerDetectionState(
    val fingerPresent: Boolean,
    val signalValid: Boolean,
    val signalQuality: SignalQuality,
    val confidence: Float,
    val avgPerfusion: Double,
    val avgNoise: Double
)

/**
 * Calidad de señal
 */
enum class SignalQuality {
    NO_SIGNAL,     // Sin señal detectada
    POOR,          // Señal muy pobre
    ACCEPTABLE,    // Señal aceptable
    GOOD,          // Buena señal
    EXCELLENT      // Señal excelente
}

/**
 * Extensión para potencia
 */
private fun Float.pow(exponent: Int): Float {
    return this.toDouble().pow(exponent.toDouble()).toFloat()
}
