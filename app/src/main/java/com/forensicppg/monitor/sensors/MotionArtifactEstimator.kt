package com.forensicppg.monitor.sensors

import kotlin.math.abs

/**
 * Estima un "motion score" en [0,1] a partir de muestras crudas del acelerómetro.
 * Criterios:
 *   - varianza en ventana corta (1 s) de la magnitud.
 *   - diferencial respecto a la gravedad (~9.81 m/s²).
 *   - picos abruptos (golpes).
 *
 * El score es 0.0 cuando el teléfono está quieto y 1.0 cuando el movimiento
 * es claramente disruptivo para PPG.
 */
class MotionArtifactEstimator(
    windowSize: Int = 50
) {
    private val win = DoubleArray(windowSize)
    private var idx = 0
    private var filled = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private var lastMag = 9.81f
    private var lastSpike = 0.0

    fun reset() {
        for (i in win.indices) win[i] = 0.0
        idx = 0; filled = 0; sum = 0.0; sumSq = 0.0
        lastMag = 9.81f; lastSpike = 0.0
    }

    fun push(sample: MotionSample): Double {
        val mag = sample.accelMagnitude.toDouble()
        val delta = mag - lastMag
        if (abs(delta) > 3.5) lastSpike = kotlin.math.min(1.0, abs(delta) / 12.0)
        lastSpike *= 0.92
        lastMag = mag.toFloat()

        val old = win[idx]
        win[idx] = mag
        sum += mag - old
        sumSq += mag * mag - old * old
        idx = (idx + 1) % win.size
        if (filled < win.size) filled++

        val mean = sum / filled
        val variance = (sumSq / filled) - mean * mean
        val std = kotlin.math.sqrt(variance.coerceAtLeast(0.0))
        val stdScore = (std / 2.5).coerceIn(0.0, 1.0)
        return (0.75 * stdScore + 0.35 * lastSpike).coerceIn(0.0, 1.0)
    }
}
