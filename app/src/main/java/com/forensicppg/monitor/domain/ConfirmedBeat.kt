package com.forensicppg.monitor.domain

/**
 * Latido confirmado por detección de pico sobre señal filtrada y criterios fisiológicos.
 * Los campos derivan sólo de medición real; si no hay intervalo RR aún válido será null.
 */
data class ConfirmedBeat(
    val timestampNs: Long,
    val amplitude: Double,
    val rrIntervalMs: Double?,
    val confidence: Double,
    val sourceChannel: String,
    val sqiSegment: Double,
    val rhythmMarker: BeatRhythmMarker
)

enum class BeatRhythmMarker {
    NORMAL,
    ECTOPIC_OR_PREMATURE,
    PAUSE_SUSPECT,
    IRREGULAR,
    INITIAL_NOT_ENOUGH_CONTEXT
}
