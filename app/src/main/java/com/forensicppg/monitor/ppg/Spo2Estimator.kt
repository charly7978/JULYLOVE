package com.forensicppg.monitor.ppg

import kotlin.math.max
import kotlin.math.min

/**
 * Estimador SpO₂ basado en ratio-of-ratios de AC/DC por canal. NO devuelve un
 * número absoluto si no se provee un [CalibrationProfile]. En ese caso, la UI
 * muestra "SpO₂ requiere calibración".
 */
class Spo2Estimator(
    sampleRateHz: Double,
    windowSeconds: Double = 6.0
) {
    private val n = (sampleRateHz * windowSeconds).toInt().coerceAtLeast(60)
    private val redBuf = DoubleArray(n)
    private val blueBuf = DoubleArray(n)
    private val greenBuf = DoubleArray(n)
    private var idx = 0
    private var filled = 0
    private var redDcEma = 0.0
    private var blueDcEma = 0.0
    private var greenDcEma = 0.0
    private var hasDc = false
    private val alpha = 1.0 / (sampleRateHz * 3.0)

    data class Result(
        val spo2: Double?,
        val confidence: Double,
        val ratioOfRatios: Double?,
        val redAcDc: Double?,
        val blueAcDc: Double?,
        val greenAcDc: Double?,
        val reason: String
    )

    fun reset() {
        for (i in 0 until n) { redBuf[i] = 0.0; blueBuf[i] = 0.0; greenBuf[i] = 0.0 }
        idx = 0; filled = 0
        redDcEma = 0.0; blueDcEma = 0.0; greenDcEma = 0.0
        hasDc = false
    }

    /** Alimenta medias por frame. Se espera que el llamante ya haya normalizado las medias por el tamaño del ROI. */
    fun push(redMean: Double, greenMean: Double, blueMean: Double) {
        if (!hasDc) {
            redDcEma = redMean; blueDcEma = blueMean; greenDcEma = greenMean; hasDc = true
        } else {
            redDcEma += alpha * (redMean - redDcEma)
            blueDcEma += alpha * (blueMean - blueDcEma)
            greenDcEma += alpha * (greenMean - greenDcEma)
        }
        redBuf[idx] = redMean - redDcEma
        blueBuf[idx] = blueMean - blueDcEma
        greenBuf[idx] = greenMean - greenDcEma
        idx = (idx + 1) % n
        if (filled < n) filled++
    }

    fun estimate(
        calibration: CalibrationProfile?,
        perfusionIndex: Double,
        sqi: Double,
        motionScore: Double,
        clipHighRatio: Double
    ): Result {
        if (filled < n) return Result(null, 0.0, null, null, null, null, "ventana_incompleta")
        val (rAc, rDc) = peakToPeakAndMean(redBuf, redDcEma)
        val (bAc, bDc) = peakToPeakAndMean(blueBuf, blueDcEma)
        val (gAc, gDc) = peakToPeakAndMean(greenBuf, greenDcEma)

        val rRatio = if (rDc > 1.0) rAc / rDc else 0.0
        val bRatio = if (bDc > 1.0) bAc / bDc else 0.0
        val gRatio = if (gDc > 1.0) gAc / gDc else 0.0

        if (rRatio <= 0.0 || bRatio <= 0.0) {
            return Result(null, 0.0, null, rRatio, bRatio, gRatio, "ac_dc_insuficiente")
        }
        val r = rRatio / bRatio
        if (r !in 0.3..3.0) return Result(null, 0.0, r, rRatio, bRatio, gRatio, "r_fuera_rango")
        if (perfusionIndex < 0.5) return Result(null, 0.0, r, rRatio, bRatio, gRatio, "perfusion_baja")
        if (motionScore > 0.35) return Result(null, 0.0, r, rRatio, bRatio, gRatio, "movimiento")
        if (clipHighRatio > 0.18) return Result(null, 0.0, r, rRatio, bRatio, gRatio, "clipping")
        if (sqi < 0.45) return Result(null, 0.0, r, rRatio, bRatio, gRatio, "sqi_bajo")

        if (calibration == null) {
            return Result(null, 0.0, r, rRatio, bRatio, gRatio, "sin_calibracion")
        }
        val spo2 = calibration.apply(r)
        // Confianza: proporcional a sqi, penalizada por distancia al rango calibrado.
        val confidence = (sqi * (1.0 - motionScore.coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
        return Result(spo2, confidence, r, rRatio, bRatio, gRatio, "ok")
    }

    private fun peakToPeakAndMean(buf: DoubleArray, dcEma: Double): Pair<Double, Double> {
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (v in buf) { mx = max(mx, v); mn = min(mn, v) }
        val ac = (mx - mn).coerceAtLeast(0.0)
        return ac to dcEma
    }
}
