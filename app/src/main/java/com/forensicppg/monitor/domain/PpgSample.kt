package com.forensicppg.monitor.domain

/**
 * Muestra coherente con un frame óptico real. Si el extractor devuelve `null`,
 * no hubo contacto óptico válido — el pipeline no debe inventar señal.
 */
data class PpgSample(
    val timestampNs: Long,
    val monotonicRealtimeNs: Long,
    /** Medias ROI antes de ZLO (RGB 0–255, mismo espacio que captura). */
    val roiMeanPreZloRed: Double,
    val roiMeanPreZloGreen: Double,
    val roiMeanPreZloBlue: Double,
    /** Robustos tras corrección ZLO (DC digital); usados por SpO₂ / coherencia. */
    val rawRed: Double,
    val rawGreen: Double,
    val rawBlue: Double,
    /** Absorbancia relativa -ln(I/I_dc) en el canal principal (típ. verde). */
    val ppgGreenAbsorbance: Double,
    val ppgRedAbsorbance: Double,
    /** Señal mostrada / DSP (absorbancia filtrada o 0 si aún no procede). */
    val filteredPrimary: Double,
    val displayWave: Double,
    val roiStats: RoiChannelStats,
    val clippingHighRatio: Double,
    val clippingLowRatio: Double,
    /** Movimiento óptico intra-ROI [0..1]. */
    val motionScoreOptical: Double,
    val exposureDiagnostics: ExposureDiagnostics,
    val lowLightSuspected: Boolean,
    /** Cobertura de la máscara de dedo dentro del ROI usado [0..1]. */
    val maskCoverage: Double,
    /** Coherencia dedo + perfil cromático + cobertura [0..1]. */
    val contactScore: Double,
    /** FPS instantáneo estimado (ventana corta de timestamps). */
    val instantaneousFpsHz: Double,
    /**
     * ROI contraído alrededor de la región útil (dedo detectado).
     * Si no hay dedo, los valores serán 0.
     */
    val roiBoundingLeft: Int,
    val roiBoundingTop: Int,
    val roiBoundingWidth: Int,
    val roiBoundingHeight: Int,
    /** Motivo de rechazo si la muestra es marginal (p. ej. dedo parcial). Vacío si OK. */
    val rejectReason: String,
    /** Si es falso, la UI no debe dibujar morfología cardíaca (solo línea técnica). */
    val waveformDisplayAllowed: Boolean = false,
    val sqi: Double,
    val filteredSecondary: Double? = null
) {
    val perfusionIndexGreen: Double get() = roiStats.perfusionIndexGreenPct

    val motionScore: Double get() = motionScoreOptical
}

data class RoiChannelStats(
    val roiLeft: Int,
    val roiTop: Int,
    val roiWidth: Int,
    val roiHeight: Int,
    val redMean: Double,
    val greenMean: Double,
    val blueMean: Double,
    val redMedian: Double,
    val greenMedian: Double,
    val blueMedian: Double,
    val redTrimmedMean2080: Double,
    val greenTrimmedMean2080: Double,
    val blueTrimmedMean2080: Double,
    val redP05: Double,
    val greenP05: Double,
    val blueP05: Double,
    val redP50: Double,
    val greenP50: Double,
    val blueP50: Double,
    val redP95: Double,
    val greenP95: Double,
    val blueP95: Double,
    val redMad: Double,
    val greenMad: Double,
    val blueMad: Double,
    val saturationR01: Double,
    val saturationG01: Double,
    val saturationB01: Double,
    val redDominanceRg: Double,
    val greenPulsatility: Double,
    val blueChannelStability: Double,
    val perfusionIndexGreenPct: Double,
    val redAcDc: Double,
    val greenAcDc: Double,
    val blueAcDc: Double,
    val roiVarianceLuma: Double
) {
    fun fingerProfileScore(): Double {
        val rg = redDominanceRg
        val okRg = when {
            rg in 1.01..1.90 -> 1.0
            rg in 0.95..2.10 -> 0.55
            else -> 0.12
        }
        val gdc = greenAcDc.coerceIn(0.0, 0.35)
        val perf = (perfusionIndexGreenPct / 85.0).coerceIn(0.0, 1.2)
        return (0.45 * okRg + 0.35 * minOf(perf, 1.0) + 0.20 * minOf(gdc * 4.0, 1.0))
            .coerceIn(0.0, 1.0)
    }
}
