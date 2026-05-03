package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatRhythmMarker
import com.forensicppg.monitor.domain.ConfirmedBeat
import com.forensicppg.monitor.domain.PpgValidityState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/**
 * Picos sólo con evidencia PPG; RR inicial 300–1500 ms; mínimo 5 latidos para BPM (en pipeline).
 */
class PpgPeakDetector(
    sampleRateHz: Double,
    private val rhythm: RhythmAnalyzer
) {
    private val sr = sampleRateHz.coerceIn(17.5, 90.0)

    data class DetectorStats(var confirmedSession: Int = 0, var rejectedSession: Int = 0, var rejectDigest: String = "")

    val stats = DetectorStats()

    private val buf = ArrayDeque<Pk>(360)
    private var lastEmittedNs: Long? = null
    private var prevFiltered = Double.NaN

    private val minRRms = 300.0
    private val maxRRms = 1500.0

    private val minPeakGapNs get() = (minRRms * 1e6).toLong()

    private data class Pk(val tsNs: Long, val y: Double, val dy: Double)

    fun reset() {
        buf.clear()
        lastEmittedNs = null
        prevFiltered = Double.NaN
        stats.confirmedSession = 0
        stats.rejectedSession = 0
        stats.rejectDigest = ""
    }

    private fun refuse(msg: String) {
        stats.rejectedSession++
        stats.rejectDigest = msg.takeLast(280)
    }

    fun onSample(
        timestampNs: Long,
        filteredWave: Double,
        derivativeWave: Double,
        sqiComposite: Double,
        validityState: PpgValidityState,
        opticalMotionSmoothed: Double,
        maskCoverage: Double,
        stabilizationActive: Boolean
    ): ConfirmedBeat? {
        if (stabilizationActive) return null
        if (maskCoverage < 0.70) return null

        buf.addLast(Pk(timestampNs, filteredWave, derivativeWave))
        while (buf.size > 360) buf.removeFirst()

        if (!prevFiltered.isFinite()) {
            prevFiltered = filteredWave
            return null
        }
        val spike = abs(filteredWave - prevFiltered)
        prevFiltered = filteredWave

        val validityOk = validityState.ordinal >= PpgValidityState.PPG_CANDIDATE.ordinal
        if (
            validityState.ordinal <= PpgValidityState.QUIET_NO_PULSE.ordinal ||
            !validityOk
        ) {
            return null
        }
        if (sqiComposite < 0.42) return null
        if (opticalMotionSmoothed > 0.18 && spike > 0.14) return null
        val lastTs = lastEmittedNs
        if (buf.size < 72) return null

        val neigh = max(6, ceil(0.15 * sr).toInt())
        val list = buf.toMutableList()

        for (i in (list.size - neigh - 3) downTo neigh + 1) {
            if (i + neigh >= list.size - 4) continue
            val cen = list[i]
            if (list.lastIndex - i < neigh + 3) continue
            if (!(cen.y >= list[i - 1].y && cen.y > list[i + 1].y)) continue
            if (cen.dy <= 0.0) continue

            val leftMin = minSliceCorrect(list, i - neigh, i - 2)
            val rightMin = minSliceCorrect(list, i + 2, i + neigh)

            val base = kotlin.math.min(leftMin, rightMin)
            val prom = cen.y - base
            val mad = residualMadAround(list, i)
            val gateProm = max(2.5 * mad, abs(cen.y) * 0.072)

            if (prom <= gateProm || prom < abs(cen.y) * 0.058) continue

            if (lastTs != null && cen.tsNs - lastTs < minPeakGapNs) continue

            val rrMs: Double? =
                if (lastTs == null) null
                else (cen.tsNs - lastTs) / 1_000_000.0

            if (rrMs != null && (rrMs < minRRms || rrMs > maxRRms)) {
                refuse("rr_fuera=${"%.0f".format(rrMs)}")
                continue
            }

            val median = rhythm.medianRecentRr()
            val marker = classify(rrMs, median)

            lastEmittedNs = cen.tsNs
            stats.confirmedSession++

            val confShape = ((prom / (gateProm.coerceAtLeast(1e-5))).coerceIn(0.0, 1.35)).coerceAtMost(1.0)
            val conf = ((confShape * 0.42) + (sqiComposite.coerceAtMost(1.0) * 0.58)).coerceIn(0.0, 1.0)

            return ConfirmedBeat(
                timestampNs = cen.tsNs,
                amplitude = cen.y,
                rrIntervalMs = rrMs,
                confidence = conf,
                sourceChannel = "GREEN",
                sqiSegment = sqiComposite,
                rhythmMarker = marker
            )
        }
        return null
    }

    private fun minSliceCorrect(list: List<Pk>, a: Int, b: Int): Double {
        val lo = kotlin.math.min(a, b).coerceIn(0, list.lastIndex)
        val hi = kotlin.math.max(a, b).coerceIn(0, list.lastIndex)
        var m = Double.POSITIVE_INFINITY
        for (j in lo..hi) m = kotlin.math.min(m, list[j].y)
        return m
    }

    private fun residualMadAround(list: MutableList<Pk>, centerIdx: Int): Double {
        val start = max(0, centerIdx - 52)
        val endExclusive = min(list.size, centerIdx + 53)
        if (endExclusive - start < 7) return 1e-3
        var mu = 0.0
        for (idx in start until endExclusive) mu += list[idx].y
        mu /= (endExclusive - start)
        val xs = MutableList<Double>(endExclusive - start) { ji ->
            abs(list[start + ji].y - mu)
        }
        xs.sort()
        return xs[xs.size / 2].coerceAtLeast(1e-4)
    }

    private fun classify(rrMs: Double?, medianRecent: Double?): BeatRhythmMarker {
        if (rrMs == null) return BeatRhythmMarker.INITIAL_NOT_ENOUGH_CONTEXT
        val med = medianRecent ?: return BeatRhythmMarker.NORMAL
        when {
            rrMs < max(minRRms, med * 0.60) -> return BeatRhythmMarker.ECTOPIC_OR_PREMATURE
            rrMs > min(maxRRms, med * 1.78) -> return BeatRhythmMarker.PAUSE_SUSPECT
            abs(rrMs - med) / med > 0.26 -> return BeatRhythmMarker.IRREGULAR
            else -> return BeatRhythmMarker.NORMAL
        }
    }
}
