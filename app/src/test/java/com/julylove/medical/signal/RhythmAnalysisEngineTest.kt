package com.julylove.medical.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmAnalysisEngineTest {

    @Test
    fun `intervalos regulares establecen ritmo REGULAR con BPM plausible`() {
        val engine = RhythmAnalysisEngine()
        var timeNs = 1_000_000_000L
        var last: RhythmAnalysisEngine.RhythmResult? = null
        repeat(45) {
            val ppi = 800L
            timeNs += ppi * 1_000_000L
            last = engine.addIntervalDetailed(ppi, timeNs)
        }
        requireNotNull(last)
        assertEquals(RhythmAnalysisEngine.RhythmStatus.REGULAR, last!!.status)
        assertTrue(last!!.bpm in 72..82)
        assertTrue(last!!.shannonEntropyBits < 3.2)
        assertTrue(last!!.rmssd < 50.0)
    }

    @Test
    fun `patron muy irregular aumenta dispersion y activa clase de irregularidad alta`() {
        val engine = RhythmAnalysisEngine()
        var timeNs = 500_000_000L
        val patternMs = longArrayOf(420, 980, 450, 1020, 380, 1100)
        var last: RhythmAnalysisEngine.RhythmResult? = null
        repeat(90) {
            val ppi = patternMs[it % patternMs.size]
            timeNs += ppi * 1_000_000L
            last = engine.addIntervalDetailed(ppi, timeNs)
        }
        requireNotNull(last)
        assertTrue(last!!.cv > 8.0)
        assertEquals(RhythmAnalysisEngine.RhythmStatus.SUSPECTED_ARRHYTHMIA, last!!.status)
    }
}
