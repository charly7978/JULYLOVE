package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrhythmiaScreeningTest {

    private fun beat(rrMs: Double, type: BeatType = BeatType.NORMAL): BeatEvent =
        BeatEvent(0L, 1.0, rrMs, 60_000.0 / rrMs, 0.9, type, "")

    @Test
    fun regular_rhythm_has_low_cv() {
        val s = ArrhythmiaScreening()
        repeat(20) { s.ingest(beat(900.0)) }
        val r = s.compute(sqi = 0.9)
        assertFalse(r.flagIrregular)
        assertTrue("cv debería ser muy bajo, fue ${r.coefficientOfVariation}", (r.coefficientOfVariation ?: 1.0) < 0.01)
    }

    @Test
    fun irregular_rhythm_raises_flag() {
        val s = ArrhythmiaScreening()
        val rrs = doubleArrayOf(900.0, 700.0, 1200.0, 850.0, 600.0, 1400.0, 900.0, 700.0, 1100.0, 800.0, 650.0, 1300.0)
        rrs.forEach { s.ingest(beat(it)) }
        val r = s.compute(sqi = 0.9)
        assertTrue("cv debería estar por encima del umbral", (r.coefficientOfVariation ?: 0.0) > 0.12)
        assertTrue(r.flagIrregular)
    }

    @Test
    fun returns_empty_when_sqi_is_low() {
        val s = ArrhythmiaScreening()
        repeat(20) { s.ingest(beat(900.0)) }
        val r = s.compute(sqi = 0.1)
        assertNull(r.sdnnMs)
    }
}
