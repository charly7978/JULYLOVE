package com.julylove.medical.signal

/**
 * FingerDetectionEngine - Motor de detección de presencia de dedo
 * 
 * Basado en análisis de perfusión y características ópticas
 * NO SIMULA - detecta presencia real basada en señal fisiológica
 */
class FingerDetectionEngine {
    
    data class DetectionResult(
        val fingerPresent: Boolean,
        val signalValid: Boolean,
        val signalQuality: SignalQuality,
        val confidence: Float,
        val perfusionIndex: Float,
        val contactPressure: Float  // Estimación de presión de contacto
    )
    
    private var history = ArrayDeque<Triple<Float, Float, Float>>(100)
    private var lastDetection = false
    private var detectionConfidence = 0f
    
    fun processFrame(
        red: Float,
        green: Float,
        blue: Float,
        timestampNs: Long
    ): DetectionResult {
        
        // Almacenar historial
        history.add(Triple(red, green, blue))
        if (history.size > 100) history.removeFirst()
        
        // 1. Verificar intensidades válidas (no saturadas ni muy bajas)
        val totalIntensity = red + green + blue
        if (totalIntensity > 600f || totalIntensity < 10f) {
            return DetectionResult(
                fingerPresent = false,
                signalValid = false,
                signalQuality = SignalQuality.NO_SIGNAL,
                confidence = 0f,
                perfusionIndex = 0f,
                contactPressure = 0f
            )
        }
        
        // 2. Análisis de perfusión (variación temporal)
        val perfusion = calculatePerfusion()
        
        // 3. Análisis espectral de canales
        val redRatio = red / totalIntensity
        val greenRatio = green / totalIntensity
        val blueRatio = blue / totalIntensity
        
        // La sangre absorbe más verde/azul que rojo
        val validHemoglobinSignature = greenRatio < redRatio * 1.1f && blueRatio < redRatio * 1.2f
        
        // 4. Determinar presencia de dedo
        val fingerPresent = perfusion > 0.005f && validHemoglobinSignature && totalIntensity in 20f..400f
        
        // 5. Calcular calidad de señal
        val signalQuality = when {
            !fingerPresent -> SignalQuality.NO_SIGNAL
            perfusion > 0.05f -> SignalQuality.EXCELLENT
            perfusion > 0.03f -> SignalQuality.GOOD
            perfusion > 0.015f -> SignalQuality.ACCEPTABLE
            else -> SignalQuality.POOR
        }
        
        // 6. Validar señal
        val signalValid = fingerPresent && perfusion > 0.01f
        
        // 7. Calcular confianza
        val confidence = when {
            !fingerPresent -> 0f
            signalQuality == SignalQuality.EXCELLENT -> 0.95f
            signalQuality == SignalQuality.GOOD -> 0.8f
            signalQuality == SignalQuality.ACCEPTABLE -> 0.6f
            else -> 0.4f
        }
        
        // 8. Estimar presión de contacto (basada en amplitud DC)
        val contactPressure = (totalIntensity / 500f).coerceIn(0f, 1f)
        
        return DetectionResult(
            fingerPresent = fingerPresent,
            signalValid = signalValid,
            signalQuality = signalQuality,
            confidence = confidence,
            perfusionIndex = perfusion,
            contactPressure = contactPressure
        )
    }
    
    private fun calculatePerfusion(): Float {
        if (history.size < 10) return 0f
        
        val recent = history.takeLast(30)
        val values: List<Float> = recent.map { it.first + it.second + it.third }
        val mean: Double = values.average()
        
        if (mean == 0.0) return 0f
        
        // Calcular variación estándar
        val squaredDiffs: List<Double> = values.map { 
            val diff = it - mean
            (diff * diff).toDouble()
        }
        val variance: Double = squaredDiffs.average()
        
        val stdDev = kotlin.math.sqrt(variance)
        return (stdDev / mean).toFloat()
    }
    
    fun reset() {
        history.clear()
        lastDetection = false
        detectionConfidence = 0f
    }
}
