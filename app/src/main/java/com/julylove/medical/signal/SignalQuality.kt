package com.julylove.medical.signal

/**
 * Niveles de calidad de señal PPG para análisis médico-forense
 */
enum class SignalQuality {
    NO_SIGNAL,      // No hay dedo o señal completamente ausente
    POOR,           // Señal muy débil, no usable
    ACCEPTABLE,     // Señal usable con precaución
    GOOD,           // Buena señal, confiable
    EXCELLENT       // Señal óptima, todos los puntos fiduciales visibles
}
