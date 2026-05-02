package com.forensicppg.monitor.ppg

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class BandpassFilterTest {

    private fun rms(arr: DoubleArray, skip: Int = 0): Double {
        var s = 0.0; var c = 0
        for (i in skip until arr.size) { s += arr[i] * arr[i]; c++ }
        return if (c > 0) sqrt(s / c) else 0.0
    }

    @Test
    fun passes_frequency_inside_band() {
        val fs = 60.0
        val f = 1.5 // Hz → 90 BPM
        val filter = BandpassFilter(fs, 0.5, 4.0)
        val n = 1200
        val signal = DoubleArray(n) { i -> sin(2.0 * PI * f * i / fs) }
        val output = DoubleArray(n) { filter.process(signal[it]) }
        val inRms = rms(signal, 200)
        val outRms = rms(output, 400)
        assertTrue("banda pasante atenúa demasiado: in=$inRms out=$outRms", outRms > 0.55 * inRms)
    }

    @Test
    fun rejects_frequency_below_band() {
        val fs = 60.0
        val f = 0.05 // Hz (deriva lenta)
        val filter = BandpassFilter(fs, 0.5, 4.0)
        val n = 3000
        val signal = DoubleArray(n) { i -> sin(2.0 * PI * f * i / fs) }
        val output = DoubleArray(n) { filter.process(signal[it]) }
        val outRms = rms(output, 1000)
        assertTrue("no atenúa DC/deriva: outRms=$outRms", outRms < 0.05)
    }

    @Test
    fun rejects_frequency_above_band() {
        val fs = 60.0
        val f = 12.0
        val filter = BandpassFilter(fs, 0.5, 4.0)
        val n = 1200
        val signal = DoubleArray(n) { i -> cos(2.0 * PI * f * i / fs) }
        val output = DoubleArray(n) { filter.process(signal[it]) }
        val outRms = rms(output, 300)
        assertTrue("no atenúa alta frecuencia: outRms=$outRms", outRms < 0.1)
    }
}
