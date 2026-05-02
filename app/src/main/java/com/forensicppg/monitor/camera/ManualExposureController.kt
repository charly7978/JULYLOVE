package com.forensicppg.monitor.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import com.forensicppg.monitor.ppg.PpgAcquisitionTuning

/**
 * Aplica control manual de exposición / ISO / frame duration y bloquea AE/AWB
 * cuando el hardware lo soporta. Si el dispositivo NO expone MANUAL_SENSOR la
 * función devuelve `false` y quien la llame debe registrar "control manual
 * parcial" en el panel de diagnóstico.
 */
object ManualExposureController {

    /** Parámetros objetivo para PPG de contacto con torch ON. */
    data class Target(
        val exposureTimeNs: Long,
        val iso: Int,
        val frameDurationNs: Long
    )

    /**
     * Calcula parámetros razonables para un sensor cualquiera dentro de sus
     * rangos reales. Utiliza límites conservadores que favorecen alta SNR con
     * torch ON y evitan saturación: exposición ≈ 8 ms, ISO ≈ 2x el mínimo.
     */
    fun computeTarget(caps: CameraCapabilities, targetFps: Int): Target? {
        if (!caps.hasManualControl) return null
        val minExp = caps.minExposureNs ?: return null
        val maxExp = caps.maxExposureNs ?: return null
        val minIso = caps.minIso ?: return null
        val maxIso = caps.maxIso ?: return null

        val fps = targetFps.coerceAtLeast(15)
        val maxFrameDurationForFps = 1_000_000_000L / fps
        val desiredExposure = PpgAcquisitionTuning.MANUAL_DESIRED_EXPOSURE_NS
        val clampedExposure = desiredExposure.coerceIn(minExp, minOf(maxExp, maxFrameDurationForFps - 500_000L))
        val desiredIso = minOf(maxIso, maxOf(minIso, (caps.maxAnalogSensitivity ?: minIso) / 2))
        val frameDuration = maxFrameDurationForFps.coerceAtLeast(clampedExposure + 500_000L)
        return Target(clampedExposure, desiredIso, frameDuration)
    }

    fun apply(
        builder: CaptureRequest.Builder,
        caps: CameraCapabilities,
        target: Target?
    ): Boolean {
        if (target == null || !caps.hasManualControl) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (caps.supportsAeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            if (caps.supportsAwbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            return false
        }
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, target.exposureTimeNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, target.iso)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, target.frameDurationNs)
        if (caps.supportsAeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        if (caps.supportsAwbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        return true
    }
}
