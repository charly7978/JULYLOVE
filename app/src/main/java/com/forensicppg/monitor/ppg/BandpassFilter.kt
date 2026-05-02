package com.forensicppg.monitor.ppg

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Filtro biquad IIR (Direct Form II Transposed). Se cascadan dos biquads para
 * formar un pasa-banda Butterworth de orden 4 (2 de low-pass + 2 de high-pass),
 * con estado por muestra. Está optimizado: cada llamada a `process` no hace
 * allocations.
 *
 * Banda por defecto: 0.5 Hz – 4.0 Hz (cubre 30–240 BPM) a una frecuencia de
 * muestreo configurable.
 */
class BandpassFilter(
    private val sampleRate: Double,
    lowHz: Double = 0.5,
    highHz: Double = 4.0
) {
    private val hp: Biquad
    private val hp2: Biquad
    private val lp: Biquad
    private val lp2: Biquad

    init {
        require(sampleRate > 0.0)
        require(lowHz > 0.0 && highHz > lowHz && highHz < sampleRate / 2.0)
        hp = Biquad.designHighPass(lowHz, sampleRate, q = 0.7071)
        hp2 = Biquad.designHighPass(lowHz, sampleRate, q = 0.7071)
        lp = Biquad.designLowPass(highHz, sampleRate, q = 0.7071)
        lp2 = Biquad.designLowPass(highHz, sampleRate, q = 0.7071)
    }

    fun process(sample: Double): Double {
        var v = hp.process(sample)
        v = hp2.process(v)
        v = lp.process(v)
        v = lp2.process(v)
        return v
    }

    fun reset() {
        hp.reset(); hp2.reset(); lp.reset(); lp2.reset()
    }
}

/**
 * Biquad IIR Direct Form II Transposed.
 *
 *   y[n] = b0*x[n] + z1[n-1]
 *   z1[n] = b1*x[n] - a1*y[n] + z2[n-1]
 *   z2[n] = b2*x[n] - a2*y[n]
 */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    companion object {
        fun designLowPass(cutoffHz: Double, sampleRate: Double, q: Double = 0.7071): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = (1.0 - cosW) / 2.0 / a0
            val b1 = (1.0 - cosW) / a0
            val b2 = (1.0 - cosW) / 2.0 / a0
            val a1 = -2.0 * cosW / a0
            val a2 = (1.0 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        fun designHighPass(cutoffHz: Double, sampleRate: Double, q: Double = 0.7071): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = (1.0 + cosW) / 2.0 / a0
            val b1 = -(1.0 + cosW) / a0
            val b2 = (1.0 + cosW) / 2.0 / a0
            val a1 = -2.0 * cosW / a0
            val a2 = (1.0 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }
    }
}

/**
 * Variante equivalente con coeficientes pre-calculados (Butterworth orden 2
 * analógico → bilinear). Se deja como alternativa si se necesitara un Q
 * estrictamente Butterworth distinto del 1/sqrt(2) típico.
 */
@Suppress("unused")
object ButterworthCoeffs {
    fun lp(cutoff: Double, fs: Double): DoubleArray {
        val w = tan(PI * cutoff / fs)
        val norm = 1.0 / (1.0 + sqrt(2.0) * w + w * w)
        val b0 = w * w * norm
        val b1 = 2.0 * b0
        val b2 = b0
        val a1 = 2.0 * (w * w - 1.0) * norm
        val a2 = (1.0 - sqrt(2.0) * w + w * w) * norm
        return doubleArrayOf(b0, b1, b2, a1, a2)
    }
}
