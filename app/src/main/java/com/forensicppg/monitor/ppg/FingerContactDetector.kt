package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.CameraFrame
import com.forensicppg.monitor.domain.MeasurementState
import com.forensicppg.monitor.domain.ReadingValidity

/**
 * Detector de contacto del dedo. Basado en criterios ópticos verificables con
 * los datos reales del frame:
 *
 *  1. Dominancia del canal rojo respecto a G y B (hemoglobina + torch blanco).
 *  2. Brillo medio en rango razonable (ni dedo ausente, ni saturado).
 *  3. Clipping bajo (rechaza saturaciones extremas).
 *  4. Varianza espacial baja (dedo homogéneo vs fondo heterogéneo).
 *  5. Cobertura alta del ROI.
 *
 * Se usa histéresis temporal simple para no parpadear entre estados.
 */
class FingerContactDetector(
    private val warmupMs: Long = 2500L,
    private val minRedGreenRatio: Double = 1.25,
    private val minRedBlueRatio: Double = 1.30,
    private val minRedMean: Double = 60.0,
    private val maxRedMean: Double = 245.0,
    private val maxClipHigh: Double = 0.18,
    private val maxClipLow: Double = 0.15,
    private val maxStdForFinger: Double = 60.0,
    private val holdSamples: Int = 4
) {

    private var stableFor = 0
    private var unstableFor = 0
    private var measuringSince: Long? = null
    private var lastState: MeasurementState = MeasurementState.NO_CONTACT

    /**
     * Evalúa un frame y devuelve el estado y los flags de motivo.
     */
    fun evaluate(frame: CameraFrame, motionScore: Double, fps: Double): Pair<MeasurementState, Int> {
        var flags = 0
        var contact = true

        if (frame.redMean < minRedMean) { contact = false; flags = flags or ReadingValidity.NO_FINGER }
        if (frame.redGreenRatio < minRedGreenRatio) { contact = false; flags = flags or ReadingValidity.NO_FINGER }
        if (frame.redBlueRatio < minRedBlueRatio) { contact = false; flags = flags or ReadingValidity.NO_FINGER }
        if (frame.roiCoverage < 0.6) { contact = false; flags = flags or ReadingValidity.PARTIAL_CONTACT }

        if (frame.clipHighRatio > maxClipHigh) flags = flags or ReadingValidity.CLIPPING_HIGH
        if (frame.clipLowRatio > maxClipLow) flags = flags or ReadingValidity.CLIPPING_LOW
        if (frame.redMean > maxRedMean) flags = flags or ReadingValidity.CLIPPING_HIGH

        val spatialStd = kotlin.math.sqrt(frame.roiVariance.coerceAtLeast(0.0))
        if (spatialStd > maxStdForFinger) {
            flags = flags or ReadingValidity.PARTIAL_CONTACT
            contact = false
        }
        if (motionScore > 0.6) flags = flags or ReadingValidity.MOTION_HIGH
        if (fps in 1.0..18.0) flags = flags or ReadingValidity.LOW_FPS

        val newState = nextState(contact, flags, frame.timestampNs)
        lastState = newState
        return newState to flags
    }

    private fun nextState(contact: Boolean, flags: Int, timestampNs: Long): MeasurementState {
        if (!contact) {
            stableFor = 0
            unstableFor = (unstableFor + 1).coerceAtMost(holdSamples * 2)
            measuringSince = null
            return if (flags and ReadingValidity.NO_FINGER != 0) MeasurementState.NO_CONTACT
            else MeasurementState.CONTACT_PARTIAL
        }
        stableFor = (stableFor + 1).coerceAtMost(holdSamples * 4)
        unstableFor = 0

        if (stableFor < holdSamples) return MeasurementState.WARMUP

        if (measuringSince == null) measuringSince = timestampNs
        val elapsedMs = (timestampNs - (measuringSince ?: timestampNs)) / 1_000_000L
        if (elapsedMs < warmupMs) return MeasurementState.WARMUP

        val degradingFlags =
            ReadingValidity.CLIPPING_HIGH or
            ReadingValidity.CLIPPING_LOW or
            ReadingValidity.MOTION_HIGH or
            ReadingValidity.LOW_FPS or
            ReadingValidity.LOW_PERFUSION
        if ((flags and degradingFlags) != 0) return MeasurementState.DEGRADED
        return MeasurementState.MEASURING
    }

    fun reset() {
        stableFor = 0
        unstableFor = 0
        measuringSince = null
        lastState = MeasurementState.NO_CONTACT
    }
}
