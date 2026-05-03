package com.forensicppg.monitor.ppg

/**
 * Parámetros de adquisición cPPG fijados de forma única aquí para evitar
 * “números mágicos” dispersos en cámara y analizador. Los valores siguen la
 * literatura habitual (30 Hz estable, AE/ISO fijos cuando existe MANUAL_SENSOR;
 * véase p. ej. revisión PMC10937558 y calibración cPPG Wang et al. 2023,
 * Front. Digit. Health) y heurísticas internas reproducibles — no RNG.
 */
object PpgAcquisitionTuning {
    /**
     * Exposición inicial deseada (ns) cuando el hardware permite modo manual —
     * orden típico 5–15 ms citado para cPPG con LED en contacto cercano en
     * literatura reproducible sobre exposición controlada vs. modo estético.
     */
    const val MANUAL_DESIRED_EXPOSURE_NS = 8_000_000L

    /** Máximo de imágenes en cola antes de procesarlas; amortiza jitter del ISP. */
    const val IMAGE_READER_MAX_IMAGES = 14

    /**
     * Si el intervalo entre dos frames supera [nominalFrameMs] × este factor,
     * se cuenta como cuadro perdido (solo telemetría).
     */
    const val FRAME_DROP_GAP_MULTIPLIER = 2.45

    /** Submuestreo espacial dentro del ROI si el ancho supera este valor (pixels). */
    const val ROI_SUBSAMPLE_WIDE_THRESHOLD_PX = 384

    /** Ventana rápida de medias ROI por canal (frames). ≈200/30 Hz ≈6.7 s histórico local. */
    const val CHANNEL_ROLLING_CAPACITY = 200

    /** EMA coeficiente para movimiento óptico intra-ROI [0..1]. Más bajo ⇒ feedback más rápido (mejor UX al colocar el dedo). */
    const val MOTION_EMA_ALPHA = 0.88

    /** Umbral inferior de movimiento estable para contraer ROI ligeramente. */
    const val MOTION_STABLE_GATE = 0.042

    /** Frames continuos estables antes de usar ROI más compacto (menos bordes). */
    const val MOTION_STABLE_FRAMES_NEED = 52

    /** Fracciones adaptativas del ROI (centro frame), acotadas. */
    const val ROI_FRACTION_TIGHT = 0.50
    const val ROI_FRACTION_LOOSE = 0.655

}
