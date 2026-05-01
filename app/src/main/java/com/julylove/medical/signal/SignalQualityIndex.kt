package com.julylove.medical.signal

import kotlin.math.sqrt

/**
 * Índice de calidad óptico (SQI) para ponderar RoR / SpO₂ sin valores simulados.
 *
 * Inspirado en control de calidad típico de PPG reflejo (Lee et al., 2012; revisiones DL Tison et al., 2024).
 */
object SignalQualityIndex {

    fun fromOpticalStreams(
        redHistory: List<Float>,
        greenHistory: List<Float>,
        blueHistory: List<Float>,
        motionIntensity: Float,
        perfusionRatio: Float
    ): Float {
        if (redHistory.size < 8 || greenHistory.size < 8) return 0f

        fun cv(samples: List<Float>): Float {
            val mean = samples.average().toFloat().coerceAtLeast(1e-6f)
            var s = 0f
            for (v in samples) {
                val d = v - mean
                s += d * d
            }
            return sqrt(s / samples.size) / mean
        }

        val redCv = cv(redHistory)
        val greenCv = cv(greenHistory)
        val blueCv = cv(blueHistory)

        // Estabilidad multi-canal + ausencia de movimiento + perfusión mínima
        val stability = (1f - (redCv + greenCv + blueCv).coerceIn(0f, 3f) / 3f).coerceIn(0f, 1f)
        val motionPenalty = (1f - (motionIntensity / 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val perfTerm = ((perfusionRatio - 0.05f) / 5f).coerceIn(0f, 1f)

        return (stability * 0.5f + motionPenalty * 0.35f + perfTerm * 0.15f).coerceIn(0f, 1f)
    }
}
