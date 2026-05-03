package com.forensicppg.monitor.ppg

import android.graphics.ImageFormat
import android.media.Image
import com.forensicppg.monitor.domain.ExposureDiagnostics
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.RoiChannelStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Valores vigentes tras [configureSensorZlo] (solo lectura fuera del analyzer). */
data class AppliedSensorZlo(
    val r: Double,
    val g: Double,
    val b: Double,
    val sourceBrief: String
)

/**
 * Extrae estadísticas físicas desde YUV 420. Aplica opcionalmente offsets tipo ZLO
 * (DC digital por canal antes del pulsátil, Wang et al. 2023 / adaptación campo).
 */
class PpgFrameAnalyzer(
    private val roiSelector: RoiSelector = RoiSelector(centerFraction = 0.58)
) {
    private val zloLock = Any()
    private var zloR = 0.0
    private var zloG = 0.0
    private var zloB = 0.0
    /** literatura|captura|manual|ninguno — brevemente para auditoría */
    private var zloDesc = "ninguno"

    private var prevMeanGreen = 0.0

    private val rb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val gb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val bb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)

    private val motionSmooth = AlphaSmoother(PpgAcquisitionTuning.MOTION_EMA_ALPHA)
    private var stableRoiFrames = 0
    private var roiFractionAdaptive: Double = PpgAcquisitionTuning.ROI_FRACTION_LOOSE

    fun configureSensorZlo(red: Double, green: Double, blue: Double, sourceBrief: String) {
        synchronized(zloLock) {
            zloR = red.coerceAtLeast(0.0)
            zloG = green.coerceAtLeast(0.0)
            zloB = blue.coerceAtLeast(0.0)
            zloDesc = sourceBrief.take(48)
        }
    }

    fun currentSensorZlo(): AppliedSensorZlo =
        synchronized(zloLock) {
            AppliedSensorZlo(zloR, zloG, zloB, zloDesc)
        }

    fun analyze(
        image: Image,
        monotonicRealtimeNs: Long,
        exposureDiagnostics: ExposureDiagnostics
    ): PpgSample? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        if (width <= 1 || height <= 1) return null
        val planes = image.planes
        if (planes.size < 3) return null

        val zr: Double
        val zg: Double
        val zb: Double
        val zBrief: String
        synchronized(zloLock) {
            zr = zloR
            zg = zloG
            zb = zloB
            zBrief = zloDesc
        }

        updateAdaptiveRoiFraction()
        val roi = roiSelector.pickRoi(width, height, roiFractionAdaptive)

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        var clipHighRgb = 0L
        var clipLowRgb = 0L
        var saturatedR = 0L
        var saturatedG = 0L
        var saturatedB = 0L
        var sumRPre = 0.0
        var sumGPre = 0.0
        var sumBPre = 0.0
        var sumRCorr = 0.0
        var sumGCorr = 0.0
        var sumBCorr = 0.0
        var sumY = 0L
        var sumY2 = 0L

        val step = if (roi.width > PpgAcquisitionTuning.ROI_SUBSAMPLE_WIDE_THRESHOLD_PX) 2 else 1
        var cnt = 0L
        var yPixRow = roi.y
        while (yPixRow < roi.y + roi.height) {
            val uvY = yPixRow / 2
            var xPix = roi.x
            while (xPix < roi.x + roi.width) {
                val uvX = xPix / 2
                val yIdx = yPixRow * yRow + xPix * yPix
                val uIdx = uvY * uRow + uvX * uPix
                val vIdx = uvY * vRow + uvX * vPix
                if (yIdx < 0 || yIdx >= yBuf.capacity() ||
                    uIdx < 0 || uIdx >= uBuf.capacity() ||
                    vIdx < 0 || vIdx >= vBuf.capacity()
                ) {
                    xPix += step
                    continue
                }
                val yv = yBuf.get(yIdx).toInt() and 0xFF
                val uu = (uBuf.get(uIdx).toInt() and 0xFF) - 128
                val vv = (vBuf.get(vIdx).toInt() and 0xFF) - 128

                val r = (yv + 1.402 * vv).toInt().coerceIn(0, 255)
                val g = (yv - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
                val b = (yv + 1.772 * uu).toInt().coerceIn(0, 255)

                if (yv >= 250 || r >= 250 || g >= 250 || b >= 250) clipHighRgb++
                if (yv <= 5 || r <= 5 || g <= 5 || b <= 5) clipLowRgb++
                if (r >= 246) saturatedR++
                if (g >= 246) saturatedG++
                if (b >= 246) saturatedB++

                val dr = (r.toDouble() - zr).coerceIn(0.12, 255.93)
                val dg = (g.toDouble() - zg).coerceIn(0.12, 255.93)
                val db = (b.toDouble() - zb).coerceIn(0.12, 255.93)
                val cr = dr.roundToInt().coerceIn(0, 255)
                val cg = dg.roundToInt().coerceIn(0, 255)
                val cb = db.roundToInt().coerceIn(0, 255)

                hr[cr]++; hg[cg]++; hb[cb]++

                sumRPre += r
                sumGPre += g
                sumBPre += b
                sumRCorr += dr
                sumGCorr += dg
                sumBCorr += db
                sumY += yv
                sumY2 += yv.toLong() * yv
                cnt++
                xPix += step
            }
            yPixRow += step
        }

        if (cnt <= 0L) return null

        val n = cnt.toDouble()
        val mrPre = sumRPre / n
        val mgPre = sumGPre / n
        val mbPre = sumBPre / n
        val mr = sumRCorr / n
        val mg = sumGCorr / n
        val mb = sumBCorr / n

        val medianR = percentileFromHist(hr, n, 0.5).toDouble()
        val medianG = percentileFromHist(hg, n, 0.5).toDouble()
        val medianB = percentileFromHist(hb, n, 0.5).toDouble()

        val yMean = sumY / n
        val roiVar = (sumY2 / n) - (yMean * yMean)

        rb.push(mr); gb.push(mg); bb.push(mb)
        val (rAc, rDc) = rb.peakPeakAndDc()
        val (gAc, gDc) = gb.peakPeakAndDc()
        val (bAc, bDc) = bb.peakPeakAndDc()

        val rAcDc = if (rDc > 1.0) rAc / rDc else 0.0
        val gAcDc = if (gDc > 1.0) gAc / gDc else 0.0
        val bAcDc = if (bDc > 1.0) bAc / bDc else 0.0

        val clipHR = clipHighRgb / n
        val clipLR = clipLowRgb / n
        val satR = saturatedR / n
        val satG = saturatedG / n
        val satB = saturatedB / n

        val diff = abs(mg - prevMeanGreen)
        prevMeanGreen = mg
        val motionRaw = diff / max(mg + 18.0, 18.0)
        val motionSmoothed = motionSmooth.update(motionRaw.coerceIn(0.0, 1.5))

        val lowLight = mg < 24.0 && clipLR < 0.35

        val redDominance = mr / max(mg + 1e-6, 1e-6)
        val greenPulsatility = gb.coefficientVariation()
        val blueStable = bb.stabilityScore()

        val perfusionGreenPct = kotlin.math.min(
            PpgAcquisitionTuning.GREEN_PERFUSION_SCALE,
            gAcDc * PpgAcquisitionTuning.GREEN_PERFUSION_SCALE
        )

        val roiStats = RoiChannelStats(
            roiLeft = roi.x,
            roiTop = roi.y,
            roiWidth = roi.width,
            roiHeight = roi.height,
            redMean = mr,
            greenMean = mg,
            blueMean = mb,
            redMedian = medianR,
            greenMedian = medianG,
            blueMedian = medianB,
            saturationR01 = satR.coerceIn(0.0, 1.0),
            saturationG01 = satG.coerceIn(0.0, 1.0),
            saturationB01 = satB.coerceIn(0.0, 1.0),
            redDominanceRg = redDominance,
            greenPulsatility = greenPulsatility,
            blueChannelStability = blueStable,
            perfusionIndexGreenPct = perfusionGreenPct,
            redAcDc = rAcDc,
            greenAcDc = gAcDc,
            blueAcDc = bAcDc,
            roiVarianceLuma = roiVar.coerceAtLeast(0.0)
        )

        val diagOut = exposureDiagnostics.copy(
            sensorZloR = zr.takeIf { it >= 1e-6 },
            sensorZloG = zg.takeIf { it >= 1e-6 },
            sensorZloB = zb.takeIf { it >= 1e-6 },
            zloSourceSummary = zBrief
        )

        return PpgSample(
            timestampNs = image.timestamp,
            monotonicRealtimeNs = monotonicRealtimeNs,
            roiMeanPreZloRed = mrPre,
            roiMeanPreZloGreen = mgPre,
            roiMeanPreZloBlue = mbPre,
            rawRed = mr,
            rawGreen = mg,
            rawBlue = mb,
            filteredPrimary = mg,
            displayWave = mg,
            roiStats = roiStats,
            clippingHighRatio = clipHR,
            clippingLowRatio = clipLR,
            motionScoreOptical = motionSmoothed,
            exposureDiagnostics = diagOut,
            lowLightSuspected = lowLight,
            sqi = 0.0,
            filteredSecondary = mr
        )
    }

    private fun updateAdaptiveRoiFraction() {
        val m = motionSmooth.value
        if (m < PpgAcquisitionTuning.MOTION_STABLE_GATE) stableRoiFrames++ else stableRoiFrames = 0
        roiFractionAdaptive =
            if (stableRoiFrames > PpgAcquisitionTuning.MOTION_STABLE_FRAMES_NEED) {
                PpgAcquisitionTuning.ROI_FRACTION_TIGHT
            } else {
                PpgAcquisitionTuning.ROI_FRACTION_LOOSE
            }
        roiFractionAdaptive = roiFractionAdaptive.coerceIn(0.44, 0.66)
    }

    companion object {
        private fun percentileFromHist(hist: IntArray, total: Double, p: Double): Int {
            val target = (total * p).toLong().coerceAtLeast(0L)
            var cum = 0L
            for (i in hist.indices) {
                cum += hist[i].toLong()
                if (cum >= target) return i
            }
            return 255
        }
    }

    private class AlphaSmoother(private val momentum: Double) {
        private var initialized = false
        var value = 0.0
            private set

        fun update(x: Double): Double {
            if (!initialized) {
                value = x
                initialized = true
                return value
            }
            value = momentum * value + (1.0 - momentum) * x
            return value
        }
    }

    private class Rolling(private val capacity: Int) {
        private val q = ArrayDeque<Double>(capacity + 2)

        fun push(v: Double) {
            q.addLast(v)
            while (q.size > capacity) q.removeFirst()
        }

        private fun valuesSnapshot(): DoubleArray =
            DoubleArray(q.size) { qi -> q.elementAt(qi) }

        fun peakPeakAndDc(): Pair<Double, Double> {
            val arr = valuesSnapshot()
            val nEff = arr.size
            if (nEff == 0) return 0.0 to 0.0
            var mx = Double.NEGATIVE_INFINITY
            var mn = Double.POSITIVE_INFINITY
            var sum = 0.0
            for (x in arr) {
                mx = max(mx, x)
                mn = min(mn, x)
                sum += x
            }
            val dc = sum / nEff
            return (mx - mn).coerceAtLeast(0.0) to dc
        }

        fun coefficientVariation(): Double {
            val arr = valuesSnapshot()
            val nEff = arr.size
            if (nEff <= 2) return 0.0
            var mu = 0.0
            for (x in arr) mu += x
            mu /= nEff
            var v = 0.0
            for (x in arr) {
                val d = x - mu
                v += d * d
            }
            v /= (nEff - 1).coerceAtLeast(1)
            val sd = sqrt(v.coerceAtLeast(1e-9))
            return (sd / max(abs(mu).coerceAtLeast(1e-3), 1e-3)).coerceIn(0.0, 4.0)
        }

        fun stabilityScore(): Double {
            val cv = coefficientVariation()
            return (1.0 - cv / 6.0).coerceIn(0.05, 1.0)
        }
    }
}
