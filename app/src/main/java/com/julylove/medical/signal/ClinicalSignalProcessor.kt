package com.julylove.medical.signal

import kotlin.math.*

/**
 * ClinicalSignalProcessor - Procesador de señales PPG de grado clínico
 */
class ClinicalSignalProcessor {
    
    companion object {
        private const val BUFFER_SIZE = 512
        private const val SAMPLING_RATE = 30  // Hz
    }
    
    // Buffers de señal (usando ArrayDeque para simplicidad)
    private val rawBuffer = ArrayDeque<Float>(BUFFER_SIZE)
    private val filteredBuffer = ArrayDeque<Float>(BUFFER_SIZE)
    
    // Filtros
    private val detrendingFilter = DetrendingFilter(90) // Componente DC
    private val bandpassFilter = ButterworthBandpass(SAMPLING_RATE.toFloat())
    private val smoothingFilter = SavitzkyGolayFilter()
    
    // Estado del procesador
    private var perfusionIndex = 0f
    
    /**
     * Procesa una muestra de señal PPG cruda
     */
    fun process(
        rawValue: Float,
        timestampNs: Long,
        redValue: Float,
        greenValue: Float,
        blueValue: Float
    ): ClinicalResult {
        
        rawBuffer.addLast(rawValue)
        if (rawBuffer.size > BUFFER_SIZE) rawBuffer.removeFirst()
        
        // Componente DC (Línea base)
        val meanValue = if (rawBuffer.isNotEmpty()) rawBuffer.average().toFloat() else rawValue
        val dcValue = meanValue
        
        // Componente AC (Pulso)
        val acValue = rawValue - dcValue
        
        // Calcular índice de perfusión
        perfusionIndex = if (dcValue > 0) abs(acValue) / dcValue else 0f
        
        // Verificar si hay presencia real de dedo
        val fingerPresent = detectFingerPresence(rawValue, redValue, greenValue, blueValue)
        
        if (!fingerPresent) {
            return ClinicalResult(
                timestampNs = timestampNs,
                isValid = false,
                fingerDetected = false,
                dcComponent = dcValue,
                acComponent = 0f,
                filteredValue = 0f,
                perfusionIndex = 0f,
                signalQuality = 0f,
                systolicPeak = false,
                dicroticNotch = false,
                diastolicPeak = false,
                valley = false
            )
        }
        
        // Filtrado
        val bandpassed = bandpassFilter.filter(acValue)
        val smoothed = smoothingFilter.filter(bandpassed)
        
        filteredBuffer.addLast(smoothed)
        if (filteredBuffer.size > BUFFER_SIZE) filteredBuffer.removeFirst()
        
        // Detectar puntos característicos
        val features = detectFiducialPoints()
        
        // Calcular calidad
        val quality = calculateSignalQuality(rawBuffer.toList())
        
        return ClinicalResult(
            timestampNs = timestampNs,
            isValid = quality > 0.3f,
            fingerDetected = true,
            dcComponent = dcValue,
            acComponent = acValue,
            filteredValue = smoothed,
            perfusionIndex = perfusionIndex,
            signalQuality = quality,
            systolicPeak = features.systolicPeak,
            dicroticNotch = features.dicroticNotch,
            diastolicPeak = features.diastolicPeak,
            valley = features.valley,
            peakConfidence = features.confidence
        )
    }
    
    private fun detectFingerPresence(
        rawValue: Float,
        red: Float,
        green: Float,
        blue: Float
    ): Boolean {
        if (rawValue > 250f || rawValue < 5f) return false
        
        val totalIntensity = red + green + blue
        if (totalIntensity < 1f) return false
        
        val redRatio = red / totalIntensity
        val greenRatio = green / totalIntensity
        val blueRatio = blue / totalIntensity
        
        if (greenRatio > redRatio * 1.2f || blueRatio > redRatio * 1.3f) return false
        if (perfusionIndex < 0.005f) return false
        
        if (rawBuffer.size >= 30) {
            val recent = rawBuffer.toList().takeLast(30)
            val mean = recent.average()
            val variance = recent.map { (it - mean).pow(2) }.average()
            val cv = sqrt(variance) / mean
            if (cv < 0.001 || cv > 0.3) return false
        }
        
        return true
    }
    
    private fun detectFiducialPoints(): FiducialFeatures {
        val data = filteredBuffer.toList()
        if (data.size < 3) return FiducialFeatures()
        
        val curr = data.last()
        val prev = data[data.size - 2]
        val prev2 = data[data.size - 3]
        
        val isPeak = prev > prev2 && prev > curr && prev > 0.001f
        val isValley = prev < prev2 && prev < curr && prev < -0.001f
        
        return FiducialFeatures(
            systolicPeak = isPeak,
            valley = isValley,
            confidence = if (isPeak) 0.8f else 0f
        )
    }
    
    private fun calculateSignalQuality(rawData: List<Float>): Float {
        if (rawData.size < 60) return 0.5f
        
        val signalPower = filteredBuffer.map { it * it }.average().toFloat()
        val noisePower = rawData.zipWithNext { a, b -> (a - b).pow(2) }.average().toFloat()
        
        val snr = if (noisePower > 0) 10 * log10((signalPower + 1e-6f) / (noisePower + 1e-6f)) else 0f
        val snrScore = ((snr + 20f) / 40f).coerceIn(0f, 1f)
        
        val perfusionScore = (perfusionIndex * 20f).coerceIn(0f, 1f)
        
        return (snrScore * 0.5f + perfusionScore * 0.5f)
    }
    
    fun reset() {
        rawBuffer.clear()
        filteredBuffer.clear()
        bandpassFilter.reset()
        smoothingFilter.reset()
        detrendingFilter.reset()
    }
}

data class ClinicalResult(
    val timestampNs: Long,
    val isValid: Boolean,
    val fingerDetected: Boolean,
    val dcComponent: Float,
    val acComponent: Float,
    val filteredValue: Float,
    val perfusionIndex: Float,
    val signalQuality: Float,
    val systolicPeak: Boolean,
    val dicroticNotch: Boolean,
    val diastolicPeak: Boolean,
    val valley: Boolean,
    val peakConfidence: Float = 0f
)

data class FiducialFeatures(
    val systolicPeak: Boolean = false,
    val dicroticNotch: Boolean = false,
    val diastolicPeak: Boolean = false,
    val valley: Boolean = false,
    val confidence: Float = 0f
)
