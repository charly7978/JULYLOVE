package com.julylove.medical.signal

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced Signal Quality Index (SQI) for PPG.
 * Evaluates morphological and statistical features of the signal.
 */
class SignalQualityIndexEngine {

    enum class QualityLevel {
        EXCELENTE,
        BUENA,
        REGULAR,
        MALA,
        NO_INTERPRETABLE
    }

    private val window = mutableListOf<Float>()
    private val windowSize = 120 // ~2 seconds

    fun calculateSQI(
        filteredValue: Float, 
        redDC: Float, 
        greenDC: Float, 
        isFingerDetected: Boolean,
        isMoving: Boolean
    ): Pair<QualityLevel, Float> {
        if (!isFingerDetected) return QualityLevel.NO_INTERPRETABLE to 0.0f
        if (isMoving) return QualityLevel.MALA to 0.2f

        window.add(filteredValue)
        if (window.size > windowSize) window.removeAt(0)
        if (window.size < windowSize) return QualityLevel.REGULAR to 0.5f

        // 1. Perfusion Index (PI) Proxy
        // AC is approx the range, DC is the mean of the raw signal
        val amplitude = (window.maxOrNull() ?: 0f) - (window.minOrNull() ?: 0f)
        val perfusionIndex = (amplitude / greenDC.coerceAtLeast(1f)) * 100f

        // 2. Kurtosis (Statistical measure of "peakedness")
        val mean = window.average().toFloat()
        val stdDev = sqrt(window.map { (it - mean).pow(2) }.average()).toFloat()
        
        var kurtosisSum = 0f
        for (x in window) {
            kurtosisSum += ((x - mean) / stdDev.coerceAtLeast(0.01f)).pow(4)
        }
        val kurtosis = (kurtosisSum / window.size) - 3f

        // 3. SNR Estimate
        val variance = stdDev.pow(2)
        val snr = amplitude / (variance.coerceAtLeast(0.01f))

        // Scoring Logic (0.0 to 1.0)
        var score = 0.5f
        
        // Perfusion check (Typical PPG PI is 0.1% to 20%)
        if (perfusionIndex in 0.5f..15f) score += 0.2f
        
        // Kurtosis check (Clean PPG usually has positive kurtosis > 1)
        if (kurtosis > 0.5f) score += 0.2f
        
        // Clipping check (If DC is too high, sensor is saturated)
        if (greenDC > 250f) score -= 0.4f

        val level = when {
            score > 0.85f -> QualityLevel.EXCELENTE
            score > 0.70f -> QualityLevel.BUENA
            score > 0.45f -> QualityLevel.REGULAR
            score > 0.25f -> QualityLevel.MALA
            else -> QualityLevel.NO_INTERPRETABLE
        }

        return level to score.coerceIn(0f, 1f)
    }
}
