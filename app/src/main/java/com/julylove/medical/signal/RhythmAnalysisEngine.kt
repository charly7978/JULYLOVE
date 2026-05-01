package com.julylove.medical.signal

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Análisis de variabilidad (HRV) sobre intervalos RR derivados del PPG.
 *
 * Referencias (soporte algorítmico y contexto clínico):
 * - Lee et al., Heart Rhythm 2012 (PPG smartphone / detección de FA en estudios pioneros).
 * - Tison et al. y revisiones recientes (2023–2024) sobre deep learning y filtrado de movimiento en ritmos irregulares.
 * - Task Force ESC (1996) para definición de RMSSD/pNN50 en dominio temporal.
 *
 * Criterios de “arritmia sospechosa” aquí son heurísticos (no diagnóstico): combinan RMSSD, CV RR,
 * entropía de Shannon sobre RR discretizada y muestral (SampEn) como indicadores de irregularidad.
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
        val cv: Double,
        val shannonEntropyBits: Double,
        val sampleEntropy: Double?
    )

    private data class RrBeat(val intervalMs: Long, val beatTimeNs: Long)

    /** Historial RR con marca temporal monótona (ns) para ventanas temporales de BPM. */
    private val rrSeries = mutableListOf<RrBeat>()
    private val maxSeriesSize = 120

    /** Ventana de trabajo HRV (intervalos consecutivos válidos). */
    private val rrBuffer = mutableListOf<Long>()
    private val maxBufferSize = 100

    fun addIntervalDetailed(ppiMs: Long, beatTimestampNs: Long): RhythmResult {
        if (ppiMs in 300..2000) {
            if (rrBuffer.isNotEmpty()) {
                val lastRr = rrBuffer.last()
                val change = abs(ppiMs - lastRr).toDouble() / lastRr
                if (change > 0.3) {
                    // Posible ectópico o artefacto: no se excluye del todo; ver Task Force (edición de intervalos).
                }
            }
            rrBuffer.add(ppiMs)
            if (rrBuffer.size > maxBufferSize) rrBuffer.removeAt(0)

            rrSeries.add(RrBeat(ppiMs, beatTimestampNs))
            while (rrSeries.size > maxSeriesSize) rrSeries.removeAt(0)
        }

        if (rrBuffer.size < 10) {
            val instantBpm = if (ppiMs > 0) (60000 / ppiMs).toInt() else 0
            return RhythmResult(
                RhythmStatus.CALIBRATING, instantBpm, 0.0, 0.0, 0.0, 0.0, null
            )
        }

        val doubleBuffer = rrBuffer.map { it.toDouble() }
        val meanRr = doubleBuffer.average()

        // BPM en ventana 3–5 s hacia atrás desde el último latido (lit.: promedios cortos estabilizan visualización).
        val bpmWindowed = computeBpmFromRecentIntervals()
        val bpm = bpmWindowed ?: (60000.0 / meanRr).toInt().coerceIn(35, 200)

        val variance = doubleBuffer.map { (it - meanRr) * (it - meanRr) }.average()
        val sdnn = sqrt(variance)

        var sumDiffSq = 0.0
        var countNN50 = 0
        for (i in 1 until rrBuffer.size) {
            val diff = abs(rrBuffer[i] - rrBuffer[i - 1]).toDouble()
            sumDiffSq += diff * diff
            if (diff > 50.0) countNN50++
        }
        val rmssd = sqrt(sumDiffSq / (rrBuffer.size - 1))
        val pnn50 = (countNN50.toDouble() / (rrBuffer.size - 1)) * 100.0
        val cv = (sdnn / meanRr) * 100.0

        val hShannon = shannonEntropyOfRR(rrBuffer)
        val sampEn = sampleEntropy(rrBuffer, m = 2, rFraction = 0.2)

        val status = when {
            cv > 12.0 || rmssd > 120.0 ||
                hShannon > 3.8 || (sampEn != null && sampEn > 1.35) -> RhythmStatus.SUSPECTED_ARRHYTHMIA
            cv > 6.0 || rmssd > 60.0 || hShannon > 3.0 -> RhythmStatus.IRREGULAR
            else -> RhythmStatus.REGULAR
        }

        return RhythmResult(status, bpm, rmssd, pnn50, cv, hShannon, sampEn)
    }

    /** BPM = 60 / media RR usando solo intervalos más recientes cuya suma cubre ~3–5 s. */
    private fun computeBpmFromRecentIntervals(): Int? {
        if (rrSeries.size < 3) return null
        var sumMs = 0L
        var count = 0
        for (i in rrSeries.indices.reversed()) {
            sumMs += rrSeries[i].intervalMs
            count++
            if (sumMs in 3_000L..5_000L && count >= 3) {
                val mean = sumMs.toDouble() / count
                if (mean in 320.0..2000.0) return (60000.0 / mean).toInt().coerceIn(35, 200)
            }
            if (sumMs > 5_500L || count > 18) break
        }
        return null
    }

    private fun shannonEntropyOfRR(rrMs: List<Long>): Double {
        if (rrMs.size < 5) return 0.0
        val binWidth = 20L
        val bins = HashMap<Long, Int>()
        for (v in rrMs) {
            val k = (v / binWidth) * binWidth
            bins[k] = (bins[k] ?: 0) + 1
        }
        val n = rrMs.size.toDouble()
        var h = 0.0
        for (c in bins.values) {
            val p = c / n
            if (p > 0) h -= p * (ln(p) / ln(2.0))
        }
        return h
    }

    /**
     * Entropía muestral (Richman & Moorman, 2000) sobre RR (ms), distancia Chebyshev, sin autocoincidencias.
     */
    private fun sampleEntropy(data: List<Long>, m: Int, rFraction: Double): Double? {
        val n = data.size
        if (n < m + 25) return null
        val x = DoubleArray(n) { data[it].toDouble() }
        val sd = std(x) ?: return null
        val r = rFraction * sd
        if (r <= 1e-6) return null

        fun match(i: Int, j: Int, len: Int): Boolean {
            for (k in 0 until len) {
                if (abs(x[i + k] - x[j + k]) > r) return false
            }
            return true
        }

        fun countTemplates(len: Int): Int {
            var c = 0
            for (i in 0 until n - len) {
                for (j in i + 1 until n - len) {
                    if (match(i, j, len)) c++
                }
            }
            return c
        }

        val b = countTemplates(m)
        val a = countTemplates(m + 1)
        if (b <= 0 || a <= 0) return null
        return -ln(a.toDouble() / b.toDouble())
    }

    private fun std(x: DoubleArray): Double? {
        if (x.isEmpty()) return null
        val mean = x.average()
        var s = 0.0
        for (v in x) {
            val d = v - mean
            s += d * d
        }
        return sqrt(s / x.size)
    }

    fun reset() {
        rrBuffer.clear()
        rrSeries.clear()
    }
}
