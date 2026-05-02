package com.julylove.medical.signal

/**
 * Tipos de onda PPG detectados
 */
enum class WaveType {
    NO_SIGNAL,              // Sin señal o señal plana
    NORMAL_TRIFFASIC,       // Onda normal con pico sistólico, notch, pico diastólico
    NORMAL_BIFFASIC,        // Onda con pico sistólico y valle
    NORMAL,                 // Onda normal (genérica)
    ACCEPTABLE,             // Onda aceptable
    WEAK,                   // Onda débil
    IRREGULAR,              // Onda irregular
    ABNORMAL,               // Onda anormal
    ABNORMAL_MORPHOLOGY,    // Morfología atípica
    ARTIFACT_RAPID_PEAKS,   // Picos demasiado frecuentes (artefacto)
    DAMPED_WAVEFORM,        // Onda amortiguada (presión excesiva)
    FLAT_WAVEFORM           // Onda plana (presión insuficiente)
}
