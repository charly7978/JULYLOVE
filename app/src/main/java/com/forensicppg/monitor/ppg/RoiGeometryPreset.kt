package com.forensicppg.monitor.ppg

/**
 * Corrección geométrica del ROI sobre YUV antes de ROI→RGB pulsátil.
 * Negative [verticalBiasFracOfHeight]: ROI se desplaza hacia el borde SUPERIOR del frame
 * (muchos módulos cámara+flash están arriba del centro).
 * Negative [horizontalBiasFracOfWidth]: ROI hacia la IZQUIERDA (mirando pantalla típico trasero como bitmap).
 */
enum class RoiGeometryPreset(
    val presetId: String,
    val labelEs: String,
    /** Texto corto para chips UI. */
    val chipLabelEs: String,
    val verticalBiasFracOfHeight: Double,
    val horizontalBiasFracOfWidth: Double
) {
    NEUTRAL(
        presetId = "neutral",
        labelEs = "Centro geométrico (sin sesgo)",
        chipLabelEs = "Centro",
        verticalBiasFracOfHeight = 0.0,
        horizontalBiasFracOfWidth = 0.0
    ),
    FLASH_ARRIBA(
        presetId = "flash_arriba",
        labelEs = "Flash habitual arriba del sensor",
        chipLabelEs = "Flash ↑",
        verticalBiasFracOfHeight = -0.058,
        horizontalBiasFracOfWidth = 0.0
    ),
    FLASH_ABAJO(
        presetId = "flash_abajo",
        labelEs = "Módulo bajo · compensar hacia abajo",
        chipLabelEs = "Flash ↓",
        verticalBiasFracOfHeight = 0.068,
        horizontalBiasFracOfWidth = 0.0
    ),
    LED_IZQUIERDA(
        presetId = "led_izquierda",
        labelEs = "LED lateral a la izquierda del lente (vista habitual)",
        chipLabelEs = "LED ←",
        verticalBiasFracOfHeight = -0.042,
        horizontalBiasFracOfWidth = -0.062
    ),
    LED_DERECHA(
        presetId = "led_derecha",
        labelEs = "LED lateral a la derecha del lente (vista habitual)",
        chipLabelEs = "LED →",
        verticalBiasFracOfHeight = -0.042,
        horizontalBiasFracOfWidth = 0.062
    );

    companion object {
        /** Si no hay dato persistido usamos compensación típica “flash arriba” (similar al tuning previo sin selector). */
        fun defaultResolved(): RoiGeometryPreset = FLASH_ARRIBA

        fun fromPersistedId(id: String?): RoiGeometryPreset {
            if (id.isNullOrBlank()) return defaultResolved()
            return entries.find { it.presetId == id.trim() } ?: defaultResolved()
        }
    }
}
