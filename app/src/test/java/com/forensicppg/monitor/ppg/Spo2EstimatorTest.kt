package com.forensicppg.monitor.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Spo2EstimatorTest {

    private fun feedSyntheticPhysiologicalSignal(est: Spo2Estimator, fs: Double, seconds: Int) {
        val n = (fs * seconds).toInt()
        val beatHz = 1.2
        for (i in 0 until n) {
            val t = i / fs
            // AC_R / DC_R ≈ 0.014; AC_G / DC_G ≈ 0.011 → R = 1.27 → SpO₂ ≈ 78
            // (la amplitud no es realista; estamos ejercitando la cadena, no
            // el valor clínico).
            val r = 140.0 + 2.0 * sin(2.0 * PI * beatHz * t)
            val g = 80.0 + 0.9 * sin(2.0 * PI * beatHz * t + 0.1)
            val b = 40.0 + 0.3 * sin(2.0 * PI * beatHz * t + 0.2)
            est.push(r, g, b)
        }
    }

    private fun testCalibration(samples: Int = 3): CalibrationProfile =
        CalibrationProfile(
            profileId = "test",
            deviceModel = "test",
            cameraId = "0",
            physicalCameraId = null,
            exposureTimeNs = null,
            iso = null,
            frameDurationNs = null,
            torchIntensity = null,
            coefficientA = 110.0,
            coefficientB = 25.0,
            coefficientC = null,
            createdAtMs = 0L,
            algorithmVersion = "spo2-ppg-v1",
            calibrationSamples = samples,
            minPerfusionIndex = 0.5,
            notes = ""
        )

    @Test
    fun returns_provisional_spo2_without_calibration() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val r = e.estimate(
            calibration = null,
            validityStateAllowsOximetry = true,
            perfusionIndex = 1.5,
            sqi = 0.8,
            motionScore = 0.05,
            clipHighRatio = 0.01
        )
        // Cambio crítico: ahora EMITIMOS un SpO2 provisional para que el
        // operador pueda calibrar. Antes era imposible ver SpO2 sin perfil
        // y la calibración requería el ratio en vivo, que también dependía
        // de pasar gates internos. Deadlock.
        assertNotNull(r.spo2Clinical)
        assertEquals("provisional_no_clinico", r.reasonCode)
        assertTrue(r.spo2Confidence <= 0.55)
        assertTrue(!r.clinicallyValidDisplay)
    }

    @Test
    fun returns_clinical_spo2_with_calibration_when_signal_is_good() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val cal = testCalibration(samples = 3)
        val r = e.estimate(
            calibration = cal,
            validityStateAllowsOximetry = true,
            perfusionIndex = 1.5,
            sqi = 0.8,
            motionScore = 0.05,
            clipHighRatio = 0.01
        )
        assertNotNull(r.spo2Clinical)
        assertEquals("ok", r.reasonCode)
        assertTrue(r.ratioOfRatios != null && r.ratioOfRatios!! > 0.0)
    }

    @Test
    fun rejects_when_motion_is_high() {
        val fs = 30.0
        val e = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(e, fs, 10)
        val cal = testCalibration()
        val r = e.estimate(
            calibration = cal,
            validityStateAllowsOximetry = true,
            perfusionIndex = 1.5,
            sqi = 0.8,
            motionScore = 0.9,
            clipHighRatio = 0.01
        )
        assertNull(r.spo2Clinical)
        assertEquals("movimiento_excesivo", r.reasonCode)
    }

    @Test
    fun ratio_uses_red_over_green_not_blue() {
        // Bajo flash blanco el ratio canónico es R/G. Si AC_R sube manteniendo
        // G fijo, R debe SUBIR (mayor absorción de rojo → menor saturación).
        val fs = 30.0
        val baseline = Spo2Estimator(fs)
        feedSyntheticPhysiologicalSignal(baseline, fs, 8)
        val rBase = baseline.estimate(
            calibration = testCalibration(),
            validityStateAllowsOximetry = true,
            perfusionIndex = 1.5,
            sqi = 0.8,
            motionScore = 0.05,
            clipHighRatio = 0.01
        ).ratioOfRatios!!

        val moreRed = Spo2Estimator(fs)
        val n = (fs * 8).toInt()
        val beatHz = 1.2
        for (i in 0 until n) {
            val t = i / fs
            val r = 140.0 + 4.0 * sin(2.0 * PI * beatHz * t) // AC_R doble
            val g = 80.0 + 0.9 * sin(2.0 * PI * beatHz * t)  // G igual
            val b = 40.0 + 0.3 * sin(2.0 * PI * beatHz * t)
            moreRed.push(r, g, b)
        }
        val rMoreRed = moreRed.estimate(
            calibration = testCalibration(),
            validityStateAllowsOximetry = true,
            perfusionIndex = 1.5,
            sqi = 0.8,
            motionScore = 0.05,
            clipHighRatio = 0.01
        ).ratioOfRatios!!

        assertTrue("RoR debe crecer con más AC rojo: $rBase -> $rMoreRed", rMoreRed > rBase)
    }
}
