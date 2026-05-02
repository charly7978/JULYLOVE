package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatRhythmMarker
import com.forensicppg.monitor.domain.RhythmPatternHint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** HRV-lite y patrón de ritmo usando únicamente RR confirmados desde latidos válidos. */
class RhythmAnalyzer {

    private val rrIntervalsMs = ArrayDeque<Double>(384)
    private val irregularMarksNs = ArrayDeque<Long>(64)

    fun reset() {
        rrIntervalsMs.clear(); irregularMarksNs.clear()
    }

    fun registerRr(rrMs: Double?, timestampNs: Long, rhythm: BeatRhythmMarker) {
        if (rrMs != null && rrMs.isFinite() && rrMs in 260.0..2200.0) {
            rrIntervalsMs.addLast(rrMs)
            while (rrIntervalsMs.size > 256) rrIntervalsMs.removeFirst()
        }
        if (rhythm != BeatRhythmMarker.NORMAL && rhythm != BeatRhythmMarker.INITIAL_NOT_ENOUGH_CONTEXT) {
            irregularMarksNs.addLast(timestampNs)
            while (irregularMarksNs.size > 32) irregularMarksNs.removeFirst()
        }
    }

    fun tachogramTail(max: Int = 42): List<Double> {
        if (rrIntervalsMs.isEmpty()) return emptyList()
        return rrIntervalsMs.toList().takeLast(max.coerceAtMost(rrIntervalsMs.size))
    }

    fun rrIntervalCount(): Int = rrIntervalsMs.size

    fun medianRecentRr(): Double? {
        val v = tachogramTail(12).ifEmpty { return null }.sorted()
        return v[v.size / 2]
    }

    fun irregularityTimestamps(): List<Long> = irregularMarksNs.toList()

    fun summarize(): RhythmSummary {
        val arr = rrIntervalsMs.toList()
        val n = arr.size
        if (n == 0) {
            return RhythmSummary(null, null, null, null, null, null, 0,
                RhythmPatternHint.INSUFFICIENT_DATA)
        }
        val mean = arr.average()
        val variance = arr.sumOf { (it - mean) * (it - mean) } / max(1, n - 1).toDouble()
        val sdnn = sqrt(max(0.0, variance))
        var summedSqDiff = 0.0
        var pAbove50 = 0
        val diffs = mutableListOf<Double>()
        for (i in 1 until arr.size) {
            val d = arr[i] - arr[i - 1]
            diffs += d
            summedSqDiff += d * d
            if (abs(d) > 50.0) pAbove50++
        }
        val rmssd = if (diffs.isNotEmpty()) sqrt(summedSqDiff / diffs.size) else 0.0
        val pnn50 = if (diffs.isNotEmpty()) 100.0 * pAbove50.toDouble() / diffs.size else 0.0
        val cv = if (mean > 1e-3) sdnn / mean else null
        val irrIx = irregularityCoefficient(diffs)
        val pattern = when {
            n < 4 -> RhythmPatternHint.INSUFFICIENT_DATA
            irregularMarksNs.size >= 5 && irrIx >= 0.22 -> RhythmPatternHint.SUSPECT_ARRHYTHMIA
            irrIx >= 0.12 -> RhythmPatternHint.IRREGULAR
            else -> RhythmPatternHint.REGULAR
        }
        return RhythmSummary(
            meanMs = mean,
            sdnn = sdnn,
            rmssd = rmssd,
            pnn50 = pnn50,
            coefficientVar = cv,
            irregularityIx = irrIx,
            ectopicSuspects = irregularMarksNs.size.coerceAtMost(64),
            pattern = pattern
        )
    }

    private fun irregularityCoefficient(deltas: List<Double>): Double {
        if (deltas.size < 2) return 0.0
        val m = deltas.map(::abs).average()
        if (m < 1e-3) return 0.0
        val v = deltas.map { abs(abs(it) - m) }.average()
        return (v / m).coerceIn(0.0, 3.5)
    }

    data class RhythmSummary(
        val meanMs: Double?,
        val sdnn: Double?,
        val rmssd: Double?,
        val pnn50: Double?,
        val coefficientVar: Double?,
        val irregularityIx: Double?,
        val ectopicSuspects: Int,
        val pattern: RhythmPatternHint
    )
}
