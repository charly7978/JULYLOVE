package com.julylove.medical.signal

/**
 * Anomalías detectables en la onda PPG
 */
enum class PPGAbnormality {
    MISSING_DICROTIC_NOTCH,      // Ausencia de notch dicrotico
    NO_DETECTABLE_PEAKS,       // No se detectan picos sistólicos
    HIGH_AMPLITUDE_VARIABILITY, // Variabilidad excesiva en amplitud
    PREMATURE_BEAT,            // Latido prematuro detectado
    MISSED_BEAT,               // Latido faltante
    ARRHYTHMIA_DETECTED,       // Arritmia detectada
    LOW_PERFUSION,             // Perfusión baja
    MOTION_ARTIFACT,           // Artefacto de movimiento
    ARTIFACT,                  // Artefacto general
    POOR_CONTACT_PRESSURE,     // Presión de contacto inadecuada
    SIGNAL_CLIPPING            // Señal saturada (clipping)
}
