package com.forensicppg.monitor.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Range
import android.util.Size

/**
 * Describe las capacidades relevantes para PPG de una cámara concreta, tal
 * como se obtuvieron del `CameraCharacteristics`. No simula ningún valor: si
 * una capacidad no existe en el dispositivo, se reporta `null`/`false`.
 */
data class CameraCapabilities(
    val cameraId: String,
    val lensFacing: Int,
    val hardwareLevel: Int,
    val hasFlash: Boolean,
    val supportsManualSensor: Boolean,
    val supportsAeLock: Boolean,
    val supportsAwbLock: Boolean,
    val supportsLogicalMultiCamera: Boolean,
    val physicalCameraIds: Set<String>,
    val sensorOrientation: Int,
    val activeArrayPixels: Long,
    val minExposureNs: Long?,
    val maxExposureNs: Long?,
    val minIso: Int?,
    val maxIso: Int?,
    val maxAnalogSensitivity: Int?,
    val availableFpsRanges: List<Range<Int>>,
    val supportedYuvSizes: List<Size>,
    val focalLengthsMm: List<Float>,
    val minFocusDistanceDiopters: Float?,
    val rollingShutterSkewNs: Long?
) {
    /** Prioridad relativa (mayor = mejor) para seleccionar cámara trasera para PPG. */
    fun ppgPreferenceScore(): Double {
        var score = 0.0
        if (hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3) score += 4.0
        else if (hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) score += 3.0
        else if (hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED) score += 1.0
        if (hasFlash) score += 3.0
        if (supportsManualSensor) score += 2.0
        if (supportsAeLock) score += 1.0
        if (supportsAwbLock) score += 1.0
        if ((rollingShutterSkewNs ?: Long.MAX_VALUE) < 20_000_000L) score += 1.0
        return score
    }

    val maxFps: Int get() = availableFpsRanges.maxOfOrNull { it.upper } ?: 30

    val hasManualControl: Boolean
        get() = supportsManualSensor && minExposureNs != null && maxExposureNs != null
                && minIso != null && maxIso != null

    val isBackFacing: Boolean get() = lensFacing == CameraCharacteristics.LENS_FACING_BACK

    companion object {
        fun levelName(hardwareLevel: Int): String = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }
}
