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
    private val maxWindowSize = 60 // 1 minute window approx

    data class DetailedRhythm(
        val bpm: Int,
        val status: RhythmStatus,
        val rmssd: Double,
        val pnn50: Double,
        val cv: Double
    )

    fun addIntervalDetailed(ppiMs: Long): DetailedRhythm {
        if (ppiMs < 300 || ppiMs > 2000) {
            return DetailedRhythm(0, RhythmStatus.ARTIFACT, 0.0, 0.0, 0.0)
        }
        
        ppiWindow.add(ppiMs)
        if (ppiWindow.size > maxWindowSize) {
            ppiWindow.removeAt(0)
        }

        val bpm = (60000.0 / ppiMs).toInt()

        if (ppiWindow.size < 10) {
            return DetailedRhythm(bpm, RhythmStatus.CALIBRATING, 0.0, 0.0, 0.0)
        }

        val mean = ppiWindow.average()
        val stdDev = sqrt(ppiWindow.map { (it - mean) * (it - mean) }.average())
        val cv = (stdDev / mean) * 100 

        var sumDiffSq = 0.0
        var countNN50 = 0
        for (i in 0 until ppiWindow.size - 1) {
            val diff = (ppiWindow[i+1] - ppiWindow[i]).toDouble()
            sumDiffSq += diff * diff
            if (Math.abs(diff) > 50.0) {
                countNN50++
            }
        }
        val rmssd = sqrt(sumDiffSq / (ppiWindow.size - 1))
        val pNN50 = (countNN50.toDouble() / (ppiWindow.size - 1)) * 100.0

        val status = when {
            cv > 15.0 || rmssd > 120.0 || pNN50 > 30.0 -> RhythmStatus.SUSPECTED_ARRHYTHMIA
            cv > 8.0 || rmssd > 60.0 -> RhythmStatus.IRREGULAR
            else -> RhythmStatus.REGULAR
        }

        return DetailedRhythm(bpm, status, rmssd, pNN50, cv)
    }
    
    fun getPNN50(): Double {
        if (ppiWindow.size < 2) return 0.0
        var countNN50 = 0
        for (i in 0 until ppiWindow.size - 1) {
            if (Math.abs(ppiWindow[i+1] - ppiWindow[i]) > 50) {
                countNN50++
            }
        }
        return (countNN50.toDouble() / (ppiWindow.size - 1)) * 100.0
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
