package com.julylove.medical.signal

import kotlin.math.abs

/**
 * PpgPhysiologyClassifier: The "gatekeeper" of the clinical pipeline.
 * It analyzes optical signals to ensure they originate from a human finger
 * and not a static red object or noise.
 */
class PpgPhysiologyClassifier {

    private val signalBuffer = mutableListOf<Float>()
    private val windowSize = 150 // ~2.5 seconds at 60fps

    fun classify(
        filteredValue: Float,
        redMean: Float,
        greenMean: Float,
        blueMean: Float,
        isMoving: Boolean
    ): PpgValidityState {
        
        // 1. Basic Hardware/Optical Checks
        if (redMean > 252f) return PpgValidityState.SATURATED
        if (redMean < 30f) return PpgValidityState.LOW_PERFUSION
        
        // 2. Chromatic Check: Human tissue with flash should be heavily dominated by Red
        // and have a specific relationship with Green.
        // Physiological tissue reflection: Red >> Green > Blue
        val isChromaticLikelyHuman = redMean > (greenMean * 1.5f) && greenMean >= (blueMean * 0.9f)
        if (!isChromaticLikelyHuman) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL

        // 3. Pulsatility Check: Analyze signal variance in the buffer
        signalBuffer.add(filteredValue)
        if (signalBuffer.size > windowSize) signalBuffer.removeAt(0)

        if (signalBuffer.size < windowSize) return PpgValidityState.SEARCHING_PPG

        // Calculate AC/DC Proxy
        val dc = redMean
        val ac = calculateRMS(signalBuffer)
        val perfusionIndex = (ac / dc) * 100

        // Physiological PI for PPG is usually 0.1% to 5.0%
        if (perfusionIndex < 0.05f) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL
        
        if (isMoving) return PpgValidityState.MOTION_ARTIFACT

        // 4. Rhythmicity Check (Simplified for now, can be expanded with FFT/Autocorrelation)
        val crossings = countZeroCrossings(signalBuffer)
        // At 60fps, 2.5s is 150 samples. 
        // 40bpm = 0.66Hz -> ~1.6 cycles in 2.5s -> ~3 zero crossings
        // 200bpm = 3.33Hz -> ~8.3 cycles in 2.5s -> ~16 zero crossings
        if (crossings !in 3..20) return PpgValidityState.NO_PPG_PHYSIOLOGICAL_SIGNAL

        return PpgValidityState.PPG_VALID
    }

    private fun calculateRMS(buffer: List<Float>): Float {
        val avg = buffer.average().toFloat()
        var sumSq = 0f
        for (v in buffer) {
            val diff = v - avg
            sumSq += diff * diff
        }
        return kotlin.math.sqrt(sumSq / buffer.size)
    }

    private fun countZeroCrossings(buffer: List<Float>): Int {
        var count = 0
        val avg = buffer.average().toFloat()
        for (i in 1 until buffer.size) {
            val prev = buffer[i - 1] - avg
            val curr = buffer[i] - avg
            if (prev * curr < 0) count++
        }
        return count
    }
}
