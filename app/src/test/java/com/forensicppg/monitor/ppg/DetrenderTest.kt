package com.forensicppg.monitor.ppg

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DetrenderTest {

    @Test
    fun detrender_removes_linear_trend() {
        val det = Detrender(windowSamples = 64)
        var maxAbs = 0.0
        for (i in 0 until 1024) {
            val out = det.process(i.toDouble())
            if (i >= 128) maxAbs = maxOf(maxAbs, abs(out))
        }
        assertTrue("detrender no remueve tendencia lineal (maxAbs=$maxAbs)", maxAbs < 40.0)
    }

    @Test
    fun detrender_centers_constant_signal_to_zero_after_warmup() {
        val det = Detrender(windowSamples = 16)
        repeat(64) { det.process(5.0) }
        val out = det.process(5.0)
        assertTrue(abs(out) < 0.001)
    }
}
