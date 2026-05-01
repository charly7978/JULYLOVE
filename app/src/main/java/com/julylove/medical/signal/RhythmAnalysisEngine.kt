package com.julylove.medical.signal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * RhythmAnalysisEngine: Clinical-grade rhythm analysis based on RR intervals (PPI).
 * Optimized for long-term stability and artifact rejection.
 */
class RhythmAnalysisEngine {

    enum class RhythmStatus {
        CALIBRATING,
        REGULAR,
        IRREGULAR,
        SUSPECTED_ARRHYTHMIA
    }

    data class RhythmResult(
        val status: RhythmStatus,
        val bpm: Int,
        val rmssd: Double,
        val pnn50: Double,
        val cv: Double
    )

    private val rrBuffer = mutableListOf<Long>()
    private val maxBufferSize = 100 // Longer window for stability (~1-2 minutes)

    fun addIntervalDetailed(ppiMs: Long): RhythmResult {
        // Physiological filter: 300ms (200 BPM) to 2000ms (30 BPM)
        if (ppiMs in 300..2000) {
            // Ectopic beat rejection (simple % change filter)
            if (rrBuffer.isNotEmpty()) {
                val lastRr = rrBuffer.last()
                val change = abs(ppiMs - lastRr).toDouble() / lastRr
                if (change > 0.3) {
                    // Possible artifact or ectopic beat, don't use for HRV statistics but use for BPM?
                    // For now, we allow it to the buffer but could flag it.
                }
            }
            rrBuffer.add(ppiMs)
            if (rrBuffer.size > maxBufferSize) rrBuffer.removeAt(0)
        }

        if (rrBuffer.size < 10) {
            val instantBpm = if (ppiMs > 0) (60000 / ppiMs).toInt() else 0
            return RhythmResult(RhythmStatus.CALIBRATING, instantBpm, 0.0, 0.0, 0.0)
        }

        val doubleBuffer = rrBuffer.map { it.toDouble() }
        val meanRr = doubleBuffer.average()
        val bpm = (60000.0 / meanRr).toInt()
        
        // SDNN (Standard Deviation of NN intervals)
        val variance = doubleBuffer.map { (it - meanRr) * (it - meanRr) }.average()
        val sdnn = sqrt(variance)
        
        // RMSSD (Root Mean Square of Successive Differences)
        var sumDiffSq = 0.0
        var countNN50 = 0
        for (i in 1 until rrBuffer.size) {
            val diff = abs(rrBuffer[i] - rrBuffer[i - 1]).toDouble()
            sumDiffSq += diff * diff
            if (diff > 50.0) countNN50++
        }
        val rmssd = sqrt(sumDiffSq / (rrBuffer.size - 1))
        val pnn50 = (countNN50.toDouble() / (rrBuffer.size - 1)) * 100.0
        
        // CV (Coefficient of Variation) - robust indicator of arrhythmia
        val cv = (sdnn / meanRr) * 100.0

        // Clinical Decision Logic
        val status = when {
            cv > 12.0 || rmssd > 120.0 -> RhythmStatus.SUSPECTED_ARRHYTHMIA
            cv > 6.0 || rmssd > 60.0 -> RhythmStatus.IRREGULAR
            else -> RhythmStatus.REGULAR
        }

        return RhythmResult(status, bpm, rmssd, pnn50, cv)
    }

    fun reset() {
        rrBuffer.clear()
    }
}
