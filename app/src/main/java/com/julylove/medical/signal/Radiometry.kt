package com.julylove.medical.signal

import kotlin.math.ln
import kotlin.math.pow

/**
 * sRGB → lineal (IEC 61966-2-1); cadena **ln(I / I₀)** con I₀ EMA lenta (proxy absorción DC Beer–Lambert en dominio log).
 * Offset oscuro opcional en [0,1] lineal por canal (mediana de frames con lente tapada / ambiente).
 */
object Radiometry {

    fun srgbByteToLinear(cs: Float): Float {
        val c = (cs / 255f).coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linear01WithDark(
        srgbMean: Float,
        darkLinear01: Float
    ): Float {
        return (srgbByteToLinear(srgbMean) - darkLinear01).coerceAtLeast(1e-7f)
    }

    /**
     * I₀(t) actualizado por EMA; salida **ln(I / I₀)** en verde (canal más estable en LED blanco sin IR).
     * [blendI0] = (1−α) en la mezcla I₀ ← I; a ~60 fps, 0.003–0.006 ≈ constante de tiempo ~0,5–1 s.
     */
    class OpticalDensityGreen(
        private val blendI0: Float = 0.0045f,
        private val scaleForDsp: Float = 320f
    ) {
        private var i0Green = -1f

        fun reset() {
            i0Green = -1f
        }

        /**
         * [linearGreen01] reflectancia lineal 0..1 ya corregida por dark offset.
         * Devuelve cantidad escalada para bandpass (mismo orden de magnitud que pipeline lineal previo).
         */
        fun pushScaledPulse(linearGreen01: Float): Float {
            val g = linearGreen01.coerceIn(1e-7f, 1f)
            if (i0Green < 0f) {
                i0Green = g
                return 0f
            }
            i0Green = (1f - blendI0) * i0Green + blendI0 * g
            val ratio = (g / i0Green.coerceAtLeast(1e-7f)).toDouble().coerceIn(0.45, 1.55)
            return (ln(ratio).toFloat() * scaleForDsp)
        }

        fun baselineGreen01(): Float = i0Green.coerceAtLeast(0f)
    }
}
