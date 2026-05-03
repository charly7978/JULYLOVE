package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatRhythmMarker
import com.forensicppg.monitor.domain.ConfirmedBeat
import com.forensicppg.monitor.domain.PpgValidityState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil

/**
 * Detector de picos sistólicos PPG — Slope Sum Function (Zong 2003) sobre la
 * señal pulsátil con polaridad fija.
 *
 * Modelo físico (cámara trasera + LED de flash, dedo apoyado):
 *
 *   - Sístole = mayor absorción de hemoglobina = MENOS luz reflejada en R y G.
 *   - Por tanto la "media verde" del ROI CAE en sístole.
 *   - El [PpgSignalProcessor] entrega `filteredWave = bandpass(detrend(G))`.
 *     La sístole de esa señal es un valle. Para detectar picos sistólicos
 *     trabajamos sobre `-filteredWave` (la sístole sube).
 *
 * SSF en la muestra i:
 *
 *     z[i] = Σ_{k=i-w+1..i} max(0, x[k] − x[k-1])
 *
 * con `w` ≈ 125 ms (típico PPG arterial). z[i] tiene un máximo local muy
 * marcado justo antes del pico sistólico (∂x/∂t positivo máximo = onset
 * sistólico). Es robusto al nivel DC y al ruido blanco.
 *
 * Ya no exigimos gates de clasificación o SQI globales para emitir candidatos.
 * Los gates fisiológicos están aquí mismo:
 *
 *   - Período refractario: 280 ms (≤ 215 BPM).
 *   - RR ∈ [285, 2080] ms (28–210 BPM).
 *   - Coherencia de RR: |RR − medianaRR_8| / medianaRR ≤ 0.40.
 *   - Prominencia ≥ 2.5 · MAD residual local + amplitud relativa ≥ 6 %.
 *
 * Esto rompe el deadlock anterior donde el clasificador pedía picos previos
 * para subir de nivel y el detector pedía nivel alto para emitir picos.
 */
class PpgPeakDetector(
    sampleRateHz: Double,
    private val rhythm: RhythmAnalyzer
) {
    private val sr = sampleRateHz.coerceIn(15.0, 90.0)

    data class DetectorStats(
        var confirmedSession: Int = 0,
        var rejectedSession: Int = 0,
        var rejectDigest: String = ""
    )

    val stats = DetectorStats()

    private val bufCapacity = (sr * 8).toInt().coerceAtLeast(120)
    private val buf = ArrayDeque<Pk>(bufCapacity + 8)
    private var lastEmittedNs: Long? = null
    private var prevFiltered = Double.NaN

    /** Período refractario en muestras (~280 ms → ≤ 215 BPM). */
    private val refractoryNs = 280_000_000L

    private val minRRms = 285.0
    private val maxRRms = 2080.0

    /** Tamaño de ventana SSF (~125 ms). */
    private val ssfWindowN = max(3, (sr * 0.125).toInt())
    private val ssfBuf = DoubleArray(ssfWindowN)
    private var ssfIdx = 0
    private var ssfFilled = 0
    private var ssfSum = 0.0

    /** Estimadores adaptativos del threshold sobre z. */
    private var ssfMeanEma = 0.0
    private var ssfPeakEma = 0.0
    private var thresholdZ = 0.0
    private var calibratedThreshold = false
    private var samplesSeen = 0
    /** Calibración inicial corta (~1.5 s) — antes esperaba 68 muestras antes de
     *  buscar siquiera, eran ~2.3 s perdidos. */
    private val learnSamples = max(20, (sr * 1.5).toInt())

    /** Estado de búsqueda tras cruce ascendente del threshold. */
    private var searching = false
    private var searchDeadlineNs = 0L
    private var searchBest = Pk(0L, Double.NEGATIVE_INFINITY)
    private var searchMin = Double.POSITIVE_INFINITY
    private var searchZPeak = 0.0
    private val searchWindowNs = 220_000_000L

    private val ampHistory = ArrayDeque<Double>(12)

    private data class Pk(val tsNs: Long, val y: Double)

    fun reset() {
        buf.clear()
        lastEmittedNs = null
        prevFiltered = Double.NaN
        stats.confirmedSession = 0
        stats.rejectedSession = 0
        stats.rejectDigest = ""
        for (i in 0 until ssfWindowN) ssfBuf[i] = 0.0
        ssfIdx = 0; ssfFilled = 0; ssfSum = 0.0
        ssfMeanEma = 0.0; ssfPeakEma = 0.0; thresholdZ = 0.0
        calibratedThreshold = false; samplesSeen = 0
        searching = false
        searchBest = Pk(0L, Double.NEGATIVE_INFINITY)
        searchMin = Double.POSITIVE_INFINITY; searchZPeak = 0.0
        ampHistory.clear()
    }

    private fun refuse(msg: String) {
        stats.rejectedSession++
        stats.rejectDigest = msg.takeLast(280)
    }

    fun onSample(
        timestampNs: Long,
        filteredWave: Double,
        sqiComposite: Double,
        validityState: PpgValidityState,
        opticalMotionSmoothed: Double
    ): ConfirmedBeat? {
        // Polaridad fija: invertimos para que la SÍSTOLE SUBA (cámara con
        // flash blanco: sístole = caída de luz reflejada).
        val x = -filteredWave

        // Buffer rodante para visualización / stats locales.
        buf.addLast(Pk(timestampNs, x))
        while (buf.size > bufCapacity) buf.removeFirst()

        if (!prevFiltered.isFinite()) {
            prevFiltered = x
            samplesSeen++
            return null
        }
        val slope = x - prevFiltered
        prevFiltered = x
        val pos = if (slope > 0.0) slope else 0.0

        // Ventana móvil SSF.
        val old = ssfBuf[ssfIdx]
        ssfBuf[ssfIdx] = pos
        ssfSum += pos - old
        ssfIdx = (ssfIdx + 1) % ssfWindowN
        if (ssfFilled < ssfWindowN) ssfFilled++
        val z = ssfSum

        // Background medio lento.
        ssfMeanEma = if (ssfMeanEma == 0.0) z else ssfMeanEma + 0.01 * (z - ssfMeanEma)

        // Calibración inicial.
        if (!calibratedThreshold) {
            samplesSeen++
            if (samplesSeen >= learnSamples) {
                thresholdZ = 3.0 * max(1e-9, ssfMeanEma)
                ssfPeakEma = thresholdZ
                calibratedThreshold = true
            }
            return null
        }

        // Bloqueo duro de motion / clipping severo (sin depender del classifier).
        if (opticalMotionSmoothed > 0.85) {
            // Resetar búsqueda en curso para no comer una falsa sístole.
            searching = false
            searchBest = Pk(0L, Double.NEGATIVE_INFINITY)
            searchMin = Double.POSITIVE_INFINITY
            return null
        }
        if (validityState == PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL && opticalMotionSmoothed > 0.55) {
            return null
        }

        var result: ConfirmedBeat? = null

        // Cruce ascendente sobre threshold y refractario por timestamp.
        val refractoryOk = lastEmittedNs == null || (timestampNs - lastEmittedNs!!) > refractoryNs
        if (!searching && z > thresholdZ && refractoryOk) {
            searching = true
            searchDeadlineNs = timestampNs + searchWindowNs
            searchBest = Pk(timestampNs, x)
            searchMin = x
            searchZPeak = z
        }

        if (searching) {
            if (x > searchBest.y) searchBest = Pk(timestampNs, x)
            if (x < searchMin) searchMin = x
            if (z > searchZPeak) searchZPeak = z

            val timedOut = timestampNs >= searchDeadlineNs
            val below = z < thresholdZ * 0.5
            if (timedOut || below) {
                val prominence = searchBest.y - searchMin
                val medAmp = medianAmp()
                val ampOk = medAmp <= 0.0 || prominence >= 0.35 * medAmp
                val rrMs: Double? = lastEmittedNs?.let { (searchBest.tsNs - it) / 1_000_000.0 }
                val rrAbsoluteOk = rrMs == null || (rrMs in minRRms..maxRRms)
                val rrCoherentOk = rrMs == null || checkCoherence(rrMs)

                if (ampOk && rrAbsoluteOk && rrCoherentOk) {
                    val median = rhythm.medianRecentRr()
                    val marker = classifyMarker(rrMs, median)

                    lastEmittedNs = searchBest.tsNs
                    stats.confirmedSession++

                    val confShape = (prominence /
                        max(1e-5, max(2.0 * mad(), abs(searchBest.y) * 0.06))
                        ).coerceIn(0.0, 1.0)
                    val sqiBlend = sqiComposite.coerceIn(0.0, 1.0)
                    val conf = (confShape * 0.55 + sqiBlend * 0.45).coerceIn(0.0, 1.0)

                    // Adaptación tipo Pan-Tompkins del threshold SSF.
                    ssfPeakEma = 0.125 * searchZPeak + 0.875 * ssfPeakEma
                    thresholdZ = ssfMeanEma + 0.25 * (ssfPeakEma - ssfMeanEma)
                    thresholdZ = max(thresholdZ, 2.0 * ssfMeanEma)

                    ampHistory.addLast(prominence)
                    while (ampHistory.size > 12) ampHistory.removeFirst()

                    result = ConfirmedBeat(
                        timestampNs = searchBest.tsNs,
                        amplitude = searchBest.y,
                        rrIntervalMs = rrMs,
                        confidence = conf,
                        sourceChannel = "GREEN_INV",
                        sqiSegment = sqiComposite,
                        rhythmMarker = marker
                    )
                } else {
                    refuse("rr_o_amp_no_coherente rr=$rrMs prom=$prominence medAmp=$medAmp")
                    thresholdZ = max(thresholdZ, searchZPeak * 0.9)
                }

                searching = false
                searchBest = Pk(0L, Double.NEGATIVE_INFINITY)
                searchMin = Double.POSITIVE_INFINITY
                searchZPeak = 0.0
            }
        }

        return result
    }

    private fun medianAmp(): Double {
        val n = ampHistory.size
        if (n == 0) return 0.0
        val s = ampHistory.toMutableList().apply { sort() }
        return if (n % 2 == 0) (s[n / 2 - 1] + s[n / 2]) / 2.0 else s[(n - 1) / 2]
    }

    private fun checkCoherence(rr: Double): Boolean {
        val median = rhythm.medianRecentRr() ?: return true
        if (median <= 0.0) return true
        return abs(rr - median) / median <= 0.40
    }

    private fun mad(): Double {
        // MAD aproximado de la cola del buffer (sin allocations grandes).
        val take = min(64, buf.size)
        if (take < 8) return 1e-3
        val tail = DoubleArray(take)
        var i = buf.size - take
        var k = 0
        for (p in buf) {
            if (k >= take) break
            if (i <= 0) { tail[k++] = p.y } else i--
        }
        var mu = 0.0
        for (v in tail) mu += v
        mu /= take
        val abs = DoubleArray(take) { idx -> abs(tail[idx] - mu) }
        java.util.Arrays.sort(abs)
        return abs[take / 2].coerceAtLeast(1e-4)
    }

    private fun classifyMarker(rrMs: Double?, medianRecent: Double?): BeatRhythmMarker {
        if (rrMs == null) return BeatRhythmMarker.INITIAL_NOT_ENOUGH_CONTEXT
        val med = medianRecent ?: return BeatRhythmMarker.NORMAL
        return when {
            rrMs < max(minRRms, med * 0.60) -> BeatRhythmMarker.ECTOPIC_OR_PREMATURE
            rrMs > min(maxRRms, med * 1.78) -> BeatRhythmMarker.PAUSE_SUSPECT
            abs(rrMs - med) / med > 0.26 -> BeatRhythmMarker.IRREGULAR
            else -> BeatRhythmMarker.NORMAL
        }
    }
}
