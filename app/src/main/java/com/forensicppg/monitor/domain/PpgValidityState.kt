package com.forensicppg.monitor.domain

/**
 * Clasificación fisiológica de la ventana observada — no bloquea la captura
 * óptica: la UI indica nivel de evidencia disponible para métricas biomédicas.
 */
enum class PpgValidityState(val labelEs: String) {
    RAW_OPTICAL_ONLY("ÓPTICO CRUDO"),
    NO_PHYSIOLOGICAL_SIGNAL("SIN COMPONENTE FISIOLÓGICO CONFIRMADO"),
    PPG_CANDIDATE("PPG CANDIDATO"),
    PPG_VALID("PPG VÁLIDO"),
    BIOMETRIC_VALID("BIOMÉTRICO VÁLIDO")
}
