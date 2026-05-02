package com.forensicppg.monitor.ppg

import kotlin.math.max
import kotlin.math.min

/**
 * Ratio-of-ratios (R) real por AC/DC. SpO₂ sólo aparece cuando un [CalibrationProfile] existe —
 * caso contrario se comunica sólo índices experimentales sobre R sin convertirlas a valores clínicos.
 */
class Spo2Estimator(
    sampleRateHz: Double,
    windowSeconds: Double = 10.0
) {
    private val n = (sampleRateHz * windowSeconds).toInt().coerceAtLeast(90)
    private val redBuf = DoubleArray(n)
    private val blueBuf = DoubleArray(n)
    private val greenBuf = DoubleArray(n)
    private var idx = 0
    private var filled = 0
    private var redDcEma = 0.0
    private var blueDcEma = 0.0
    private var greenDcEma = 0.0
    private var hasDc = false
    private val alpha = 1.0 / (sampleRateHz * 3.5)

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
            redBuf[i] = 0.0; blueBuf[i] = 0.0; greenBuf[i] = 0.0
        }
        idx = 0; filled = 0
        redDcEma = 0.0; blueDcEma = 0.0; greenDcEma = 0.0
        hasDc = false
        lastMeasuredRatioR = null
    }

    fun snapshotLastRatio(): Double? = lastMeasuredRatioR

    fun push(redMean: Double, greenMean: Double, blueMean: Double) {
        if (!hasDc) {
            redDcEma = redMean; blueDcEma = blueMean; greenDcEma = greenMean
            hasDc = true
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
        validityStateAllowsOximetry: Boolean,
        perfusionIndex: Double,
        sqi: Double,
        motionScore: Double,
        clipHighRatio: Double
    ): Result {
        if (filled < n)
            return Result(null, 0.0, null, null, null, null, "ventana_incompleta", false)

        val (rAc, rDc) = peakToPeakAndMean(redBuf, redDcEma)
        val (bAc, bDc) = peakToPeakAndMean(blueBuf, blueDcEma)
        val (gAc, gDc) = peakToPeakAndMean(greenBuf, greenDcEma)

        val rRatio = if (rDc > 1.0) rAc / rDc else 0.0
        val bRatio = if (bDc > 1.0) bAc / bDc else 0.0
        val gRatio = if (gDc > 1.0) gAc / gDc else 0.0

        if (rRatio <= 0.0 || bRatio <= 0.0) {
            return Result(null, 0.0, null, rRatio, bRatio, gRatio, "ac_dc_insuficiente", false)
        }
        val rratio = rRatio / bRatio
        lastMeasuredRatioR = rratio

        if (rratio !in 0.35..4.9) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "r_fuera_rango_estudio", false)
        if (!validityStateAllowsOximetry) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "clasificacion_baja", false)
        if (perfusionIndex < 0.43) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "perfusion_baja", false)
        if (motionScore > 0.41) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "movimiento_excesivo", false)
        if (clipHighRatio > 0.22) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "clip", false)
        if (sqi < 0.47) return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "sqi_bajo", false)

        if (calibration == null) {
            return Result(null, 0.0, rratio, rRatio, bRatio, gRatio, "sin_calibracion_absoluta", false)
        }

        val spo = calibration.apply(rratio)
        val conf = (sqi * (1.0 - motionScore.coerceIn(0.0, 1.0))).coerceIn(0.0, 1.0)
        val clinicalValid = rratio in 0.45..3.7 && calibration.calibrationSamples >= 3 && conf >= 0.35
        return Result(
            spo2Clinical = if (clinicalValid) spo else null,
            spo2Confidence = conf,
            ratioOfRatios = rratio,
            redAcDc = rRatio,
            blueAcDc = bRatio,
            greenAcDc = gRatio,
            reasonCode = if (clinicalValid) "spo2_disp_con_calibracion" else "spo2_precaucion_sin_confianza",
            clinicallyValidDisplay = clinicalValid && conf >= 0.40
        )
    }

    private fun peakToPeakAndMean(buf: DoubleArray, dcEma: Double): Pair<Double, Double> {
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (v in buf) {
            mx = max(mx, v); mn = min(mn, v)
        }
        val ac = (mx - mn).coerceAtLeast(0.0)
        return ac to dcEma
    }
}
