package com.forensicppg.monitor.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build

/**
 * Reduce procesamiento ISP agresivo (NR/EDGE/FX) dentro de Camera2 público.
 * No sustituye un tone-mapping lineal + ZLO caracterizado en bench (Wang et al. 2023),
 * pero mejora la repetibilidad relativa dentro del mismo modelo y sesión.
 */
object CapturePipelineTuner {

    @Suppress("DEPRECATION")
    fun applyBestEffort(
        chars: CameraCharacteristics,
        builder: CaptureRequest.Builder
    ): String {
        val segments = mutableListOf<String>()
        chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.let { arr ->
            when {
                arr.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) -> {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    segments += "NR_OFF"
                }

                arr.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) -> {
                    builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                    segments += "NR_FAST"
                }
            }
        }
        chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.let { arr ->
            when {
                arr.contains(CaptureRequest.EDGE_MODE_OFF) -> {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    segments += "EDGE_OFF"
                }

                arr.contains(CaptureRequest.EDGE_MODE_FAST) -> {
                    builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    segments += "EDGE_FAST"
                }
            }
        }
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)
        segments += "FX_OFF"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.let { arr ->
                when {
                    arr.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF) -> {
                        builder.set(
                            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                        )
                        segments += "CC_ABR_OFF"
                    }

                    arr.contains(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST) -> {
                        builder.set(
                            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST
                        )
                        segments += "CC_ABR_FAST"
                    }
                }
            }
            runCatching {
                builder.set(CaptureRequest.BLACK_LEVEL_LOCK, true)
                segments += "BL_LOCK_TRY"
            }
        }

        chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            segments += "AF_OFF_INFINITY"
        }

        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.let { arr ->
            if (arr.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                segments += "VID_STAB_OFF"
            }
        }

        if (segments.isEmpty()) return "isp_default_oem"
        return segments.joinToString("+")
    }
}
