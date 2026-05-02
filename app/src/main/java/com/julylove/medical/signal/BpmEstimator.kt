package com.julylove.medical.signal

/**
 * BpmEstimator - Wrapper para HeartRateFusion.
 * Calcula BPM estable y confianza.
 */
class BpmEstimator {
    
    private val fusion = HeartRateFusion()
    
    fun estimate(peak: PeakDetectorElgendi.ElgendiPeak?, sqi: Float, timestampNs: Long): HeartRateFusion.FusedBeat? {
        return fusion.fuseDetections(peak, null, sqi, timestampNs)
    }
    
    fun getAverageBpm(): Int = fusion.getAverageBpm()?.toInt() ?: 0
    
    fun reset() {
        fusion.reset()
    }
}
