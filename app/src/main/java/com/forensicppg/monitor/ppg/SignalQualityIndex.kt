package com.forensicppg.monitor.ppg

import kotlin.math.abs
import kotlin.math.max

/**
 * Índice de calidad de señal compuesto (0..1). Incluye:
 *   - contacto
 *   - perfusion index
 *   - ausencia de clipping
 *   - motion artifact bajo
 *   - estabilidad de FPS
 *   - coherencia espectral
 *   - consistencia RR
 *   - estabilidad de ROI (varianza baja)
 *
 * Cada sub-componente aporta un peso. Si uno cae a 0, arrastra al global
 * por tratarse de un producto geométrico parcial para los críticos.
 */
class SignalQualityIndex {

    data class Input(
        val hasContact: Boolean,
        val perfusionIndex: Double,
        val clipHighRatio: Double,
        val clipLowRatio: Double,
        val motionScore: Double,
        val fpsActual: Double,
        val fpsTarget: Double,
        val spectralCoherence: Double,
        val rrCv: Double?,
        val rrCount: Int,
        val roiSpatialStd: Double
    )

    fun evaluate(input: Input): Double {
        if (!input.hasContact) return 0.0
        val pi = pi01(input.perfusionIndex)
        val clip = clip01(input.clipHighRatio, input.clipLowRatio)
        val motion = (1.0 - input.motionScore.coerceIn(0.0, 1.0))
        val fps = fps01(input.fpsActual, input.fpsTarget)
        val coh = input.spectralCoherence.coerceIn(0.0, 1.0)
        val rr = rrConsistency01(input.rrCv, input.rrCount)
        val roi = roiStability01(input.roiSpatialStd)

        // Producto parcial para críticos (contacto, clipping, motion): si
        // alguno es 0, el SQI es 0. El resto pondera linealmente.
        val hard = pi * clip * motion
        val soft = 0.30 * fps + 0.25 * coh + 0.25 * rr + 0.20 * roi
        return (hard * soft).coerceIn(0.0, 1.0)
    }

    private fun pi01(pi: Double): Double {
        if (pi <= 0.05) return 0.0
        if (pi >= 2.0) return 1.0
        return ((pi - 0.05) / (2.0 - 0.05)).coerceIn(0.0, 1.0)
    }
    private fun clip01(high: Double, low: Double): Double {
        val h = (0.18 - high).coerceAtLeast(0.0) / 0.18
        val l = (0.15 - low).coerceAtLeast(0.0) / 0.15
        return h * l
    }
    private fun fps01(actual: Double, target: Double): Double {
        if (target <= 0.0) return 0.0
        val ratio = actual / target
        return when {
            ratio >= 0.9 -> 1.0
            ratio >= 0.6 -> ((ratio - 0.6) / 0.3).coerceIn(0.0, 1.0)
            else -> 0.0
        }
    }
    private fun rrConsistency01(cv: Double?, count: Int): Double {
        if (cv == null || count < 3) return 0.0
        val base = (0.25 - cv).coerceAtLeast(0.0) / 0.25
        val weight = (count.toDouble() / (count + 5)).coerceIn(0.0, 1.0)
        return base * weight
    }
    private fun roiStability01(roiStd: Double): Double {
        val s = max(0.0, 70.0 - roiStd) / 70.0
        return s.coerceIn(0.0, 1.0)
    }

    enum class Band { EXCELLENT, GOOD, DEGRADED, INVALID }

    fun band(sqi: Double): Band = when {
        sqi >= 0.75 -> Band.EXCELLENT
        sqi >= 0.55 -> Band.GOOD
        sqi >= 0.35 -> Band.DEGRADED
        else -> Band.INVALID
    }

    companion object {
        fun bandLabel(b: Band): String = when (b) {
            Band.EXCELLENT -> "EXCELENTE"
            Band.GOOD -> "BUENO"
            Band.DEGRADED -> "DEGRADADO"
            Band.INVALID -> "INVÁLIDO"
        }
    }

    @Suppress("unused") fun dummy(a: Double) = abs(a)
}
