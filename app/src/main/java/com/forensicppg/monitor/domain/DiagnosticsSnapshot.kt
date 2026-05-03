package com.forensicppg.monitor.domain

/**
 * Trazabilidad técnica de captura/procesamiento para panel de diagnóstico opcional.
 */
data class DiagnosticsSnapshot(
    val measuredFps: Double,
    val targetFps: Int,
    val frameDropCount: Long,
    val frameJitterMeanMs: Double,
    val torchEnabled: Boolean,
    val manualSensorApplied: Boolean,
    val hardwareLimitNote: String?,
    val lastExposureNs: Long?,
    val lastIso: Int?,
    val peakConfirmedCountSession: Int,
    val peakRejectedCountSession: Int,
    val lastRejectionDigest: String,
    val spo2CalibrationStatus: String,
    val rhythmDigest: String,
    /** Wang et al. 2023: offset de nivel cero efectivo aplicado sobre ROI (digital). */
    val sensorZloR: Double? = null,
    val sensorZloG: Double? = null,
    val sensorZloB: Double? = null,
    val zloSourceNote: String? = null,
    val ispAcquisitionSummary: String? = null
)
