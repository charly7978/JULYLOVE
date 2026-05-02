package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import kotlin.math.abs

/**
 * Clasifica cada latido detectado en NORMAL / SUSPECT_PREMATURE /
 * SUSPECT_PAUSE / IRREGULAR a partir de su RR respecto a la mediana local de
 * los últimos RR válidos. Nunca inventa un latido: se limita a etiquetar los
 * reales.
 */
class BeatClassifier(
    private val rrHistoryLimit: Int = 16,
    private val prematureFactor: Double = 0.80,
    private val pauseFactor: Double = 1.30,
    private val irregularityFactor: Double = 0.20
) {
    private val history = ArrayDeque<Double>()

    fun reset() { history.clear() }

    fun classify(candidate: BeatEvent, sqi: Double): BeatEvent {
        if (sqi < 0.35) {
            return candidate.copy(
                type = BeatType.INVALID_SIGNAL,
                reason = "señal_insuficiente",
                quality = sqi
            )
        }
        val rr = candidate.rrMs
        if (rr == null) {
            return candidate.copy(type = BeatType.NORMAL, reason = "primer_rr", quality = sqi)
        }
        if (rr < 250.0 || rr > 2000.0) {
            return candidate.copy(type = BeatType.INVALID_SIGNAL, reason = "rr_fuera_rango", quality = sqi)
        }
        val median = medianRr()
        if (median == null) {
            history.addLast(rr); trim()
            return candidate.copy(type = BeatType.NORMAL, reason = "sin_historico", quality = sqi)
        }
        val deviation = abs(rr - median) / median
        val type = when {
            rr < median * prematureFactor -> BeatType.SUSPECT_PREMATURE
            rr > median * pauseFactor -> BeatType.SUSPECT_PAUSE
            deviation > irregularityFactor -> BeatType.IRREGULAR
            else -> BeatType.NORMAL
        }
        history.addLast(rr); trim()
        val reason = when (type) {
            BeatType.SUSPECT_PREMATURE -> "rr=${rr.toInt()}ms < 0.80 × mediana(${median.toInt()}ms)"
            BeatType.SUSPECT_PAUSE -> "rr=${rr.toInt()}ms > 1.30 × mediana(${median.toInt()}ms)"
            BeatType.IRREGULAR -> "|rr-mediana|/mediana=${"%.2f".format(deviation)}"
            BeatType.NORMAL -> "rr=${rr.toInt()}ms"
            else -> ""
        }
        return candidate.copy(type = type, reason = reason, quality = sqi)
    }

    private fun trim() { while (history.size > rrHistoryLimit) history.removeFirst() }

    fun medianRr(): Double? {
        if (history.size < 3) return null
        val sorted = history.sorted()
        val n = sorted.size
        return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 else sorted[n / 2]
    }
}
