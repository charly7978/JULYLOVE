package com.forensicppg.monitor.domain

/**
 * Estados del pipeline de medición. Se determinan a partir de evidencia real
 * obtenida de la cámara y los sensores. Ningún estado puede ser forzado desde
 * la UI: siempre proviene del procesamiento.
 */
enum class MeasurementState {
    NO_CONTACT,
    CONTACT_PARTIAL,
    WARMUP,
    MEASURING,
    DEGRADED,
    INVALID,
    CALIBRATION_REQUIRED;

    val isBlocking: Boolean
        get() = this == NO_CONTACT || this == CONTACT_PARTIAL || this == INVALID

    val allowsMetrics: Boolean
        get() = this == MEASURING || this == DEGRADED

    fun labelEs(): String = when (this) {
        NO_CONTACT -> "SIN CONTACTO"
        CONTACT_PARTIAL -> "CONTACTO PARCIAL"
        WARMUP -> "CALENTAMIENTO"
        MEASURING -> "MIDIENDO"
        DEGRADED -> "SEÑAL DEGRADADA"
        INVALID -> "SIN LECTURA VÁLIDA"
        CALIBRATION_REQUIRED -> "CALIBRACIÓN REQUERIDA"
    }
}
