package com.forensicppg.monitor.forensic

/**
 * Evento auditable durante la sesión. Se registra con timestamp monotónico
 * (nanosegundos) para correlacionarlo con las muestras.
 */
data class MeasurementEvent(
    val timestampNs: Long,
    val kind: Kind,
    val details: String
) {
    enum class Kind {
        STATE_CHANGE,
        CONTACT_LOST,
        CONTACT_RESTORED,
        MOTION_HIGH,
        CLIPPING_HIGH,
        CLIPPING_LOW,
        FPS_DEGRADED,
        BEAT_ABNORMAL,
        CALIBRATION_APPLIED,
        CALIBRATION_MISSING,
        EXPORT,
        SESSION_START,
        SESSION_END,
        ZLO_CAPTURE_START,
        ZLO_CAPTURE_OK,
        ERROR
    }
}
