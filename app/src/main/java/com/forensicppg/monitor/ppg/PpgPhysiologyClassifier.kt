package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.PpgValidityState
object PpgPhysiologyClassifier {

    fun classify(
        sqiComposite: Double,
        spectral: PpgSignalProcessor.SpectrumSummary,
        opticalMotionSmoothed: Double,
        clippingHighRatio: Double,
        roiRedDominance: Double,
        greenAcDcBandEstimate: Double
    ): PpgValidityState {
        val heartHz = spectral.dominantFreqHz
        val bandOk = spectral.heartBandFraction >= 0.075 && heartHz in 0.64..4.12 &&
                spectral.autocorrPulseStrength >= 0.055

        if (!bandOk || sqiComposite < 0.10) {
            if (roiRedDominance > 2.85 && greenAcDcBandEstimate < 0.032 && clippingHighRatio < 0.13) {
                return PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
            }
            return PpgValidityState.RAW_OPTICAL_ONLY
        }

        var step = when {
            sqiComposite < 0.28 ->
                if (spectral.autocorrPulseStrength < 0.066) PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
                else PpgValidityState.RAW_OPTICAL_ONLY
            spectral.snrHeartDbEstimate < -13.9 && spectral.autocorrPulseStrength < 0.078 ->
                PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
            clippingHighRatio > 0.36 || clippingHighRatio > 0.10 && spectral.coherenceRg < 0.11 ->
                PpgValidityState.RAW_OPTICAL_ONLY
            sqiComposite >= 0.43 && spectral.coherenceRg > 0.12 && spectral.autocorrPulseStrength >= 0.078 ->
                PpgValidityState.PPG_CANDIDATE
            else -> PpgValidityState.RAW_OPTICAL_ONLY
        }

        if ((step.ordinal >= PpgValidityState.PPG_CANDIDATE.ordinal || opticalMotionSmoothed < 0.38) &&
            sqiComposite >= 0.52 && clippingHighRatio < 0.22 && spectral.snrHeartDbEstimate >= -13.9
        ) {
            step = PpgValidityState.PPG_VALID
        }

        if (step.ordinal >= PpgValidityState.PPG_VALID.ordinal &&
            sqiComposite >= 0.62 && spectral.snrHeartDbEstimate >= -11.9 &&
            opticalMotionSmoothed < 0.62 && clippingHighRatio < 0.16
        ) {
            step = PpgValidityState.BIOMETRIC_VALID
        }
        return step
    }
}
