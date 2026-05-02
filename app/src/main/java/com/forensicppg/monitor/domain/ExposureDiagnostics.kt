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
    val awbLocked: Boolean = false
)
