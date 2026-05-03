package com.forensicppg.monitor.forensic

import com.forensicppg.monitor.domain.ConfirmedBeat
import com.forensicppg.monitor.domain.PpgSample

/**
 * Sesión de medición con metadata técnica y buffers de muestras / eventos.
 * Se usa para auditoría forense y exportación.
 */
data class MeasurementSession(
    val sessionId: String,
    val startEpochMs: Long,
    var endEpochMs: Long? = null,
    val deviceModel: String,
    val androidSdk: Int,
    val appVersion: String,
    val algorithmVersion: String,
    var cameraId: String = "",
    var physicalCameraId: String? = null,
    var torchEnabled: Boolean = false,
    var manualControlApplied: Boolean = false,
    var exposureTimeNs: Long? = null,
    var iso: Int? = null,
    var frameDurationNs: Long? = null,
    var targetFps: Int = 0,
    var fpsActualMean: Double = 0.0,
    var fpsJitterMs: Double = 0.0,
    var framesTotal: Int = 0,
    var framesAccepted: Int = 0,
    var framesRejected: Int = 0,
    /** Tramo ISP solicitado para la repetición Camera2 (tuning línea base). */
    var ispAcquisitionSummary: String? = null,
    /** ZLO efectivo cuando la sesión comienza o tras recalibración en vivo. */
    var sensorZloR: Double? = null,
    var sensorZloG: Double? = null,
    var sensorZloB: Double? = null,
    var zloSourceNote: String? = null,
    /** Preset ROI vs posición LED/lente persistido para esta cámara (`RoiGeometryPreset.presetId`). */
    var roiGeometryPresetId: String? = null,
    var calibrationProfileId: String? = null,
    val samples: MutableList<PpgSample> = mutableListOf(),
    val beats: MutableList<ConfirmedBeat> = mutableListOf(),
    val events: MutableList<MeasurementEvent> = mutableListOf(),
    var finalBpmMean: Double? = null,
    var finalBpmSdnn: Double? = null,
    var finalSpo2Mean: Double? = null,
    var finalSqiMean: Double? = null
)
