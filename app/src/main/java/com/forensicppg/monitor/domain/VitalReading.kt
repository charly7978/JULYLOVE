package com.forensicppg.monitor.domain

/**
 * Lectura vital consolidada que se muestra al usuario.
 *
 * Todos los campos numéricos son `Double?` y son `null` cuando la evidencia real
 * no alcanza el mínimo clínico. La UI jamás debe inventar un valor por default.
 */
data class VitalReading(
    val bpm: Double? = null,
    val bpmConfidence: Double = 0.0,
    val spo2: Double? = null,
    val spo2Confidence: Double = 0.0,
    val sqi: Double = 0.0,
    val perfusionIndex: Double = 0.0,
    val motionScore: Double = 0.0,
    val rrMs: Double? = null,
    val rrSdnnMs: Double? = null,
    val pnn50: Double? = null,
    val beatsDetected: Int = 0,
    val abnormalBeats: Int = 0,
    val state: MeasurementState = MeasurementState.NO_CONTACT,
    val validityFlags: Int = ReadingValidity.NO_FINGER,
    val message: String = "Coloque el dedo sobre la cámara trasera",
    val hypertensionRisk: HypertensionRiskBand? = null
) {
    val isValid: Boolean get() = state.allowsMetrics
}

/**
 * Banda de riesgo de hipertensión. Basado exclusivamente en morfología PPG y
 * datos opto-fisiológicos reales. No produce valores numéricos de presión
 * sistólica/diastólica para no caer en la simulación; sigue la recomendación
 * clínica de detección temprana de hipertensión en lugar de estimar valores
 * absolutos.
 */
enum class HypertensionRiskBand(val labelEs: String, val descEs: String) {
    NORMOTENSE(
        "NORMOTENSO",
        "Patrón compatible con rango normal"
    ),
    BORDERLINE(
        "LIMÍTROFE",
        "Patrón PPG compatible con rango elevado — verificar con médico"
    ),
    HYPERTENSIVE_PATTERN(
        "PATRÓN HIPERTENSIVO",
        "Morfología PPG compatible con hipertensión — consultar médico"
    ),
    UNCERTAIN(
        "INDETERMINADO",
        "Evidencia insuficiente para cribado"
    );
}
