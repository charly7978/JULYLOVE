package com.forensicppg.monitor.ppg

import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatClassifierTest {
    private fun beat(rrMs: Double?): BeatEvent = BeatEvent(
        timestampNs = 0L,
        amplitude = 1.0,
        rrMs = rrMs,
        bpmInstant = rrMs?.let { 60_000.0 / it }
    )

    @Test
    fun first_beat_is_normal() {
        val c = BeatClassifier()
        val out = c.classify(beat(null), sqi = 0.8)
        assertEquals(BeatType.NORMAL, out.type)
    }

    @Test
    fun premature_beat_is_detected() {
        val c = BeatClassifier()
        repeat(5) { c.classify(beat(1000.0), sqi = 0.8) }
        val out = c.classify(beat(650.0), sqi = 0.8)
        assertEquals(BeatType.SUSPECT_PREMATURE, out.type)
    }

    @Test
    fun pause_beat_is_detected() {
        val c = BeatClassifier()
        repeat(5) { c.classify(beat(900.0), sqi = 0.8) }
        val out = c.classify(beat(1500.0), sqi = 0.8)
        assertEquals(BeatType.SUSPECT_PAUSE, out.type)
    }

    @Test
    fun low_sqi_marks_invalid() {
        val c = BeatClassifier()
        val out = c.classify(beat(800.0), sqi = 0.1)
        assertEquals(BeatType.INVALID_SIGNAL, out.type)
    }
}
