package com.julylove.medical.signal

/**
 * PeakDetectionEngine: Wrapper for PpgPeakDetector to match ViewModel naming.
 */
class PeakDetectionEngine(sampleRate: Float) {
    private val detector = PpgPeakDetector(sampleRate)

    fun process(value: Float, timestampNs: Long): Boolean {
        return detector.process(value, timestampNs) != null
    }

    fun reset() {
        detector.reset()
    }
}
