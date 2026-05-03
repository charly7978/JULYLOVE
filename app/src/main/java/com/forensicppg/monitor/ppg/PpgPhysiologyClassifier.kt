package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.PpgValidityState

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
        val hzVal = spectral.dominantFreqHz
        val bandOk =
            hzVal in 0.70..3.5 &&
                spectral.heartBandFraction >= 0.055 &&
                spectral.autocorrPulseStrength >= 0.38 &&
                spectral.snrHeartDbEstimate >= 6.0 &&
                spectral.coherenceRg >= 0.22

        if (opticalMotionSmoothed >= 0.20) return PpgValidityState.MOTION
        if (clippingHighRatio >= 0.08) return PpgValidityState.CLIPPING
        if (clippingLowRatio >= 0.05 || spectral.dcStability < 0.12) return PpgValidityState.LOW_LIGHT
        if (maskCoverage < 0.35 || contactScore < 0.18) return PpgValidityState.BAD_CONTACT
        if (!maskSustained2s || maskCoverage < 0.70) {
            if (maskCoverage < 0.18) return PpgValidityState.BAD_CONTACT
            return PpgValidityState.SEARCHING
        }
        if (greenAcDcBandEstimate < 0.012) return PpgValidityState.LOW_PERFUSION
        if (roiFingerProfileScore < 0.28) return PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL

        if (!bandOk || sqiComposite < 0.12) {
            if (opticalMotionSmoothed < 0.12 && maskSustained2s && greenAcDcBandEstimate < 0.02) {
                return PpgValidityState.QUIET_NO_PULSE
            }
            return PpgValidityState.RAW_OPTICAL_ONLY
        }

        val beatsEvidence = minOf(peakConfirmedCount, rrIntervalCount)

        var step = when {
            sqiComposite < 0.22 -> PpgValidityState.RAW_OPTICAL_ONLY
            beatsEvidence < 2 -> PpgValidityState.PPG_CANDIDATE
            else -> PpgValidityState.PPG_CANDIDATE
        }

        if (sqiComposite >= 0.48 &&
            bandOk &&
            maskSustained2s &&
            beatsEvidence >= 5 &&
            spectral.autocorrPulseStrength >= 0.35
        ) {
            step = PpgValidityState.PPG_VALID
        }

        if (step == PpgValidityState.PPG_VALID &&
            sqiComposite >= 0.58 &&
            beatsEvidence >= 7 &&
            opticalMotionSmoothed < 0.16 &&
            clippingHighRatio < 0.05
        ) {
            step = PpgValidityState.BIOMETRIC_VALID
        }
        return step
    }
}
