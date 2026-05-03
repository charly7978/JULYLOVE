package com.forensicppg.monitor.ppg

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Estimador SpO₂ por ratio-of-ratios (RoR) sobre cámara con flash blanco.
 *
 * Modelo físico:
 *
 *   Bajo iluminación blanca, la absorción depende del coeficiente molar
 *   ε(λ) de HbO₂ y Hb. Para el smartphone (LED blanco como fuente, sensor
 *   RGB Bayer como detector) el ratio canónico para fuentes blancas es
 *
 *        R = (AC_red / DC_red) / (AC_green / DC_green)
 *
 *   El verde es el canal donde la hemoglobina absorbe FUERTEMENTE
 *   (ε_Hb 540 nm ≫ ε_Hb 660 nm), de modo que actúa como referencia de
 *   absorción. El AZUL es prácticamente plano bajo LED blanco contra
 *   piel y NO sirve como denominador (hay literatura que lo prueba:
 *   Scully 2012, Lamonaca 2017, Wieringa 2005, Ding 2018).
 *
 *   Antes este código usaba `R/B` y por eso era casi imposible obtener
 *   SpO₂. Cambiamos a `R/G`, que es la formulación correcta.
 *
 * Calibración:
 *
 *   - Sin perfil: SpO₂ = A − B·R con (A, B) = (110, 25) (literatura
 *     básica). Marcado como provisional con confianza ≤ 0.55 (no
 *     clínico). El usuario ahora ya puede VER un número aproximado
 *     mientras calibra contra un oxímetro de referencia.
 *   - Con perfil ajustado por mínimos cuadrados: usa (A, B) ajustados.
 *
 * Suavizado de salida: EMA τ ≈ 3 s.
 *
 * Gates: perfusión, clipping alto/bajo, movimiento, SQI mínimo.
 */
class Spo2Estimator(
    sampleRateHz: Double,
    windowSeconds: Double = 6.0
) {
    private val sr = sampleRateHz.coerceIn(15.0, 90.0)
    private val n = (sr * windowSeconds).toInt().coerceAtLeast(60)
    private val redBuf = DoubleArray(n)
    private val greenBuf = DoubleArray(n)
    private val blueBuf = DoubleArray(n)
    private var idx = 0
    private var filled = 0
    private var redDcEma = 0.0
    private var greenDcEma = 0.0
    private var blueDcEma = 0.0
    private var hasDc = false
    private val alphaDc = 1.0 / (sr * 3.0)
    private val emaAlpha = 1.0 - exp(-1.0 / (sr * 3.0))
    private var spo2Ema: Double? = null

    private val ratioWin = ArrayDeque<Double>(96)
    private var lastMeasuredRatioR: Double? = null

    data class Result(
        val spo2Clinical: Double?,
        val spo2Confidence: Double,
        val ratioOfRatios: Double?,
        val redAcDc: Double?,
        val blueAcDc: Double?,
        val greenAcDc: Double?,
        val reasonCode: String,
        val clinicallyValidDisplay: Boolean
    )

    fun reset() {
        for (i in 0 until n) {
            redBuf[i] = 0.0; greenBuf[i] = 0.0; blueBuf[i] = 0.0
        }
        idx = 0; filled = 0
        redDcEma = 0.0; greenDcEma = 0.0; blueDcEma = 0.0
        hasDc = false
        ratioWin.clear()
        lastMeasuredRatioR = null
        spo2Ema = null
    }

    fun snapshotLastRatio(): Double? = lastMeasuredRatioR

    fun push(redMean: Double, greenMean: Double, blueMean: Double) {
        if (!hasDc) {
            redDcEma = redMean; greenDcEma = greenMean; blueDcEma = blueMean
            hasDc = true
        } else {
            redDcEma += alphaDc * (redMean - redDcEma)
            greenDcEma += alphaDc * (greenMean - greenDcEma)
            blueDcEma += alphaDc * (blueMean - blueDcEma)
        }
        // Buffers AC (centrados con DC EMA).
        redBuf[idx] = redMean - redDcEma
        greenBuf[idx] = greenMean - greenDcEma
        blueBuf[idx] = blueMean - blueDcEma
        idx = (idx + 1) % n
        if (filled < n) filled++
    }

    fun estimate(
        calibration: CalibrationProfile?,
        validityStateAllowsOximetry: Boolean,
        perfusionIndex: Double,
        sqi: Double,
        motionScore: Double,
        clipHighRatio: Double
    ): Result {
        if (filled < (n * 0.6).toInt()) {
            return Result(null, 0.0, null, null, null, null, "ventana_incompleta", false)
        }

        val rAc = peakToPeak(redBuf, filled)
        val gAc = peakToPeak(greenBuf, filled)
        val bAc = peakToPeak(blueBuf, filled)

        val rRatio = if (redDcEma > 1.0) rAc / redDcEma else 0.0
        val gRatio = if (greenDcEma > 1.0) gAc / greenDcEma else 0.0
        val bRatio = if (blueDcEma > 1.0) bAc / blueDcEma else 0.0

        if (rRatio <= 0.0 || gRatio <= 0.0) {
            return Result(null, 0.0, null, rRatio, bRatio, gRatio, "ac_dc_insuficiente", false)
        }

        // Ratio canónico R/G para flash blanco.
        val ratio = rRatio / gRatio
        ratioWin.addLast(ratio)
        while (ratioWin.size > 90) ratioWin.removeFirst()
        val rMedian = median(ratioWin)
        lastMeasuredRatioR = rMedian

        if (rMedian !in 0.25..3.5) {
            return Result(null, 0.0, rMedian, rRatio, bRatio, gRatio, "r_fuera_rango", false)
        }

        // Gates de calidad calibrados a la realidad de smartphone.
        // Perfusión verde típica con dedo apoyado: 0.3 – 6 (en %).
        if (perfusionIndex < 0.20) {
            return Result(null, 0.0, rMedian, rRatio, bRatio, gRatio, "perfusion_baja", false)
        }
        if (motionScore > 0.50) {
            return Result(null, 0.0, rMedian, rRatio, bRatio, gRatio, "movimiento_excesivo", false)
        }
        if (clipHighRatio > 0.30) {
            return Result(null, 0.0, rMedian, rRatio, bRatio, gRatio, "clipping_alto", false)
        }
        if (sqi < 0.20) {
            return Result(null, 0.0, rMedian, rRatio, bRatio, gRatio, "sqi_bajo", false)
        }

        // Curva empírica. Con calibración: A,B ajustados.
        val A = calibration?.coefficientA ?: 110.0
        val B = calibration?.coefficientB ?: 25.0
        val raw = A - B * rMedian
        val clamped = raw.coerceIn(70.0, 100.0)

        // EMA temporal de salida.
        spo2Ema = if (spo2Ema == null) clamped else spo2Ema!! + emaAlpha * (clamped - spo2Ema!!)

        val baseConf = (sqi * (1.0 - motionScore.coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
        val conf = if (calibration != null) baseConf else min(0.55, baseConf)

        val clinicallyValid =
            calibration != null &&
                calibration.calibrationSamples >= 3 &&
                rMedian in 0.40..3.0 &&
                conf >= 0.40 &&
                validityStateAllowsOximetry

        return Result(
            spo2Clinical = spo2Ema,
            spo2Confidence = conf,
            ratioOfRatios = rMedian,
            redAcDc = rRatio,
            blueAcDc = bRatio,
            greenAcDc = gRatio,
            reasonCode = if (calibration == null) "provisional_no_clinico" else "ok",
            clinicallyValidDisplay = clinicallyValid
        )
    }

    private fun peakToPeak(buf: DoubleArray, len: Int): Double {
        if (len <= 0) return 0.0
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        // Si no se ha llenado completo todavía, considerar sólo `len` elementos
        // empezando desde el más antiguo (no es estrictamente necesario; basta
        // con escanear todo el buffer cuando `filled >= n`, pero esto evita
        // contar ceros iniciales como mínimos).
        val effective = if (len >= buf.size) buf.size else len
        for (i in 0 until effective) {
            val v = buf[i]
            if (v > mx) mx = v
            if (v < mn) mn = v
        }
        return max(0.0, mx - mn)
    }

    private fun median(arr: ArrayDeque<Double>): Double {
        if (arr.isEmpty()) return 0.0
        val s = arr.toMutableList().apply { sort() }
        val n = s.size
        return if (n % 2 == 0) (s[n / 2 - 1] + s[n / 2]) / 2.0 else s[(n - 1) / 2]
    }
}
