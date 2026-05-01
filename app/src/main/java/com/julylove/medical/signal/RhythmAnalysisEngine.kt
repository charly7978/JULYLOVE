package com.julylove.medical.signal

import kotlin.math.sqrt

/**
 * Technical classification of heart rhythm based on PPI (Pulse-to-Pulse Intervals).
 */
class RhythmAnalysisEngine {
    
    enum class RhythmStatus {
        CALIBRATING,
        REGULAR,
        IRREGULAR,
        SUSPECTED_ARRHYTHMIA,
        ARTIFACT
    }

    private val ppiWindow = mutableListOf<Long>()
    private val maxWindowSize = 30 // Approx 30 seconds of data at 60 BPM

    fun addInterval(ppiMs: Long): RhythmStatus {
        if (ppiMs < 300 || ppiMs > 2000) return RhythmStatus.ARTIFACT // Non-physiological
        
        ppiWindow.add(ppiMs)
        if (ppiWindow.size > maxWindowSize) {
            ppiWindow.removeAt(0)
        }

        if (ppiWindow.size < 5) return RhythmStatus.CALIBRATING

        return analyzeRhythm()
    }

    private fun analyzeRhythm(): RhythmStatus {
        val mean = ppiWindow.average()
        val stdDev = sqrt(ppiWindow.map { (it - mean) * (it - mean) }.average())
        val cv = (stdDev / mean) * 100 // Coefficient of Variation

        // RMSSD (Root Mean Square of Successive Differences)
        var sumDiffSq = 0.0
        for (i in 0 until ppiWindow.size - 1) {
            val diff = (ppiWindow[i+1] - ppiWindow[i]).toDouble()
            sumDiffSq += diff * diff
        }
        val rmssd = sqrt(sumDiffSq / (ppiWindow.size - 1))

        return when {
            cv > 15.0 || rmssd > 100.0 -> RhythmStatus.SUSPECTED_ARRHYTHMIA
            cv > 8.0 -> RhythmStatus.IRREGULAR
            else -> RhythmStatus.REGULAR
        }
    }
    
    fun getRMSSD(): Double {
        if (ppiWindow.size < 2) return 0.0
        var sumDiffSq = 0.0
        for (i in 0 until ppiWindow.size - 1) {
            val diff = (ppiWindow[i+1] - ppiWindow[i]).toDouble()
            sumDiffSq += diff * diff
        }
        return sqrt(sumDiffSq / (ppiWindow.size - 1))
    }
}
