package com.forensicppg.monitor.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
// SENSOR_ROLLING_SHUTTER_SKEW se lee desde CaptureResult, no desde CameraCharacteristics.
import java.util.Collections

/**
 * Selecciona la mejor cámara trasera para PPG entre las disponibles. Si el
 * dispositivo expone una `LOGICAL_MULTI_CAMERA`, también analiza las cámaras
 * físicas sub-yacentes y elige la que mejor puntúa (sensibilidad,
 * hardware_level, flash, control manual, etc.).
 */
object MultiCameraSelector {

    private const val IMAGE_FORMAT_YUV_420_888 = 0x23

    fun enumerateBackCameras(context: Context): List<CameraCapabilities> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<CameraCapabilities>()
        for (id in manager.cameraIdList) {
            val caps = runCatching { describeCamera(manager, id) }.getOrNull() ?: continue
            if (caps.isBackFacing) out += caps
            for (physicalId in caps.physicalCameraIds) {
                val phys = runCatching { describeCamera(manager, physicalId) }.getOrNull()
                if (phys != null && phys.isBackFacing) out += phys
            }
        }
        return out.distinctBy { it.cameraId }
    }

    fun selectBest(context: Context): CameraCapabilities? {
        val cams = enumerateBackCameras(context)
        return cams.maxByOrNull { it.ppgPreferenceScore() }
    }

    fun describeCamera(manager: CameraManager, cameraId: String): CameraCapabilities {
        val chars = manager.getCameraCharacteristics(cameraId)
        val lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val supportsManual = caps.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR }
        val supportsLogical = caps.any { it == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA }
        val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val maxAnalog = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val yuvSizes = map?.getOutputSizes(IMAGE_FORMAT_YUV_420_888)?.toList() ?: emptyList<Size>()
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val activePixels = sensorSize?.let { it.width.toLong() * it.height.toLong() } ?: 0L
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rollingSkew: Long? = null
        val physical: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && supportsLogical) {
            chars.physicalCameraIds ?: Collections.emptySet()
        } else Collections.emptySet()

        return CameraCapabilities(
            cameraId = cameraId,
            lensFacing = lensFacing,
            hardwareLevel = hardwareLevel,
            hasFlash = hasFlash,
            supportsManualSensor = supportsManual,
            supportsAeLock = aeLock,
            supportsAwbLock = awbLock,
            supportsLogicalMultiCamera = supportsLogical,
            physicalCameraIds = physical,
            sensorOrientation = orientation,
            activeArrayPixels = activePixels,
            minExposureNs = expRange?.lower,
            maxExposureNs = expRange?.upper,
            minIso = isoRange?.lower,
            maxIso = isoRange?.upper,
            maxAnalogSensitivity = maxAnalog,
            availableFpsRanges = fpsRanges,
            supportedYuvSizes = yuvSizes,
            focalLengthsMm = focals,
            minFocusDistanceDiopters = minFocus,
            rollingShutterSkewNs = rollingSkew
        )
    }
}
