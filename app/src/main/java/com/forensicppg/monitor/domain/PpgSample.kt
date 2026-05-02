package com.forensicppg.monitor.domain

/**
 * Una muestra coherente con un frame óptico real: bandas ROI, estadística y metadatos
 * temporales incluyendo jitter de captura. No debe fabricarse sintéticamente.
 */
data class PpgSample(
    val timestampNs: Long,
    val monotonicRealtimeNs: Long,
    val rawRed: Double,
    val rawGreen: Double,
    val rawBlue: Double,
    /** Señal preprocesada normalmente basada en el canal principal (typ. verde) */
    val filteredPrimary: Double,
    val displayWave: Double,
    val roiStats: RoiChannelStats,
    val clippingHighRatio: Double,
    val clippingLowRatio: Double,
    val motionScoreOptical: Double,
    val exposureDiagnostics: ExposureDiagnostics,
    val lowLightSuspected: Boolean,
    /** SQI compuesto último ciclo DSP [0..1] */
    val sqi: Double,
    /** Canales paralelos cuando el procesador DSP los proporciona */
    val filteredSecondary: Double? = null
) {
    val perfusionIndexGreen: Double get() = roiStats.perfusionIndexGreenPct

    /** Alias documentado para mezclas IMU vs óptica en capas superiores. */
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
    /** Varianza espacial agregada dentro del ROI (estabilidad estructura) */
    val roiVarianceLuma: Double
)
