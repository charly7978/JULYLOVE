package com.julylove.medical.signal

/**
 * ButterworthBandpass: sección IIR de 2º orden (aprox. paso banda cardíaco ~0,5–5 Hz a Fs 30/60 Hz).
 * Objetivo: aislar componente pulsátil atenuando deriva lenta (respiración) y ruido HF (movimiento, cuantización).
 * Ver validación PPG smartphone (p. ej. Lee et al., Heart Rhythm 2012) y revisiones recientes (Tison et al., 2023–2024).
 * Coeficientes precalculados para dos rejillas de muestreo (≈30 y ≈60 fps); [updateCoefficients] sincroniza con FPS real.
 */
class ButterworthBandpass(sampleRate: Float = 60f) {
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    private var b = floatArrayOf(0f, 0f, 0f)
    private var a = floatArrayOf(1f, 0f, 0f)

    init {
        updateCoefficients(sampleRate)
    }

    fun updateCoefficients(rate: Float) {
        if (rate < 10f) return 
        
        if (rate < 45f) {
            // Coeficientes para ~30Hz
            b = floatArrayOf(0.0134f, 0f, -0.0134f)
            a = floatArrayOf(1f, -1.8227f, 0.8372f)
        } else {
            // Coeficientes para ~60Hz
            b = floatArrayOf(0.0036f, 0f, -0.0036f)
            a = floatArrayOf(1f, -1.9112f, 0.9150f)
        }
    }

    fun filter(x0: Float): Float {
        val y0 = b[0] * x0 + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2 = x1; x1 = x0
        y2 = y1; y1 = y0
        return y0
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }
}
