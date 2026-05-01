package com.julylove.medical.signal

/**
 * Robust finger detection based on RGB intensity and stability.
 * Real medical sensors use infrared, here we use the red channel dominance 
 * and mean brightness to ensure the camera is covered.
 */
class FingerDetectionEngine {

    enum class FingerState {
        NO_DEDO,
        POSICIONANDO,
        PRESION_EXCESIVA,
        SENAL_VALIDA
    }

    private var averageRedHistory = mutableListOf<Float>()

    fun analyze(red: Float, green: Float, blue: Float): FingerState {
        // Basic heuristic: when a finger covers the lens with flash,
        // the red channel dominates and intensity is high but not clipped.
        
        averageRedHistory.add(red)
        if (averageRedHistory.size > 30) averageRedHistory.removeAt(0)

        val avgRed = averageRedHistory.average().toFloat()
        
        return when {
            red < 50 -> FingerState.NO_DEDO // Too dark
            red > 250 -> FingerState.PRESION_EXCESIVA // Too bright / Clipped
            red > (green + blue) * 1.5f -> FingerState.SENAL_VALIDA
            else -> FingerState.POSICIONANDO
        }
    }
}
