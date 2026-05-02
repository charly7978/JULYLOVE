package com.forensicppg.monitor.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateFusionTest {
    private val fusion = HeartRateFusion()

    @Test
    fun returns_null_when_sqi_is_too_low() {
        val r = fusion.fuse(rrBpm = 72.0, rrBeats = 10, specBpm = 74.0, specCoherence = 0.9, sqi = 0.2)
        assertNull(r.bpm)
        assertEquals("bajo_sqi", r.source)
    }

    @Test
    fun fuses_when_rr_and_spec_agree() {
        val r = fusion.fuse(rrBpm = 72.0, rrBeats = 10, specBpm = 73.0, specCoherence = 0.9, sqi = 0.7)
        assertNotNull(r.bpm)
        assertTrue(r.confidence > 0.4)
        assertEquals("fusion", r.source)
    }

    @Test
    fun falls_back_to_rr_when_spec_disagrees() {
        val r = fusion.fuse(rrBpm = 72.0, rrBeats = 10, specBpm = 100.0, specCoherence = 0.9, sqi = 0.8)
        assertEquals(72.0, r.bpm!!, 0.001)
        assertEquals("rr_disagree", r.source)
    }

    @Test
    fun returns_null_without_rr_and_low_coherence() {
        val r = fusion.fuse(rrBpm = null, rrBeats = 0, specBpm = 70.0, specCoherence = 0.1, sqi = 0.7)
        assertNull(r.bpm)
    }
}
