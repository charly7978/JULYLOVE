package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.PpgValidityState

/**
 * Clasificador fisiológico con gates duros.
 *
 * PPG_VALID requiere simultáneamente:
 *  1. Contacto real (máscara sostenida ≥ 70 % durante ≥ 2 s)
 *  2. Componente pulsátil (AC/DC verde en rango útil)
 *  3. Periodicidad cardíaca (dominante en 0.7–3.5 Hz, autocorrelación ≥ 0.35)
 *  4. Morfología de pico compatible (SNR ≥ 6 dB, coherencia R/G ≥ 0.22)
 *  5. Latidos confirmados (≥ 5 picos con RR fisiológico)
 *
 * La quietud NO es evidencia fisiológica: movimiento bajo + sin pulsatilidad
 * produce QUIET_NO_PULSE, nunca PPG_VALID.
 */
object PpgPhysiologyClassifier {

    fun classify(
        sqiComposite: Double,
        spectral: PpgSignalProcessor.SpectrumSummary,
        opticalMotionSmoothed: Double,
        clippingHighRatio: Double,
        clippingLowRatio: Double,
        maskCoverage: Double,
        maskSustained2s: Boolean,
        peakConfirmedCount: Int,
        rrIntervalCount: Int,
        roiFingerProfileScore: Double,
        greenAcDcBandEstimate: Double,
        contactScore: Double
    ): PpgValidityState {

        // ── Gates de rechazo inmediato (orden: más urgente primero) ──

        if (opticalMotionSmoothed >= 0.20) return PpgValidityState.MOTION
        if (clippingHighRatio >= 0.08) return PpgValidityState.CLIPPING
        if (clippingLowRatio >= 0.05 || spectral.dcStability < 0.12)
            return PpgValidityState.LOW_LIGHT
        if (maskCoverage < 0.35 || contactScore < 0.18)
            return PpgValidityState.BAD_CONTACT
        if (!maskSustained2s || maskCoverage < 0.70) {
            return if (maskCoverage < 0.18) PpgValidityState.BAD_CONTACT
            else PpgValidityState.SEARCHING
        }
        if (greenAcDcBandEstimate < 0.008) return PpgValidityState.LOW_PERFUSION
        if (roiFingerProfileScore < 0.28) return PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL

        // ── Evidencia espectral cardíaca ──

        val hzVal = spectral.dominantFreqHz
        val bandOk =
            hzVal in 0.70..3.5 &&
                spectral.heartBandFraction >= 0.055 &&
                spectral.autocorrPulseStrength >= 0.38 &&
                spectral.snrHeartDbEstimate >= 6.0 &&
                spectral.coherenceRg >= 0.22

        // ── Sin evidencia espectral: distinguir quietud de señal ──

        if (!bandOk || sqiComposite < 0.12) {
            // Quietud sin pulso: movimiento bajo + cobertura OK + sin pulsatilidad
            if (opticalMotionSmoothed < 0.12 && maskSustained2s && greenAcDcBandEstimate < 0.02) {
                return PpgValidityState.QUIET_NO_PULSE
            }
            return PpgValidityState.RAW_OPTICAL_ONLY
        }

        // ── Evidencia de latidos reales ──

        val beatsEvidence = minOf(peakConfirmedCount, rrIntervalCount)

        if (sqiComposite < 0.22) return PpgValidityState.RAW_OPTICAL_ONLY
        if (beatsEvidence < 2) return PpgValidityState.PPG_CANDIDATE

        // ── Gate PPG_VALID: todos los criterios simultáneos ──

        if (sqiComposite >= 0.48 &&
            bandOk &&
            maskSustained2s &&
            beatsEvidence >= 5 &&
            spectral.autocorrPulseStrength >= 0.35
        ) {
            // Gate BIOMETRIC_VALID: umbral más exigente
            if (sqiComposite >= 0.58 &&
                beatsEvidence >= 7 &&
                opticalMotionSmoothed < 0.16 &&
                clippingHighRatio < 0.05
            ) {
                return PpgValidityState.BIOMETRIC_VALID
            }
            return PpgValidityState.PPG_VALID
        }

        return PpgValidityState.PPG_CANDIDATE
    }
}
