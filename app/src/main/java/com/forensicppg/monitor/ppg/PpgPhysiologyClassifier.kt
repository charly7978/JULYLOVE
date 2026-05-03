package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.PpgValidityState

/**
 * Clasificador evidencial PPG. Su función es etiquetar la evidencia
 * disponible (RAW_OPTICAL_ONLY → PPG_CANDIDATE → PPG_VALID → BIOMETRIC_VALID),
 * NO bloquear el detector de picos. Por eso los gates son **suaves y
 * monotónicos**: cualquier evidencia espectral o de autocorrelación
 * mínima ya empuja a CANDIDATE; el detector trabaja independientemente.
 *
 * Umbrales calibrados con datos típicos de smartphone bajo flash blanco
 * (Mather 2024 scoping review FC-PPG vs ECG; Bánhalmi 2018 spectral PPG):
 *
 *   - heartBandFraction: 0.04..0.30 típico en buenas grabaciones cPPG.
 *   - autocorr lag-pulso: 0.08..0.6 cuando el pulso es visible.
 *   - SNR pulso: -18..+5 dB en cámara visible con torch.
 *   - SQI compuesto: 0.10..0.85.
 */
object PpgPhysiologyClassifier {

    fun classify(
        sqiComposite: Double,
        spectral: PpgSignalProcessor.SpectrumSummary,
        opticalMotionSmoothed: Double,
        clippingHighRatio: Double,
        roiRedDominance: Double,
        greenAcDcBandEstimate: Double
    ): PpgValidityState {
        // 1) ¿Hay rastro óptico de algo cardíaco?
        val heartFreqOk = spectral.dominantFreqHz in 0.5..4.5
        val anyEvidence =
            (spectral.heartBandFraction >= 0.025 && heartFreqOk) ||
                spectral.autocorrPulseStrength >= 0.06

        // 2) Si hay clipping severo y motion fuerte, retroceder fuerte.
        val severeClip = clippingHighRatio > 0.40
        val severeMotion = opticalMotionSmoothed > 0.85

        if (severeClip || severeMotion) return PpgValidityState.RAW_OPTICAL_ONLY

        if (!anyEvidence) {
            // Si la firma óptica es claramente "dedo apoyado" pero no hay
            // banda cardíaca, marcar como NO_PHYSIOLOGICAL_SIGNAL para
            // diferenciarlo de "ROI vacío".
            return if (roiRedDominance > 2.5 && greenAcDcBandEstimate < 0.005) {
                PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
            } else {
                PpgValidityState.RAW_OPTICAL_ONLY
            }
        }

        // 3) PPG_CANDIDATE: evidencia mínima de pulso, sin exigir SQI alto.
        var step: PpgValidityState = PpgValidityState.PPG_CANDIDATE

        // 4) PPG_VALID: SQI > 0.30 + SNR razonable + sin clipping fuerte.
        if (sqiComposite >= 0.30 && spectral.snrHeartDbEstimate >= -14.0 &&
            opticalMotionSmoothed < 0.60 && clippingHighRatio < 0.25
        ) {
            step = PpgValidityState.PPG_VALID
        }

        // 5) BIOMETRIC_VALID: condiciones limpias para SpO2 calibrada.
        if (step == PpgValidityState.PPG_VALID &&
            sqiComposite >= 0.55 && spectral.snrHeartDbEstimate >= -10.0 &&
            opticalMotionSmoothed < 0.40 && clippingHighRatio < 0.18
        ) {
            step = PpgValidityState.BIOMETRIC_VALID
        }

        return step
    }
}
