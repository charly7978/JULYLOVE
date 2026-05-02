package com.forensicppg.monitor.ppg

import kotlin.math.abs

/**
 * Detector de picos morfológico por pendiente (zero-crossing de la derivada
 * primera, de positivo a negativo). Requiere prominencia mínima y período
 * refractario para no disparar en artefactos.
 */
class PeakDetectorDerivative(
    sampleRateHz: Double,
    private val refractoryMs: Long = 260L,
    private val minProminence: Double = 1e-4
) {
    private var lastSample: Double = Double.NaN
    private var lastDeriv: Double = 0.0
    private var refractoryCountdown = 0
    private val refractoryN = (refractoryMs.toDouble() * sampleRateHz / 1000.0).toInt().coerceAtLeast(3)
    private var sampleIndex = 0L

    private var minSinceLastPeak = Double.POSITIVE_INFINITY
    private var peakCandidate: Double = Double.NaN
    private var peakCandidateTsNs: Long = 0L
    private var peakCandidateIdx: Long = -1L

    data class Detection(val sampleIndex: Long, val amplitude: Double, val timestampNs: Long, val prominence: Double)

    fun reset() {
        lastSample = Double.NaN
        lastDeriv = 0.0
        refractoryCountdown = 0
        sampleIndex = 0L
        minSinceLastPeak = Double.POSITIVE_INFINITY
        peakCandidate = Double.NaN
        peakCandidateTsNs = 0L
        peakCandidateIdx = -1L
    }

    fun feed(filtered: Double, timestampNs: Long): Detection? {
        if (refractoryCountdown > 0) refractoryCountdown--
        if (filtered < minSinceLastPeak) minSinceLastPeak = filtered

        val result: Detection? = if (!lastSample.isNaN()) {
            val deriv = filtered - lastSample
            val crossedDown = lastDeriv > 0.0 && deriv <= 0.0
            var emit: Detection? = null
            if (crossedDown) {
                val prominence = abs(lastSample - minSinceLastPeak)
                if (refractoryCountdown <= 0 && prominence >= minProminence) {
                    emit = Detection(
                        sampleIndex = sampleIndex - 1,
                        amplitude = lastSample,
                        timestampNs = timestampNs,
                        prominence = prominence
                    )
                    refractoryCountdown = refractoryN
                    minSinceLastPeak = filtered
                }
            }
            lastDeriv = deriv
            emit
        } else {
            lastDeriv = 0.0
            null
        }
        lastSample = filtered
        sampleIndex++
        return result
    }
}
