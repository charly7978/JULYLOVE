package com.forensicppg.monitor.forensic

/**
 * Entrada del registro de auditoría ROI / contacto / calidad de captura.
 * TRIGGER = condición adversa o cambio que requiere atención; CLEAR = recuperación o estabilidad.
 */
data class RoiAuditEvent(
    val timestampNs: Long,
    val edge: Edge,
    val channel: Channel,
    val summary: String,
    val payload: String
) {
    enum class Edge { TRIGGER, CLEAR }

    enum class Channel {
        CONTACT_SCORE,
        FINGER_MASK,
        CLIPPING_HIGH,
        CLIPPING_LOW,
        MOTION_FUSED,
        FPS_STABILITY,
        ROI_GEOMETRY,
        LOW_LIGHT
    }

    fun clipboardLine(): String =
        "${edge.name}\t${channel.name}\t$timestampNs\t$summary\t$payload"
}
