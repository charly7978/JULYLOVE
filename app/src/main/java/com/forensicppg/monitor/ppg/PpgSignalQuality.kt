package com.forensicppg.monitor.ppg

/**
 * SQI duro: `PPG_VALID` exige pasar **todos** los subcriterios vía
 * [PpgPhysiologyClassifier] usando el compositor y flags explícitos.
 */
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
        val rrCount: Int,
        val maskCoverage: Double,
        val maskSustained2s: Boolean,
        val greenAcDc: Double,
        val dominantInValidationBand: Boolean
    )

    /**
     * Retorna score [0,1] pero los **umbrales duros** se aplican en el clasificador;
     * aquí se penaliza fuerte cualquier fallo duro.
     */
    fun compose(ci: ComposerInput): Double {
        if (!ci.maskSustained2s || ci.maskCoverage < 0.70) return 0.0
        if (ci.clippingHigh >= 0.08 || ci.clippingLow >= 0.05) return 0.0
        if (ci.motionCombined01 >= 0.20) return 0.0
        if (!ci.dominantInValidationBand) return 0.02
        if (ci.spectralSnrDb < 6.0) return 0.05
        if (ci.autocorr01 < 0.35) return 0.06
        if (ci.coherenceRg < 0.22) return 0.07
        if (ci.greenAcDc < 0.008 || ci.greenAcDc > 0.28) return 0.04

        val snr01 = ((ci.spectralSnrDb - 6.0) / 24.0).coerceIn(0.0, 1.0)
        val heart01 = ci.spectralHeartFrac.coerceIn(0.0, 1.0)
        val mask01 = ((ci.maskCoverage - 0.70) / 0.28).coerceIn(0.0, 1.0)
        val rr01 = when {
            ci.rrCv != null && ci.rrCount >= 7 ->
                (1.0 - ci.rrCv * 12.0).coerceIn(0.0, 1.0)
            ci.rrCount < 5 -> 0.15
            else -> 0.45
        }
        val wSum = 0.35 + 0.28 + 0.22 + 0.28 + 0.18 + 0.22
        val corr01 =
            (
                0.35 * ci.autocorr01 + 0.28 * ci.coherenceRg + 0.22 * rr01 +
                    0.28 * heart01 + 0.18 * snr01 + 0.22 * mask01
                ) / wSum
        return corr01.coerceIn(0.0, 1.0)
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
