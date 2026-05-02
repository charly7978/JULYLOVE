package com.julylove.medical.signal

/**
 * PpgPeakDetector - Centralizador de detección de picos.
 * Orquestra detectores específicos (Elgendi, Derivativo) y aplica lógica de rechazo.
 */
class PpgPeakDetector(val sampleRate: Float) {
    
    private val elgendi = PeakDetectorElgendi(sampleRate)
    
    fun process(filteredValue: Float, timestampNs: Long): PeakDetectorElgendi.ElgendiPeak? {
        return elgendi.process(filteredValue, timestampNs)
    }
    
    fun reset() {
        elgendi.reset()
    }
}
