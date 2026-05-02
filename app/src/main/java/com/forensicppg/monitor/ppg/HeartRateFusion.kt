package com.forensicppg.monitor.ppg

import kotlin.math.abs

/**
 * Fusión temporal/espectral de BPM. Acepta un BPM basado en mediana de RR
 * y uno basado en espectro; valida que coincidan dentro de una tolerancia
 * razonable y produce un BPM final con una medida de confianza.
 */
class HeartRateFusion(
    private val tolerancePercent: Double = 15.0,
    private val minBeatsForRr: Int = 5
) {
    data class Fused(val bpm: Double?, val confidence: Double, val source: String)

    /**
     * @param rrBpm BPM obtenido por mediana de RR intervals.
     * @param rrBeats número de RR usados (cuanto más, mejor).
     * @param specBpm BPM espectral.
     * @param specCoherence coherencia espectral en [0,1].
     * @param sqi SQI global en [0,1].
     */
    fun fuse(
        rrBpm: Double?,
        rrBeats: Int,
        specBpm: Double?,
        specCoherence: Double,
        sqi: Double
    ): Fused {
        if (sqi < 0.35) return Fused(null, 0.0, "bajo_sqi")

        val rrUsable = rrBpm != null && rrBeats >= minBeatsForRr
        val specUsable = specBpm != null && specCoherence >= 0.25

        if (rrUsable && specUsable) {
            val delta = abs(rrBpm!! - specBpm!!) / rrBpm * 100.0
            if (delta <= tolerancePercent) {
                val w = (rrBeats.toDouble() / (rrBeats + 4)).coerceIn(0.55, 0.85)
                val bpm = rrBpm * w + specBpm * (1.0 - w)
                val conf = (0.5 + 0.5 * (1.0 - delta / tolerancePercent)) * sqi
                return Fused(bpm, conf.coerceIn(0.0, 1.0), "fusion")
            }
            // Desacuerdo: se prioriza RR por ser por latido, pero baja confianza.
            return Fused(rrBpm, (0.35 * sqi).coerceIn(0.0, 1.0), "rr_disagree")
        }
        if (rrUsable) {
            val conf = (0.5 + 0.05 * rrBeats).coerceAtMost(0.9) * sqi
            return Fused(rrBpm, conf, "rr_only")
        }
        if (specUsable) {
            val conf = specCoherence * sqi
            return Fused(specBpm, conf.coerceIn(0.0, 0.8), "spec_only")
        }
        return Fused(null, 0.0, "insuficiente")
    }
}
