package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.PpgSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val FFT_SIZE = 512
private const val SPECTRAL_BOOTSTRAP_MIN_SAMPLES = 64

private val SPECTRAL_FFT_STEPS_DESC: IntArray =
    intArrayOf(
        512, 448, 384, 352, 320, 288, 256, 224, 192, 176, 160, 144, 128,
        112, 104, 96, 88, 80, 72, 64
    )

private fun fftWindowSamples(haveSamples: Int): Int {
    val h = haveSamples.coerceAtLeast(0)
    var best = 0
    var i = 0
    while (i < SPECTRAL_FFT_STEPS_DESC.size) {
        val sz = SPECTRAL_FFT_STEPS_DESC[i]
        if (h >= sz) {
            best = sz
            break
        }
        i++
    }
    return best
}

/**
 * Buffer circular con **timestamps reales**: absorbancia verde como señal principal,
 * pasabanda 0.7–3.5 Hz, espectro sobre ventana 8–12 s efectivos.
 */
class PpgSignalProcessor(
    sampleRateHz: Double,
    bufferSeconds: Double = 25.0
) {
    private val srNominal = sampleRateHz.coerceIn(14.0, 90.0)
    private val cap = ceil(srNominal * bufferSeconds).toInt().coerceIn(480, 6000)

    private val sigGreen = DoubleArray(cap)
    private val sigRedAbs = DoubleArray(cap)
    private val timeNs = LongArray(cap)
    private var head = 0
    private var filled = false

    private val detrend = Detrender(windowSamples = (srNominal * 4.5).toInt().coerceIn(48, 620))
    private val bp = BandpassFilter(srNominal, lowHz = 0.7, highHz = 3.5)
    private val smoothDeque = ArrayDeque<Double>(16)
    private val derivDeque = ArrayDeque<Double>(8)

    private var lastSpectrum = SpectrumSummary(
        dominantFreqHz = 0.0,
        heartBandFraction = 0.0,
        snrHeartDbEstimate = -40.0,
        coherenceRg = 0.0,
        autocorrPulseStrength = 0.0,
        dcStability = 0.5,
        searchDominantFreqHz = 0.0
    )

    data class SpectrumSummary(
        val dominantFreqHz: Double,
        val heartBandFraction: Double,
        val snrHeartDbEstimate: Double,
        val coherenceRg: Double,
        val autocorrPulseStrength: Double,
        val dcStability: Double,
        /** Pico en banda ampliada 0.5–4 Hz (sólo exploración). */
        val searchDominantFreqHz: Double
    )

    data class ProcessorOutput(
        val filteredWave: Double,
        val displaySmoothed: Double,
        val derivativeSmoothed: Double,
        val spectrumSummary: SpectrumSummary,
        val instantaneousHz: Double
    )

    fun reset() {
        head = 0; filled = false
        detrend.reset(); bp.reset()
        smoothDeque.clear(); derivDeque.clear()
        sigGreen.fill(0.0); sigRedAbs.fill(0.0)
        timeNs.fill(0L)
        lastSpectrum = SpectrumSummary(0.0, 0.0, -40.0, 0.0, 0.0, 0.5, 0.0)
    }

    fun lastSpectrumSummary(): SpectrumSummary = lastSpectrum
    fun effectiveSamples(): Int = if (filled) cap else head

    fun ingest(sample: PpgSample): ProcessorOutput {
        sigGreen[head] = sample.ppgGreenAbsorbance
        sigRedAbs[head] = sample.ppgRedAbsorbance
        timeNs[head] = sample.timestampNs
        head = (head + 1) % cap
        if (head == 0 && !filled) filled = true

        val detrended = detrend.process(sample.ppgGreenAbsorbance)
        val filt = bp.process(detrended)
        val prevY = smoothDeque.lastOrNull() ?: filt
        val deriv = filt - prevY
        smoothDeque.addLast(filt); while (smoothDeque.size > 7) smoothDeque.removeFirst()
        derivDeque.addLast(deriv); while (derivDeque.size > 5) derivDeque.removeFirst()
        val derivSm = derivDeque.average()

        val have = effectiveSamples()
        val segDc = chronologicalValues(sigGreen, min(560, have))
        val dtSeries = chronologicalDtSec(min(560, have))
        val fsEff = estimateFsFromTimestamps(dtSeries)
        val dcCv = coefficientOfVariation(segDc)
        val dcStab = (1.0 - min(12.0, dcCv)).coerceIn(0.0, 1.0)

        val win = fftWindowSamples(have)
        lastSpectrum =
            if (win >= SPECTRAL_BOOTSTRAP_MIN_SAMPLES) {
                computeSpectrum(dcStabilityHold = dcStab, nFft = win, fsEffective = fsEff)
            } else {
                lastSpectrum.copy(dcStability = dcStab)
            }

        return ProcessorOutput(
            filteredWave = filt,
            displaySmoothed = smoothDeque.average(),
            derivativeSmoothed = derivSm,
            spectrumSummary = lastSpectrum,
            instantaneousHz = fsEff
        )
    }

    private fun chronologicalValues(buf: DoubleArray, nSamples: Int): DoubleArray {
        val have = effectiveSamples()
        val n = min(nSamples, have).coerceAtLeast(1)
        val out = DoubleArray(n)
        for (t in 0 until n) {
            val oldestOffset = have - n + t
            var idx = head - oldestOffset - 1
            while (idx < 0) idx += cap
            idx %= cap
            out[t] = buf[idx]
        }
        return out
    }

    private fun chronologicalDtSec(nSamples: Int): DoubleArray {
        val have = effectiveSamples()
        val n = min(nSamples, have).coerceAtLeast(2)
        val ts = LongArray(n)
        for (t in 0 until n) {
            val oldestOffset = have - n + t
            var idx = head - oldestOffset - 1
            while (idx < 0) idx += cap
            idx %= cap
            ts[t] = timeNs[idx]
        }
        val dt = DoubleArray(n - 1)
        for (i in 0 until n - 1) {
            val d = (ts[i + 1] - ts[i]) / 1_000_000_000.0
            dt[i] = d.coerceIn(1.0 / 120.0, 0.25)
        }
        return dt
    }

    private fun estimateFsFromTimestamps(dt: DoubleArray): Double {
        if (dt.isEmpty()) return srNominal
        var s = 0.0
        for (d in dt) s += d
        val meanDt = s / dt.size.coerceAtLeast(1)
        return (1.0 / meanDt).coerceIn(14.0, 90.0)
    }

    private fun computeSpectrum(dcStabilityHold: Double, nFft: Int, fsEffective: Double): SpectrumSummary {
        require(nFft >= SPECTRAL_BOOTSTRAP_MIN_SAMPLES && nFft <= FFT_SIZE)
        val gRaw = chronologicalValues(sigGreen, nFft)
        val rRaw = chronologicalValues(sigRedAbs, nFft)
        val dtSeg = chronologicalDtSec(nFft)
        val fs = estimateFsFromTimestamps(dtSeg)

        val ff = gRaw.clone()
        subtractMean(ff)
        applyHann(ff)
        val magn = naivePower(ff)

        val binHz = fs / nFft.toDouble()
        val iSearchLo = max(2, ceil(0.50 / binHz).toInt())
        val iSearchHi = min(magn.lastIndex - 2, floor(4.0 / binHz).toInt())
        val iValLo = max(2, ceil(0.70 / binHz).toInt())
        val iValHi = min(magn.lastIndex - 2, floor(3.5 / binHz).toInt())

        val hiSearch = max(iSearchLo, iSearchHi.coerceAtMost(magn.lastIndex - 2))
        val totalPow = max(1e-15, magn.sum())
        val heartPow = sumSlice(magn, max(iValLo, iSearchLo), min(iValHi, hiSearch))
        val frac = heartPow / totalPow

        var peakPow = -1.0
        var peakHz = (iValLo * binHz).coerceIn(0.65, 3.5)
        val hiVal = min(hiSearch, magn.lastIndex - 2)
        for (i in max(iValLo, iSearchLo)..min(iValHi, hiVal)) {
            if (magn[i] > peakPow) {
                peakPow = magn[i]; peakHz = i * binHz
            }
        }
        if (peakPow < 1e-30) peakPow = 1e-30

        var searchPeakHz = peakHz
        var searchPow = -1.0
        for (i in iSearchLo..hiSearch) {
            if (magn[i] > searchPow) {
                searchPow = magn[i]; searchPeakHz = i * binHz
            }
        }

        val noiseMed = maskedMedianOutside(magn, iSearchLo, hiSearch)
        val snrDb = 10.0 * log10((peakPow / noiseMed.coerceAtLeast(1e-22)).coerceAtLeast(1e-14))

        val coh = pearson(rRaw, gRaw)
        val gAc = gRaw.clone(); subtractMean(gAc)
        val ac = autocorrStrength(gAc, fs, nFft)

        return SpectrumSummary(
            dominantFreqHz = peakHz.coerceIn(0.65, 3.6),
            heartBandFraction = frac.coerceIn(0.0, 1.0),
            snrHeartDbEstimate = snrDb.coerceIn(-40.0, 35.0),
            coherenceRg = coh.coerceIn(0.0, 1.0),
            autocorrPulseStrength = ac.coerceIn(0.0, 1.0),
            dcStability = dcStabilityHold,
            searchDominantFreqHz = searchPeakHz.coerceIn(0.48, 4.2)
        )
    }
}

internal fun coefficientOfVariation(data: DoubleArray): Double {
    if (data.size < 3) return 0.0
    var mu = 0.0
    for (v in data) mu += v
    mu /= data.size.toDouble()
    var vsum = 0.0
    for (t in data) {
        val d = t - mu
        vsum += d * d
    }
    vsum /= max(2, data.size - 1)
    return sqrt(vsum.coerceAtLeast(1e-12)) / max(abs(mu), 1e-3)
}

private fun naivePower(signal: DoubleArray): DoubleArray {
    val n = signal.size
    val half = n / 2 + 1
    val out = DoubleArray(half)
    for (k in 0 until half) {
        var cr = 0.0; var ci = 0.0
        val inv = -2 * PI / n
        var t = 0
        while (t < n) {
            val angle = inv * k * t
            val v = signal[t]
            cr += v * cos(angle)
            ci += v * sin(angle)
            t++
        }
        out[k] = cr * cr + ci * ci
    }
    return out
}

private fun subtractMean(a: DoubleArray) {
    var m = 0.0
    for (v in a) m += v
    m /= a.size.coerceAtLeast(1)
    for (i in a.indices) a[i] -= m
}

private fun applyHann(a: DoubleArray) {
    if (a.isEmpty()) return
    val denom = max(1, a.size - 1).toDouble()
    for (i in a.indices) {
        val w = 0.5 - 0.5 * cos(2 * PI * i / denom)
        a[i] *= w
    }
}

private fun sumSlice(a: DoubleArray, lo: Int, hi: Int): Double {
    var s = 0.0
    val l = lo.coerceAtLeast(0)
    val r = hi.coerceAtMost(a.lastIndex).coerceAtLeast(l)
    for (i in l..r) s += a[i]
    return s
}

private fun maskedMedianOutside(a: DoubleArray, lo: Int, hi: Int): Double {
    val loS = lo.coerceIn(0, a.lastIndex)
    val hiE = hi.coerceIn(0, a.lastIndex)
    val tmp = mutableListOf<Double>()
    for (idx in a.indices) {
        if (idx <= 3) continue
        val nearHeart = idx in max(0, loS - 6)..min(a.lastIndex, hiE + 14)
        if (!nearHeart) tmp += a[idx]
    }
    if (tmp.size < 6) return 1e-12
    tmp.sort()
    return tmp[tmp.size / 3].coerceAtLeast(1e-15)
}

private fun pearson(x: DoubleArray, y: DoubleArray): Double {
    val n = min(x.size, y.size)
    if (n < SPECTRAL_BOOTSTRAP_MIN_SAMPLES) return 0.0
    var mx = 0.0
    var my = 0.0
    var i = 0
    while (i < n) { mx += x[i]; my += y[i]; i++ }
    mx /= n; my /= n
    var nr = 0.0
    var dx = 0.0
    var dy = 0.0
    i = 0
    while (i < n) {
        val ux = x[i] - mx
        val uy = y[i] - my
        nr += ux * uy
        dx += ux * ux
        dy += uy * uy
        i++
    }
    return abs(nr / sqrt(dx * dy + 1e-18))
}

private fun autocorrStrength(x: DoubleArray, srHz: Double, nFftHint: Int): Double {
    val n = min(x.size, nFftHint)
    if (n < SPECTRAL_BOOTSTRAP_MIN_SAMPLES) return 0.0
    // R(0) = energía total — normalización correcta
    var r0 = 0.0
    for (t in 0 until n) r0 += x[t] * x[t]
    if (r0 < 1e-18) return 0.0
    val lagMin = max(4, ceil(srHz / 4.2).toInt().coerceAtMost(max(8, n / 8)))
    var lagMax = min(n / 3, ceil(srHz / 0.65).toInt()).coerceAtLeast(lagMin)
    lagMax = min(lagMax, max(lagMin, n / 3))
    var best = 0.0
    for (lag in lagMin..lagMax) {
        var acc = 0.0
        for (t in lag until n) {
            acc += x[t] * x[t - lag]
        }
        val sc = abs(acc / r0)
        if (sc > best) best = sc
    }
    return best.coerceIn(0.0, 1.0)
}
