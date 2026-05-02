package com.forensicppg.monitor.domain

/**
 * Muestra individual del pipeline PPG. Se produce una muestra por frame real
 * de la cámara. `raw` y `filtered` deben proceder SIEMPRE de un frame real;
 * está prohibido generar muestras sintéticas.
 */
data class PpgSample(
    val timestampNs: Long,
    val raw: Double,
    val filtered: Double,
    val displayValue: Double,
    val sqi: Double,
    val perfusionIndex: Double,
    val motionScore: Double,
    val valid: Boolean
)

/**
 * Evento de latido detectado sobre la señal real. `rrMs` y `bpmInstant` pueden
 * ser nulos en el primer latido o si el RR anterior no era fisiológico.
 */
data class BeatEvent(
    val timestampNs: Long,
    val amplitude: Double,
    val rrMs: Double? = null,
    val bpmInstant: Double? = null,
    val quality: Double = 0.0,
    val type: BeatType = BeatType.NORMAL,
    val reason: String = ""
)

enum class BeatType {
    NORMAL,
    SUSPECT_PREMATURE,
    SUSPECT_PAUSE,
    SUSPECT_MISSED,
    IRREGULAR,
    INVALID_SIGNAL
}
