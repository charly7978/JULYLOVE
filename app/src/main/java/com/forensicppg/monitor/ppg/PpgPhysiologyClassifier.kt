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
        val bandOk = spectral.heartBandFraction >= 0.058 && heartHz in 0.52..4.38 &&
                spectral.autocorrPulseStrength >= 0.047

        if (!bandOk || sqiComposite < 0.085) {
            if (roiRedDominance > 2.85 && greenAcDcBandEstimate < 0.032 && clippingHighRatio < 0.13) {
                return PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
            }
            return PpgValidityState.RAW_OPTICAL_ONLY
        }

        var step = when {
            sqiComposite < 0.24 ->
                if (spectral.autocorrPulseStrength < 0.058) PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
                else PpgValidityState.RAW_OPTICAL_ONLY
            spectral.snrHeartDbEstimate < -15.9 && spectral.autocorrPulseStrength < 0.066 ->
                PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL
            clippingHighRatio > 0.40 || clippingHighRatio > 0.12 && spectral.coherenceRg < 0.085 ->
                PpgValidityState.RAW_OPTICAL_ONLY
            sqiComposite >= 0.37 && spectral.coherenceRg > 0.09 && spectral.autocorrPulseStrength >= 0.064 ->
                PpgValidityState.PPG_CANDIDATE
            else -> PpgValidityState.RAW_OPTICAL_ONLY
        }

        if ((step.ordinal >= PpgValidityState.PPG_CANDIDATE.ordinal || opticalMotionSmoothed < 0.44) &&
            sqiComposite >= 0.45 && clippingHighRatio < 0.25 && spectral.snrHeartDbEstimate >= -15.2
        ) {
            step = PpgValidityState.PPG_VALID
        }

        if (step.ordinal >= PpgValidityState.PPG_VALID.ordinal &&
            sqiComposite >= 0.56 && spectral.snrHeartDbEstimate >= -12.3 &&
            opticalMotionSmoothed < 0.70 && clippingHighRatio < 0.185
        ) {
            step = PpgValidityState.BIOMETRIC_VALID
        }
        return step
    }
}
