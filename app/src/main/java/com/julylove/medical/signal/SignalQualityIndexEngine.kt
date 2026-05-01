package com.julylove.medical.signal

import kotlin.math.abs

/**
 * Signal Quality Index (SQI) estimator.
 * Combines factors like pulsatile amplitude, SNR, and movement to determine signal reliability.
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
    private val windowSize = 60 // ~1-2 seconds of data

    fun calculateSQI(value: Float, isFingerDetected: Boolean): Pair<QualityLevel, Float> {
        if (!isFingerDetected) return QualityLevel.NO_INTERPRETABLE to 0f

        window.add(value)
        if (window.size > windowSize) window.removeAt(0)

        if (window.size < windowSize) return QualityLevel.REGULAR to 0.5f

        // Calculate simple SNR proxy based on variance and mean
        val mean = window.average().toFloat()
        val variance = window.map { (it - mean) * (it - mean) }.average().toFloat()
        val amplitude = (window.maxOrNull() ?: 0f) - (window.minOrNull() ?: 0f)

        // Quality score (0.0 to 1.0)
        var score = 0.8f
        
        // Penalize low amplitude (weak signal)
        if (amplitude < 5f) score -= 0.3f
        
        // Penalize high variance relative to amplitude (noise)
        if (variance > amplitude * 2) score -= 0.2f

        val level = when {
            score > 0.85f -> QualityLevel.EXCELENTE
            score > 0.7f -> QualityLevel.BUENA
            score > 0.5f -> QualityLevel.REGULAR
            score > 0.3f -> QualityLevel.MALA
            else -> QualityLevel.NO_INTERPRETABLE
        }

        return level to score
    }
}
