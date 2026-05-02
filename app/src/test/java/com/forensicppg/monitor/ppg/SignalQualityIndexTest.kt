package com.forensicppg.monitor.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalQualityIndexTest {
    private val sqi = SignalQualityIndex()

    @Test
    fun zero_when_no_contact() {
        val v = sqi.evaluate(
            SignalQualityIndex.Input(
                hasContact = false, perfusionIndex = 1.0, clipHighRatio = 0.0,
                clipLowRatio = 0.0, motionScore = 0.0, fpsActual = 30.0, fpsTarget = 30.0,
                spectralCoherence = 0.9, rrCv = 0.05, rrCount = 10, roiSpatialStd = 10.0
            )
        )
        assertEquals(0.0, v, 0.0001)
    }

    @Test
    fun high_when_everything_is_ideal() {
        val v = sqi.evaluate(
            SignalQualityIndex.Input(
                hasContact = true, perfusionIndex = 2.5, clipHighRatio = 0.01,
                clipLowRatio = 0.0, motionScore = 0.05, fpsActual = 30.0, fpsTarget = 30.0,
                spectralCoherence = 0.9, rrCv = 0.03, rrCount = 15, roiSpatialStd = 8.0
            )
        )
        assertTrue("SQI esperado > 0.5 en condiciones ideales, fue $v", v > 0.5)
    }

    @Test
    fun clipping_sends_sqi_to_zero() {
        val v = sqi.evaluate(
            SignalQualityIndex.Input(
                hasContact = true, perfusionIndex = 2.5, clipHighRatio = 0.5,
                clipLowRatio = 0.0, motionScore = 0.05, fpsActual = 30.0, fpsTarget = 30.0,
                spectralCoherence = 0.9, rrCv = 0.03, rrCount = 15, roiSpatialStd = 8.0
            )
        )
        assertEquals(0.0, v, 0.0001)
    }

    @Test
    fun band_labels_are_correct() {
        assertEquals(SignalQualityIndex.Band.EXCELLENT, sqi.band(0.9))
        assertEquals(SignalQualityIndex.Band.GOOD, sqi.band(0.6))
        assertEquals(SignalQualityIndex.Band.DEGRADED, sqi.band(0.4))
        assertEquals(SignalQualityIndex.Band.INVALID, sqi.band(0.1))
    }
}
