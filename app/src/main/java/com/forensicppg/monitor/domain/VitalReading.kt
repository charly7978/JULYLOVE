package com.forensicppg.monitor.domain

/**
 * Consolidado para la UI tras analizar muestras reales continuas sin bloqueos.
 * BPM y SpO₂ clínicos sólo con evidencia y calibración adecuada.
 */
data class VitalReading(
    val bpmInstant: Double? = null,
    val bpmSmoothed: Double? = null,
    val bpmAverage: Double? = null,
    val bpmConfidence: Double = 0.0,
    val beatsValidUsed: Int = 0,
    val bpmWindowSeconds: Double = 0.0,
    val spo2: Double? = null,
    val spo2Confidence: Double = 0.0,
    val spo2RatioR: Double? = null,
    val spo2CalibrationStatus: String = "",
    val spo2WindowSecondsUsed: Double = 0.0,
    /** Índice experimental ratio-of-ratios sin modelo clínico calibrado. */
    val spo2ExperimentalIndex: Double? = null,
    val sqi: Double = 0.0,
    val snrBandDbEstimate: Double = 0.0,
    val dominantHeartHz: Double = 0.0,
    val perfusionIndex: Double = 0.0,
    val motionScore: Double = 0.0,
    val rrMs: Double? = null,
    val rrSdnnMs: Double? = null,
    val rmssdMs: Double? = null,
    val pnn50: Double? = null,
    val irregularityCoefficient: Double? = null,
    val beatsDetected: Int = 0,
    val abnormalBeatCandidates: Int = 0,
    val validityState: PpgValidityState = PpgValidityState.RAW_OPTICAL_ONLY,
    val validityFlags: Int = ReadingValidity.OK,
    val rhythmPatternHint: RhythmPatternHint = RhythmPatternHint.INSUFFICIENT_DATA,
    val tachySuspected: Boolean = false,
    val bradySuspected: Boolean = false,
    val pauseSuspected: Boolean = false,
    /** Copia superficial del último tachograma RR (visualización tachogram mini). */
    val rrRecentMs: List<Double> = emptyList(),
    /** Marca temporal sobre la onda donde hubo RR irregular sospechosos */
    val irregularSegmentTimestampsNs: List<Long> = emptyList(),
    val messagePrimary: String = "",
    val hypertensionRisk: HypertensionRiskBand? = null,
    val peakConfirmations: Int = 0,
    val peakRejectedCount: Int = 0,
    val rejectionTrace: String = "",
    val diagnostics: DiagnosticsSnapshot? = null,
    /** Onda crudo para superposición opcional (valor medio verde último frame). */
    val lastRawWaveY: Double? = null,
    /** Señal filtrada última muestra dominante (monitor). */
    val lastFilteredWaveY: Double? = null,
    /** Indica última muestra con clipping perceptible (> umbral extractor). */
    val clippingSuspectedHigh: Boolean = false,
    val clippingSuspectedLow: Boolean = false
)

enum class RhythmPatternHint(val labelEs: String) {
    REGULAR("ritmo estable"),
    IRREGULAR("ritmo irregular (patrón no diagnóstico)"),
    SUSPECT_ARRHYTHMIA("irregularidad prolongada sospechosa — no es diagnóstico médico"),
    INSUFFICIENT_DATA("datos insuficientes")
}
