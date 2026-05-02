package com.forensicppg.monitor.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Spo2EstimatorTest {

    private fun feedSyntheticPhysiologicalSignal(est: Spo2Estimator, fs: Double, seconds: Int) {
        // Señal determinística: suma de senoides distintas para R/G/B con
        // offsets y pequeñas modulaciones. No se usa azar ni Math.random.
        val n = (fs * seconds).toInt()
        val beatHz = 1.2
        for (i in 0 until n) {
            val t = i / fs
            val r = 140.0 + 2.0 * sin(2.0 * PI * beatHz * t)
            val g = 80.0 + 0.8 * sin(2.0 * PI * beatHz * t + 0.1)
            val b = 40.0 + 0.3 * sin(2.0 * PI * beatHz * t + 0.2)
            est.push(r, g, b)
        }
    }

    @Test
    fun returns_null_without_calibration() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val r = e.estimate(calibration = null, perfusionIndex = 1.5, sqi = 0.8, motionScore = 0.05, clipHighRatio = 0.01)
        assertNull(r.spo2)
        assertEquals("sin_calibracion", r.reason)
    }

    @Test
    fun returns_spo2_with_calibration_when_signal_is_good() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val cal = CalibrationProfile(
            profileId = "test", deviceModel = "test", cameraId = "0", physicalCameraId = null,
            exposureTimeNs = null, iso = null, frameDurationNs = null, torchIntensity = null,
            coefficientA = 110.0, coefficientB = 25.0,
            createdAtMs = 0L, algorithmVersion = "spo2-ppg-v1",
            calibrationSamples = 3, minPerfusionIndex = 0.5, notes = ""
        )
        val r = e.estimate(calibration = cal, perfusionIndex = 1.5, sqi = 0.8, motionScore = 0.05, clipHighRatio = 0.01)
        assertNotNull(r.spo2)
        assertEquals("ok", r.reason)
    }

    @Test
    fun rejects_when_motion_is_high() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val cal = CalibrationProfile(
            profileId = "t", deviceModel = "t", cameraId = "0", physicalCameraId = null,
            exposureTimeNs = null, iso = null, frameDurationNs = null, torchIntensity = null,
            coefficientA = 110.0, coefficientB = 25.0,
            createdAtMs = 0L, algorithmVersion = "spo2-ppg-v1",
            calibrationSamples = 3, minPerfusionIndex = 0.5, notes = ""
        )
        val r = e.estimate(calibration = cal, perfusionIndex = 1.5, sqi = 0.8, motionScore = 0.9, clipHighRatio = 0.01)
        assertNull(r.spo2)
    }
}
