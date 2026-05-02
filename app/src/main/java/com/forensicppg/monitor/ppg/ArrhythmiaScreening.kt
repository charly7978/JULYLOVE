package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cribado de arritmias basado en RR. Calcula CV(RR), SDNN, RMSSD y pNN50 y
 * emite una alerta categórica cuando hay evidencia sostenida de irregularidad
 * con SQI adecuado.
 *
 * IMPORTANTE: esto NO diagnostica arritmia clínica. Es un cribado de
 * irregularidad de pulso basado en PPG.
 */
class ArrhythmiaScreening(
    private val windowLimit: Int = 32,
    private val cvThreshold: Double = 0.12,
    private val pnn50Threshold: Double = 0.30
) {
    private val rrWindow = ArrayDeque<Double>()
    private var lastRr: Double? = null

    data class Summary(
        val rrCount: Int,
        val meanRr: Double?,
        val sdnnMs: Double?,
        val rmssdMs: Double?,
        val pnn50: Double?,
        val coefficientOfVariation: Double?,
        val flagIrregular: Boolean,
        val flagHighHrv: Boolean
    )

    fun reset() { rrWindow.clear(); lastRr = null }

    fun ingest(beat: BeatEvent) {
        val rr = beat.rrMs ?: return
        if (beat.type == BeatType.INVALID_SIGNAL) return
        rrWindow.addLast(rr)
        while (rrWindow.size > windowLimit) rrWindow.removeFirst()
        lastRr = rr
    }

    fun compute(sqi: Double): Summary {
        val n = rrWindow.size
        if (n < 5 || sqi < 0.35) return Summary(n, null, null, null, null, null, false, false)
        val list = rrWindow.toList()
        val mean = list.average()
        var s2 = 0.0
        for (v in list) { val d = v - mean; s2 += d * d }
        val sdnn = sqrt(s2 / n)
        val cv = sdnn / mean
        var diffSum = 0.0; var nn50 = 0; var comparisons = 0
        for (i in 1 until n) {
            val d = list[i] - list[i - 1]
            diffSum += d * d
            if (abs(d) > 50.0) nn50++
            comparisons++
        }
        val rmssd = if (comparisons > 0) sqrt(diffSum / comparisons) else null
        val pnn50 = if (comparisons > 0) nn50.toDouble() / comparisons else null
        val irregular = cv > cvThreshold
        val highHrv = (pnn50 ?: 0.0) > pnn50Threshold
        return Summary(n, mean, sdnn, rmssd, pnn50, cv, irregular, highHrv)
    }
}
