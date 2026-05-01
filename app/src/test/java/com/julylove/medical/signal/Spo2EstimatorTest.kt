package com.julylove.medical.signal

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Spo2EstimatorTest {

    @Test
    fun `señal estable sin pulsacion suficiente no reporta spo2 positivo`() {
        val est = Spo2Estimator()
        var last = Spo2Estimator.Spo2Result(99f, 1.0, "")
        repeat(160) {
            last = est.process(100f, 50f, 30f, sqi = 0.92f)
        }
        assertEquals(0f, last.spo2, 0.01f)
        assertTrue(last.status.uppercase().contains("SIGNAL") || last.status.uppercase().contains("SEÑAL"))
    }

    @Test
    fun `pulso sine rojo verde estable entrega valor distinto de cero con sqi alto`() {
        val est = Spo2Estimator()
        var last = Spo2Estimator.Spo2Result(0f, 0.0, "")
        repeat(165) {
            val ph = it * 0.35
            val r = 195f + 10f * sin(ph).toFloat()
            val g = 85f + 6f * sin(ph + 0.4).toFloat()
            val b = 45f + 3f * sin(ph + 0.9).toFloat()
            last = est.process(r, g, b, sqi = 0.94f)
        }
        assertTrue(last.spo2 in 82f..100f)
        assertEquals(0.94, last.confidence, 0.01)
    }

    @Test
    fun `sqi inferior a umbral no expone resultado numerico de spo2`() {
        val est = Spo2Estimator()
        var last = Spo2Estimator.Spo2Result(99f, 1.0, "")
        repeat(165) {
            val ph = it * 0.35
            val r = 195f + 10f * sin(ph).toFloat()
            val g = 85f + 6f * sin(ph + 0.4).toFloat()
            val b = 45f + 3f * sin(ph + 0.9).toFloat()
            last = est.process(r, g, b, sqi = 0.51f)
        }
        assertEquals(0f, last.spo2, 1e-2f)
    }
}
