package com.forensicppg.monitor.camera

import android.hardware.camera2.CaptureRequest

/**
 * Aplica el control del torch al request builder. Activa `TORCH` únicamente
 * cuando la cámara seleccionada tiene flash físico; de lo contrario devuelve
 * `false` y la UI mostrará una advertencia.
 */
object TorchController {
    fun apply(builder: CaptureRequest.Builder, capabilities: CameraCapabilities, enabled: Boolean): Boolean {
        if (!capabilities.hasFlash) return false
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
        return enabled
    }
}
