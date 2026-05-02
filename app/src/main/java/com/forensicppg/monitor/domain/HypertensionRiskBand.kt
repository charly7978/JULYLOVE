package com.forensicppg.monitor.domain

enum class HypertensionRiskBand(
    val labelEs: String,
    val descEs: String
) {
    UNCERTAIN("Indeterminado", "RR y SQI insuficientes — no es diagnóstico"),
    NORMOTENSE("Normotenso (patrón)", "Coherencia estable de HRV — sólo patrón fisiológico"),
    BORDERLINE("Patrón limítrofe", "Variabilidad y eje temporal en zona intermedia — vigilancia no clínica"),
    HYPERTENSIVE_PATTERN("Patrón tensión elevada (heurístico)", "Indicadores compatible con rigidez arterial heurística — NO diagnóstico")
}
