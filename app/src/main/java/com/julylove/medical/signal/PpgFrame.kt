package com.julylove.medical.signal

/**
 * PpgFrame - Contenedor de datos ópticos y espaciales por cada frame de cámara.
 * Representa la unidad básica de adquisición antes del procesamiento temporal.
 */
data class PpgFrame(
    val timestampNs: Long,
    val fpsEstimate: Float,
    
    // Medias sRGB crudas del ROI
    val avgRed: Float,
    val avgGreen: Float,
    val avgBlue: Float,
    
    // Componentes DC (Línea base corregida si aplica)
    val redDc: Float,
    val greenDc: Float,
    val blueDc: Float,
    
    // Componentes AC (Variación pulsátil estimada inicial)
    val redAc: Float,
    val greenAc: Float,
    val blueAc: Float,
    
    // Métricas Espaciales y de Calidad
    val saturationRatio: Float, // 0..1 (píxeles saturados / total)
    val darknessRatio: Float,   // 0..1 (píxeles negros / total)
    val skinLikelihood: Float,  // 0..1 basado en modelo cromático
    val redDominance: Float,    // R / (G+B)
    val greenPulseCandidate: Float, // Señal G normalizada
    val textureScore: Float,    // Uniformidad espacial
    val motionScore: Float,     // Variación inter-frame detectada
    
    // Señal Óptica Final
    val rawOpticalSignal: Float,
    val normalizedSignal: Float
)
