package com.julylove.medical.camera

/**
 * Un frame: medias RGB sRGB sobre ROI + firmas espaciales (dedo vs cobertura parcial vs superficie brillante uniforme).
 */
data class PpgCameraFrame(
    val redSrgb: Float,
    val greenSrgb: Float,
    val blueSrgb: Float,
    /** min/máx sobre 4 cuadrantes (medias R). Dedo bien colocado → cercano a 1. */
    val quadrantBalanceRed: Float,
    /** Desviación típica de medias Y de bloque 4×4 (contraste intra‑ROI). */
    val blockLumaStd: Float,
    /** Suma primeras diferencias sobre malla luminancia de bloques (textura tipo piel‑presión). */
    val interBlockGradient: Float
)
