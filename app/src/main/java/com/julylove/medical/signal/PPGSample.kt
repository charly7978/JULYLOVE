package com.julylove.medical.signal

/**
 * PPGSample - Muestra de señal fotopletismográfica
 * 
 * Contiene datos completos de la señal PPG para análisis médico-forense:
 * - Valores brutos de canales RGB
 * - Valores filtrados y procesados
 * - Puntos fiduciales detectados
 * - Calidad de señal
 */
data class PPGSample(
    val timestamp: Long,           // Timestamp en nanosegundos
    val redMean: Float,              // Intensidad promedio canal rojo
    val greenMean: Float,            // Intensidad promedio canal verde
    val blueMean: Float,             // Intensidad promedio canal azul
    val filteredValue: Float = 0f,   // Valor después de filtrado pasabanda
    val isPeak: Boolean = false,     // Es un pico sistólico detectado
    val sqi: Float = 0f,             // Signal Quality Index (0-1)
    
    // Nuevos campos para análisis forense completo
    val dcComponent: Float = 0f,       // Componente DC (línea base)
    val acComponent: Float = 0f,       // Componente AC (pulso)
    val perfusionIndex: Float = 0f,  // Relación AC/DC
    val fingerDetected: Boolean = false, // Detección real de presencia de dedo
    val isValley: Boolean = false,   // Es un valle (mínimo entre picos)
    val hasDicroticNotch: Boolean = false, // Tiene notch dicrotico
    val confidence: Float = 0f       // Confianza de detección (0-1)
)
