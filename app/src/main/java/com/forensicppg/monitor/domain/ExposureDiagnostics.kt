package com.forensicppg.monitor.domain

/**
 * Estado óptico por frame y limitaciones cuando el HW no permite control manual total.
 */
data class ExposureDiagnostics(
    val exposureTimeNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    val torchEnabled: Boolean,
    val hardwareLimitNote: String?,
    val aeLocked: Boolean = false,
    val awbLocked: Boolean = false,
    /** Resumen opcional de modo ISP (noise/edge) aplicado sobre el request repetido — no es tonemap lineal completo OEM. */
    val ispAcquisitionSummary: String? = null,
    /** Corrección tipo ZLO (resta de media digital antes de pulsátil) por canal tras YUV→RGB ROI. Null si no configurada (0 efectivo). */
    val sensorZloR: Double? = null,
    val sensorZloG: Double? = null,
    val sensorZloB: Double? = null,
    /** literatura|capturado|ninguno — trazabilidad forense. */
    val zloSourceSummary: String? = null
)
