package com.forensicppg.monitor.camera

import android.util.Size

/**
 * Configuración resuelta de la sesión de cámara: tamaño de frame, rango de FPS
 * objetivo y parámetros manuales efectivos aplicados. Todos los valores son
 * reales, no hay defaults cosméticos: si no pudieron fijarse manualmente,
 * `manualControlApplied = false` y la UI mostrará "control manual parcial".
 */
data class CameraSessionConfig(
    val cameraId: String,
    val physicalCameraId: String?,
    val previewSize: Size,
    val targetFpsRange: Pair<Int, Int>,
    val format: Int,
    val torchEnabled: Boolean,
    val manualControlApplied: Boolean,
    val manualExposureNs: Long?,
    val manualIso: Int?,
    val manualFrameDurationNs: Long?,
    val aeLocked: Boolean,
    val awbLocked: Boolean
)
