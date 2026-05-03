package com.forensicppg.monitor.domain

/**
 * Estado evidencial de la ventana (orden: peor → mejor para comparación `>=`).
 */
enum class PpgValidityState(val labelEs: String) {
    SEARCHING("BUSCANDO SEÑAL"),
    BAD_CONTACT("CONTACTO INSUFICIENTE"),
    LOW_LIGHT("LUZ BAJA / FLASH ÚTIL"),
    CLIPPING("SATURACIÓN / RECORTE"),
    MOTION("MOVIMIENTO"),
    LOW_PERFUSION("PERFUSIÓN BAJA"),
    QUIET_NO_PULSE("QUIETO SIN PULSO"),
    RAW_OPTICAL_ONLY("ÓPTICO CRUDO"),
    NO_PHYSIOLOGICAL_SIGNAL("SIN PPG (NO_PPG)"),
    PPG_CANDIDATE("PPG CANDIDATO"),
    PPG_VALID("PPG CONFIRMADO"),
    BIOMETRIC_VALID("BIOMÉTRICO VÁLIDO")
}
