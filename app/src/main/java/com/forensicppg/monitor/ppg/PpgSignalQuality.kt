package com.forensicppg.monitor.ppg

import kotlin.math.abs

/** SQI combinado PSD + clipping + RR + autocorrelación/coherencia. */
class PpgSignalQuality {

    data class ComposerInput(
        val spectralHeartFrac: Double,
        val spectralSnrDb: Double,
        val autocorr01: Double,
        val coherenceRg: Double,
        val clippingHigh: Double,
        val clippingLow: Double,
        val motionCombined01: Double,
        val rrCv: Double?,
        val rrCount: Int
    )

    fun compose(ci: ComposerInput): Double {
        val snr01 = ((ci.spectralSnrDb + 21.5) / 41.5).coerceIn(0.0, 1.0)
        val heart01 = ci.spectralHeartFrac.coerceIn(0.0, 1.0)
        val motionBounded = abs(ci.motionCombined01).coerceIn(0.0, 1.2)
        val clipSum = ci.clippingHigh * 5.4 + ci.clippingLow * 3.6 + motionBounded * 2.10
        val clipPenalty = clipSum.coerceIn(0.0, 7.0) / 7.0
        val rr01 = when {
            ci.rrCv != null && ci.rrCount >= 6 ->
                (1.0 - ci.rrCv * 15.0).coerceIn(0.0, 1.0)
            ci.rrCount <= 3 -> 0.32
            else -> 0.68
        }
        val wSum = 0.32 + 0.31 + 0.43 + 0.34 + 0.48
        val corr01 =
            (
                0.32 * ci.autocorr01 + 0.31 * ci.coherenceRg + 0.43 * rr01 +
                    0.34 * heart01 + 0.48 * snr01
                ) / wSum
        val weighted = corr01 - clipPenalty.coerceIn(0.32, 0.93)
        return weighted.coerceIn(0.0, 1.0)
    }

    enum class SqBand { EXCELLENT, ACCEPTABLE, DEGRADED, INVALID }

    fun band(score: Double): SqBand =
        when {
            score >= 0.72 -> SqBand.EXCELLENT
            score >= 0.53 -> SqBand.ACCEPTABLE
            score >= 0.36 -> SqBand.DEGRADED
            else -> SqBand.INVALID
        }

    companion object {
        fun bandLabelEs(b: SqBand): String =
            when (b) {
                SqBand.EXCELLENT -> "EXCELENTE"
                SqBand.ACCEPTABLE -> "ACEPTABLE"
                SqBand.DEGRADED -> "DEBIL"
                SqBand.INVALID -> "NO UTILIZABLE"
            }
    }
}
