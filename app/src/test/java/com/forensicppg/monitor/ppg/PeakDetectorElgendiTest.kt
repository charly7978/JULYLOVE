package com.forensicppg.monitor.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PeakDetectorElgendiTest {

    /** Genera una señal senoidal real (no aleatoria) a una frecuencia fija
     *  y conocida para validar que el detector cuenta los picos correctos. */
    private fun syntheticRhythmicWave(fs: Double, bpm: Int, durationSec: Double): DoubleArray {
        val hz = bpm / 60.0
        val n = (fs * durationSec).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = sin(2.0 * PI * hz * i / fs)
        return out
    }

    @Test
    fun detects_beats_at_60bpm_within_tolerance() {
        val fs = 60.0
        val wave = syntheticRhythmicWave(fs, bpm = 60, durationSec = 12.0)
        val filter = BandpassFilter(fs, 0.5, 4.0)
        val detector = PeakDetectorElgendi(fs)
        var peaks = 0
        var tNs = 0L
        val dtNs = (1e9 / fs).toLong()
        for (s in wave) {
            val f = filter.process(s)
            if (detector.feed(f, tNs) != null) peaks++
            tNs += dtNs
        }
        // 12 s a 60 BPM ≈ 12 latidos. Hay warm-up del bandpass que puede
        // perder el primero; aceptamos 10..13.
        assertTrue("Esperaba ≈12 latidos, obtuvo $peaks", peaks in 10..13)
    }

    @Test
    fun detects_beats_at_120bpm_within_tolerance() {
        val fs = 60.0
        val wave = syntheticRhythmicWave(fs, bpm = 120, durationSec = 10.0)
        val filter = BandpassFilter(fs, 0.5, 4.0)
        val detector = PeakDetectorElgendi(fs)
        var peaks = 0
        var tNs = 0L
        val dtNs = (1e9 / fs).toLong()
        for (s in wave) {
            val f = filter.process(s)
            if (detector.feed(f, tNs) != null) peaks++
            tNs += dtNs
        }
        assertTrue("Esperaba ≈20 latidos, obtuvo $peaks", peaks in 17..22)
    }

    @Test
    fun refractory_period_prevents_double_count() {
        val fs = 60.0
        val wave = syntheticRhythmicWave(fs, bpm = 60, durationSec = 6.0)
        val detector = PeakDetectorElgendi(fs, refractoryMs = 350L)
        val filter = BandpassFilter(fs, 0.5, 4.0)
        var peaks = 0
        var tNs = 0L
        val dtNs = (1e9 / fs).toLong()
        for (s in wave) {
            val f = filter.process(s)
            if (detector.feed(f, tNs) != null) peaks++
            tNs += dtNs
        }
        assertTrue("Refractario debería evitar más de ~6 picos, obtuvo $peaks", peaks <= 7)
    }

    @Test
    fun flat_zero_signal_produces_no_peaks() {
        val fs = 60.0
        val n = (fs * 10.0).toInt()
        val detector = PeakDetectorElgendi(fs)
        val filter = BandpassFilter(fs, 0.5, 4.0)
        var peaks = 0
        var tNs = 0L
        val dtNs = (1e9 / fs).toLong()
        for (i in 0 until n) {
            val f = filter.process(0.0)
            if (detector.feed(f, tNs) != null) peaks++
            tNs += dtNs
        }
        assertEquals(0, peaks)
    }
}
