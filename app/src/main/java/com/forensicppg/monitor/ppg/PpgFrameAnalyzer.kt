package com.forensicppg.monitor.ppg

import android.graphics.ImageFormat
import android.media.Image
import com.forensicppg.monitor.domain.ExposureDiagnostics
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.RoiChannelStats
import kotlin.math.abs
import kotlin.math.ln
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
 * Extrae estadísticas físicas desde YUV 420: máscara de dedo, ROI contraído,
 * estadísticas robustas y absorbancia relativa. Devuelve **null** si no hay
 * contacto óptico válido (no muestras “débiles”).
 */
class PpgFrameAnalyzer(
    private val roiSelector: RoiSelector = RoiSelector(centerFraction = 0.62)
) {
    private val zloLock = Any()
    private var zloR = 0.0
    private var zloG = 0.0
    private var zloB = 0.0
    private var zloDesc = "ninguno"

    private var prevMeanGreen = 0.0
    private val rb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val gb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val bb = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val dcGreenAbs = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val dcRedAbs = Rolling(PpgAcquisitionTuning.CHANNEL_ROLLING_CAPACITY)
    private val motionSmooth = AlphaSmoother(PpgAcquisitionTuning.MOTION_EMA_ALPHA)
    private var stableRoiFrames = 0
    private var roiFractionAdaptive: Double = PpgAcquisitionTuning.ROI_FRACTION_LOOSE

    private var prevFrameTsNs: Long? = null
    private val fpsSmooth = AlphaSmoother(0.82)

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
        val initialRoi = roiSelector.pickRoi(width, height, roiFractionAdaptive, 0.0, 0.0)

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

        val step = if (initialRoi.width > PpgAcquisitionTuning.ROI_SUBSAMPLE_WIDE_THRESHOLD_PX) 2 else 1

        var maskCount = 0L
        var totalPix = 0L
        var minMx = width
        var minMy = height
        var maxMx = 0
        var maxMy = 0
        var sumCx = 0.0
        var sumCy = 0.0

        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)

        var clipHighRgb = 0L
        var clipLowRgb = 0L
        var saturatedR = 0L
        var saturatedG = 0L
        var saturatedB = 0L

        var sumY = 0L
        var sumY2 = 0L

        var sumMaskRPre = 0.0
        var sumMaskGPre = 0.0
        var sumMaskBPre = 0.0
        var sumMaskRCorr = 0.0
        var sumMaskGCorr = 0.0
        var sumMaskBCorr = 0.0

        var yPixRow = initialRoi.y
        while (yPixRow < initialRoi.y + initialRoi.height) {
            var xPix = initialRoi.x
            while (xPix < initialRoi.x + initialRoi.width) {
                totalPix++
                val rgb = yuvToRgb(
                    xPix, yPixRow, yBuf, uBuf, vBuf,
                    yRow, yPix, uRow, uPix, vRow, vPix
                )
                if (rgb == null) {
                    xPix += step
                    continue
                }
                val yv = rgb.y.toInt()
                val r = rgb.r
                val g = rgb.g
                val b = rgb.b

                val ch = yv >= 250 || r >= 250 || g >= 250 || b >= 250
                val cl = yv <= 8 || r <= 5 || g <= 5 || b <= 5
                if (ch) clipHighRgb++
                if (cl) clipLowRgb++

                if (r >= 246) saturatedR++
                if (g >= 246) saturatedG++
                if (b >= 246) saturatedB++

                val dr = (r.toDouble() - zr).coerceIn(0.0, 255.9)
                val dg = (g.toDouble() - zg).coerceIn(0.0, 255.9)
                val db = (b.toDouble() - zb).coerceIn(0.0, 255.9)
                val cr = dr.roundToInt().coerceIn(0, 255)
                val cg = dg.roundToInt().coerceIn(0, 255)
                val cb = db.roundToInt().coerceIn(0, 255)

                sumY += yv
                sumY2 += yv.toLong() * yv

                val finger = isFingerMaskPixel(yv, r, g, b, ch, cl)
                if (finger) {
                    maskCount++
                    sumMaskRPre += r.toDouble()
                    sumMaskGPre += g.toDouble()
                    sumMaskBPre += b.toDouble()
                    sumMaskRCorr += dr
                    sumMaskGCorr += dg
                    sumMaskBCorr += db
                    hr[cr]++; hg[cg]++; hb[cb]++
                    sumCx += xPix.toDouble()
                    sumCy += yPixRow.toDouble()
                    if (xPix < minMx) minMx = xPix
                    if (yPixRow < minMy) minMy = yPixRow
                    if (xPix > maxMx) maxMx = xPix
                    if (yPixRow > maxMy) maxMy = yPixRow
                }
                xPix += step
            }
            yPixRow += step
        }

        val nEff = totalPix.coerceAtLeast(1).toDouble()
        val coverageInitial = maskCount / nEff
        if (coverageInitial < 0.10) return null

        val bboxW = (maxMx - minMx + 1).coerceAtLeast(8)
        val bboxH = (maxMy - minMy + 1).coerceAtLeast(8)
        val pad = max(4, (max(bboxW, bboxH) * 0.08).roundToInt())
        val roiLeft = (minMx - pad).coerceIn(0, width - 1)
        val roiTop = (minMy - pad).coerceIn(0, height - 1)
        val roiRight = (maxMx + pad).coerceIn(0, width - 1)
        val roiBottom = (maxMy + pad).coerceIn(0, height - 1)
        val roiW = (roiRight - roiLeft + 1).coerceAtLeast(8)
        val roiH = (roiBottom - roiTop + 1).coerceAtLeast(8)

        val maskTotal = maskCount.toDouble().coerceAtLeast(1.0)
        if (hr.sum() < 24) return null

        val medianR = percentileFromHist(hr, maskTotal, 0.5).toDouble()
        val medianG = percentileFromHist(hg, maskTotal, 0.5).toDouble()
        val medianB = percentileFromHist(hb, maskTotal, 0.5).toDouble()

        val p05R = percentileFromHist(hr, maskTotal, 0.05).toDouble()
        val p05G = percentileFromHist(hg, maskTotal, 0.05).toDouble()
        val p05B = percentileFromHist(hb, maskTotal, 0.05).toDouble()
        val p50R = medianR
        val p50G = medianG
        val p50B = medianB
        val p95R = percentileFromHist(hr, maskTotal, 0.95).toDouble()
        val p95G = percentileFromHist(hg, maskTotal, 0.95).toDouble()
        val p95B = percentileFromHist(hb, maskTotal, 0.95).toDouble()

        val tR = trimmedMeanFromHist(hr, maskTotal, 0.20, 0.80)
        val tG = trimmedMeanFromHist(hg, maskTotal, 0.20, 0.80)
        val tB = trimmedMeanFromHist(hb, maskTotal, 0.20, 0.80)

        val madR = robustMadFromHist(hr)
        val madG = robustMadFromHist(hg)
        val madB = robustMadFromHist(hb)

        val nm = maskTotal
        val mrPre = sumMaskRPre / nm
        val mgPre = sumMaskGPre / nm
        val mbPre = sumMaskBPre / nm
        val mr = sumMaskRCorr / nm
        val mg = sumMaskGCorr / nm
        val mb = sumMaskBCorr / nm

        val yMean = sumY / nEff
        val roiVar = (sumY2 / nEff) - (yMean * yMean)

        rb.push(mr); gb.push(mg); bb.push(mb)
        val (rAc, rDc) = rb.peakPeakAndDc()
        val (gAc, gDc) = gb.peakPeakAndDc()
        val (bAc, bDc) = bb.peakPeakAndDc()
        val rAcDc = if (rDc > 1.0) rAc / rDc else 0.0
        val gAcDc = if (gDc > 1.0) gAc / gDc else 0.0
        val bAcDc = if (bDc > 1.0) bAc / bDc else 0.0

        val clipHR = clipHighRgb / nEff
        val clipLR = clipLowRgb / nEff
        val satR = saturatedR / nEff
        val satG = saturatedG / nEff
        val satB = saturatedB / nEff

        val diff = abs(tG - prevMeanGreen)
        prevMeanGreen = tG
        val motionRaw = diff / max(tG + 18.0, 18.0)
        val motionSmoothed = motionSmooth.update(motionRaw.coerceIn(0.0, 1.5))

        val lowLight = mg < 22.0 && clipLR < 0.38

        val redDominance = mr / max(mg + 1e-6, 1e-6)
        val greenPulsatility = gb.coefficientVariation()
        val blueStable = bb.stabilityScore()

        val perfusionGreenPct = kotlin.math.min(
            PpgAcquisitionTuning.GREEN_PERFUSION_SCALE,
            gAcDc * PpgAcquisitionTuning.GREEN_PERFUSION_SCALE
        )

        val roiStats = RoiChannelStats(
            roiLeft = roiLeft,
            roiTop = roiTop,
            roiWidth = roiW,
            roiHeight = roiH,
            redMean = mr,
            greenMean = mg,
            blueMean = mb,
            redMedian = medianR,
            greenMedian = medianG,
            blueMedian = medianB,
            redTrimmedMean2080 = tR,
            greenTrimmedMean2080 = tG,
            blueTrimmedMean2080 = tB,
            redP05 = p05R,
            greenP05 = p05G,
            blueP05 = p05B,
            redP50 = p50R,
            greenP50 = p50G,
            blueP50 = p50B,
            redP95 = p95R,
            greenP95 = p95G,
            blueP95 = p95B,
            redMad = madR,
            greenMad = madG,
            blueMad = madB,
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

        val profileScore = roiStats.fingerProfileScore()
        val contactScore =
            (0.48 * coverageInitial + 0.32 * profileScore + 0.20 * (1.0 - clipHR * 3.0))
                .coerceIn(0.0, 1.0)

        var reject = ""
        when {
            coverageInitial < 0.18 -> reject = "dedo_parcial_o_sin_cobertura"
            clipHR > 0.35 -> reject = "clipping_alto"
            clipLR > 0.42 -> reject = "clipping_bajo"
            redDominance < 0.92 || redDominance > 2.4 -> reject = "perfil_no_piel"
            greenPulsatility < 0.004 && coverageInitial < 0.35 -> reject = "sin_pulsatilidad_evidente"
            contactScore < 0.22 -> reject = "contacto_insuficiente"
        }

        if (reject.isNotEmpty()) return null

        val eps = 1.2
        val absGreen = -ln((mg + eps) / max(gDc, eps))
        val absRed = -ln((mr + eps) / max(rDc, eps))
        dcGreenAbs.push(absGreen)
        dcRedAbs.push(absRed)

        val prevTs = prevFrameTsNs
        prevFrameTsNs = image.timestamp
        val instFps = if (prevTs != null && image.timestamp > prevTs) {
            (1_000_000_000.0 / (image.timestamp - prevTs).toDouble()).coerceIn(8.0, 72.0)
        } else {
            0.0
        }
        val fpsHz = if (instFps > 0.1) fpsSmooth.update(instFps) else fpsSmooth.value

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
            ppgGreenAbsorbance = absGreen,
            ppgRedAbsorbance = absRed,
            filteredPrimary = absGreen,
            displayWave = 0.0,
            roiStats = roiStats,
            clippingHighRatio = clipHR,
            clippingLowRatio = clipLR,
            motionScoreOptical = motionSmoothed,
            exposureDiagnostics = diagOut,
            lowLightSuspected = lowLight,
            maskCoverage = coverageInitial,
            contactScore = contactScore,
            instantaneousFpsHz = fpsHz,
            roiBoundingLeft = roiLeft,
            roiBoundingTop = roiTop,
            roiBoundingWidth = roiW,
            roiBoundingHeight = roiH,
            rejectReason = "",
            waveformDisplayAllowed = false,
            sqi = 0.0,
            filteredSecondary = absRed
        )
    }

    private data class RgbY(val r: Int, val g: Int, val b: Int, val y: Int)

    private fun yuvToRgb(
        xPix: Int,
        yPixRow: Int,
        yBuf: java.nio.ByteBuffer,
        uBuf: java.nio.ByteBuffer,
        vBuf: java.nio.ByteBuffer,
        yRow: Int,
        yPix: Int,
        uRow: Int,
        uPix: Int,
        vRow: Int,
        vPix: Int
    ): RgbY? {
        val uvY = yPixRow shr 1
        val uvX = xPix shr 1
        val yIdx = yPixRow * yRow + xPix * yPix
        val uIdx = uvY * uRow + uvX * uPix
        val vIdx = uvY * vRow + uvX * vPix
        if (yIdx < 0 || yIdx >= yBuf.capacity() ||
            uIdx < 0 || uIdx >= uBuf.capacity() ||
            vIdx < 0 || vIdx >= vBuf.capacity()
        ) {
            return null
        }
        val yv = yBuf.get(yIdx).toInt() and 0xFF
        val uu = (uBuf.get(uIdx).toInt() and 0xFF) - 128
        val vv = (vBuf.get(vIdx).toInt() and 0xFF) - 128
        val r = (yv + 1.402 * vv).toInt().coerceIn(0, 255)
        val g = (yv - 0.344136 * uu - 0.714136 * vv).toInt().coerceIn(0, 255)
        val b = (yv + 1.772 * uu).toInt().coerceIn(0, 255)
        return RgbY(r, g, b, yv)
    }

    /** Perfil dedo bajo flash blanco: rojo dominante no saturado, verde útil, azul menor. */
    private fun isFingerMaskPixel(yv: Int, r: Int, g: Int, b: Int, clipHigh: Boolean, clipLow: Boolean): Boolean {
        if (clipHigh || clipLow) return false
        if (yv < 12 || yv > 248) return false
        val rf = r.toDouble()
        val gf = g.toDouble()
        val bf = b.toDouble()
        if (rf < 28 || gf < 18) return false
        if (bf > rf * 0.92) return false
        val rg = rf / max(gf, 1.0)
        if (rg < 1.01 || rg > 1.72) return false
        if (bf > gf * 0.95) return false
        return true
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
        roiFractionAdaptive = roiFractionAdaptive.coerceIn(0.44, 0.68)
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

        private fun trimmedMeanFromHist(hist: IntArray, total: Double, loP: Double, hiP: Double): Double {
            val loIdx = (total * loP).toLong().coerceAtLeast(0L)
            val hiIdx = (total * hiP).toLong().coerceAtMost(total.toLong())
            if (hiIdx <= loIdx) return 0.0
            var cum = 0L
            var sum = 0.0
            var count = 0L
            for (i in hist.indices) {
                val c = hist[i].toLong()
                if (c == 0L) continue
                val segStart = cum
                val segEnd = cum + c
                val takeLo = max(loIdx, segStart)
                val takeHi = min(hiIdx, segEnd)
                if (takeHi > takeLo) {
                    sum += i.toDouble() * (takeHi - takeLo)
                    count += takeHi - takeLo
                }
                cum = segEnd
                if (cum >= hiIdx) break
            }
            return if (count > 0) sum / count.toDouble() else 0.0
        }

        private fun robustMadFromHist(hist: IntArray): Double {
            val med = percentileFromHist(hist, hist.sum().toDouble().coerceAtLeast(1.0), 0.5).toDouble()
            var acc = 0.0
            var n = 0.0
            for (i in hist.indices) {
                val c = hist[i]
                if (c == 0) continue
                acc += abs(i - med) * c
                n += c
            }
            return if (n > 0) (acc / n).coerceAtLeast(0.02) else 0.02
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
