package com.forensicppg.monitor.pipeline

import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import com.forensicppg.monitor.domain.CameraFrame
import com.forensicppg.monitor.domain.HypertensionRiskBand
import com.forensicppg.monitor.domain.MeasurementState
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.ReadingValidity
import com.forensicppg.monitor.domain.VitalReading
import com.forensicppg.monitor.forensic.AuditTrail
import com.forensicppg.monitor.forensic.MeasurementEvent
import com.forensicppg.monitor.ppg.ArrhythmiaScreening
import com.forensicppg.monitor.ppg.BeatClassifier
import com.forensicppg.monitor.ppg.CalibrationProfile
import com.forensicppg.monitor.ppg.FingerContactDetector
import com.forensicppg.monitor.ppg.HeartRateFusion
import com.forensicppg.monitor.ppg.PeakDetectorDerivative
import com.forensicppg.monitor.ppg.PeakDetectorElgendi
import com.forensicppg.monitor.ppg.PpgPreprocessor
import com.forensicppg.monitor.ppg.SignalQualityIndex
import com.forensicppg.monitor.ppg.SpectralHeartRateEstimator
import com.forensicppg.monitor.ppg.Spo2Estimator

/**
 * Pipeline completo en memoria. Todos los estadios procesan SIEMPRE datos
 * reales (`CameraFrame`); si el frame no alcanza calidad, la salida es
 * inválida y no se inventa nada.
 */
class PpgPipeline(
    private val sampleRate: Double,
    private val auditTrail: AuditTrail? = null
) {
    private val preprocessor = PpgPreprocessor(sampleRate)
    private val spo2 = Spo2Estimator(sampleRate)
    private val spectral = SpectralHeartRateEstimator(sampleRate, windowSeconds = 8.0)
    private val elgendi = PeakDetectorElgendi(sampleRate)
    private val derivative = PeakDetectorDerivative(sampleRate)
    private val classifier = BeatClassifier()
    private val screening = ArrhythmiaScreening()
    private val sqi = SignalQualityIndex()
    private val fusion = HeartRateFusion()
    private val contact = FingerContactDetector()

    private var lastBeatTsNs: Long? = null
    private var totalBeats = 0
    private var abnormalBeats = 0
    private var targetFps: Double = sampleRate

    /** Fps medido por el pipeline (moving avg simple sobre timestamps). */
    private var fpsMovingAvg: Double = 0.0
    private var fpsJitter: Double = 0.0
    private var lastFrameTsNs: Long? = null

    /** Resultado agregado de cada frame. */
    data class Step(
        val sample: PpgSample,
        val reading: VitalReading,
        val beatEvent: BeatEvent?,
        val acceptedFrame: Boolean
    )

    fun setTargetFps(fps: Int) { targetFps = fps.toDouble() }

    fun reset() {
        preprocessor.reset()
        spo2.reset()
        spectral.reset()
        elgendi.reset()
        derivative.reset()
        classifier.reset()
        screening.reset()
        contact.reset()
        lastBeatTsNs = null
        totalBeats = 0
        abnormalBeats = 0
        fpsMovingAvg = 0.0
        fpsJitter = 0.0
        lastFrameTsNs = null
    }

    /**
     * Procesa un frame real de cámara. El canal rojo es la entrada principal
     * al bandpass; el rojo/verde/azul se alimentan al estimador SpO₂.
     */
    fun process(frame: CameraFrame, motionScore: Double, calibration: CalibrationProfile?): Step {
        updateFps(frame.timestampNs)

        val pre = preprocessor.process(frame.redMean)
        spo2.push(frame.redMean, frame.greenMean, frame.blueMean)
        spectral.push(pre.filtered)
        val (contactState, validityFlags) = contact.evaluate(frame, motionScore, fpsMovingAvg)
        auditTrail?.observeState(contactState, frame.timestampNs)

        // Detección de picos: sólo alimentamos el detector si el estado
        // permite métricas; de lo contrario ignoramos para no producir picos
        // fantasmas con señal inestable.
        var beat: BeatEvent? = null
        if (contactState.allowsMetrics) {
            val peak = elgendi.feed(pre.filtered, frame.timestampNs)
            val confirm = derivative.feed(pre.filtered, frame.timestampNs)
            if (peak != null) {
                val tsNs = peak.timestampNs
                val prev = lastBeatTsNs
                val rr = if (prev != null) (tsNs - prev) / 1_000_000.0 else null
                val bpmInstant = rr?.let { if (it > 0.0) 60_000.0 / it else null }
                val raw = BeatEvent(
                    timestampNs = tsNs,
                    amplitude = peak.amplitude,
                    rrMs = rr,
                    bpmInstant = bpmInstant,
                    quality = 0.0,
                    type = BeatType.NORMAL,
                    reason = ""
                )
                // Si sólo Elgendi marca, exigimos morfología confirmando
                val confirmed = confirm != null ||
                        (pre.perfusionIndex > 0.3 && validityFlags == 0)
                val sqiNow = estimateSqiQuick(pre, frame, motionScore)
                val classified = classifier.classify(raw, sqiNow)
                if (classified.type != BeatType.INVALID_SIGNAL && confirmed) {
                    screening.ingest(classified)
                    if (prev != null && rr != null) {
                        // consumido en historial de clasificador
                    }
                    lastBeatTsNs = tsNs
                    totalBeats++
                    if (classified.type != BeatType.NORMAL) {
                        abnormalBeats++
                        auditTrail?.log(tsNs, MeasurementEvent.Kind.BEAT_ABNORMAL, classified.reason)
                    }
                    beat = classified
                }
            }
        }

        val screeningSummary = screening.compute(sqi = 1.0)
        val sqiValue = sqi.evaluate(
            SignalQualityIndex.Input(
                hasContact = contactState.allowsMetrics || contactState == MeasurementState.WARMUP,
                perfusionIndex = pre.perfusionIndex,
                clipHighRatio = frame.clipHighRatio,
                clipLowRatio = frame.clipLowRatio,
                motionScore = motionScore,
                fpsActual = fpsMovingAvg,
                fpsTarget = targetFps,
                spectralCoherence = 0.0,
                rrCv = screeningSummary.coefficientOfVariation,
                rrCount = screeningSummary.rrCount,
                roiSpatialStd = kotlin.math.sqrt(frame.roiVariance.coerceAtLeast(0.0))
            )
        )

        val spec = spectral.estimate()
        val rrBpm = classifier.medianRr()?.let { 60_000.0 / it }
        val rrBeatsUsed = screeningSummary.rrCount
        val fused = fusion.fuse(
            rrBpm = rrBpm,
            rrBeats = rrBeatsUsed,
            specBpm = spec.bpm,
            specCoherence = spec.coherence,
            sqi = sqiValue
        )

        val spo2Result = spo2.estimate(
            calibration = calibration,
            perfusionIndex = pre.perfusionIndex,
            sqi = sqiValue,
            motionScore = motionScore,
            clipHighRatio = frame.clipHighRatio
        )

        val fpsFlag = if (fpsMovingAvg in 1.0..18.0) ReadingValidity.LOW_FPS else 0
        val perfFlag = if (pre.perfusionIndex < 0.3) ReadingValidity.LOW_PERFUSION else 0
        val calFlag = if (calibration == null && contactState.allowsMetrics)
            ReadingValidity.CALIBRATION_MISSING else 0
        val allFlags = validityFlags or fpsFlag or perfFlag or calFlag

        val finalState = when {
            !contactState.allowsMetrics && contactState != MeasurementState.WARMUP -> contactState
            calibration == null && contactState == MeasurementState.MEASURING -> MeasurementState.MEASURING
            else -> contactState
        }

        val message = composeMessage(finalState, allFlags, calibration == null)
        val risk = hypertensionRiskFrom(fused.bpm, screeningSummary, pre.perfusionIndex, sqiValue)

        val sample = PpgSample(
            timestampNs = frame.timestampNs,
            raw = frame.redMean,
            filtered = pre.filtered,
            displayValue = pre.filtered,
            sqi = sqiValue,
            perfusionIndex = pre.perfusionIndex,
            motionScore = motionScore,
            valid = finalState.allowsMetrics
        )

        val reading = VitalReading(
            bpm = fused.bpm,
            bpmConfidence = fused.confidence,
            spo2 = spo2Result.spo2,
            spo2Confidence = spo2Result.confidence,
            sqi = sqiValue,
            perfusionIndex = pre.perfusionIndex,
            motionScore = motionScore,
            rrMs = screeningSummary.meanRr,
            rrSdnnMs = screeningSummary.sdnnMs,
            pnn50 = screeningSummary.pnn50,
            beatsDetected = totalBeats,
            abnormalBeats = abnormalBeats,
            state = finalState,
            validityFlags = allFlags,
            message = message,
            hypertensionRisk = risk
        )

        return Step(
            sample = sample,
            reading = reading,
            beatEvent = beat,
            acceptedFrame = finalState.allowsMetrics
        )
    }

    private fun estimateSqiQuick(
        pre: PpgPreprocessor.Output,
        frame: CameraFrame,
        motion: Double
    ): Double {
        if (motion > 0.7) return 0.0
        if (frame.clipHighRatio > 0.2) return 0.1
        val pi = (pre.perfusionIndex / 4.0).coerceIn(0.0, 1.0)
        val m = (1.0 - motion).coerceIn(0.0, 1.0)
        return (pi * m).coerceIn(0.0, 1.0)
    }

    private fun updateFps(timestampNs: Long) {
        val prev = lastFrameTsNs
        if (prev != null) {
            val dtMs = (timestampNs - prev) / 1_000_000.0
            if (dtMs > 0.0) {
                val instant = 1000.0 / dtMs
                if (fpsMovingAvg == 0.0) fpsMovingAvg = instant
                else fpsMovingAvg = 0.9 * fpsMovingAvg + 0.1 * instant
                val jitter = kotlin.math.abs(dtMs - (1000.0 / fpsMovingAvg.coerceAtLeast(1.0)))
                fpsJitter = 0.9 * fpsJitter + 0.1 * jitter
            }
        }
        lastFrameTsNs = timestampNs
    }

    fun fpsActual(): Double = fpsMovingAvg
    fun fpsJitterMs(): Double = fpsJitter

    private fun composeMessage(
        state: MeasurementState,
        flags: Int,
        calibrationMissing: Boolean
    ): String {
        if (state == MeasurementState.NO_CONTACT) return "Coloque el dedo sobre la cámara y el flash"
        if (state == MeasurementState.CONTACT_PARTIAL) return "Contacto parcial — cubra por completo la lente"
        if (flags and ReadingValidity.MOTION_HIGH != 0) return "Movimiento excesivo — inmovilice el dedo"
        if (flags and ReadingValidity.CLIPPING_HIGH != 0) return "Saturación óptica — reduzca la presión"
        if (flags and ReadingValidity.CLIPPING_LOW != 0) return "Imagen demasiado oscura — revise el flash"
        if (flags and ReadingValidity.LOW_PERFUSION != 0) return "Baja perfusión — caliente el dedo"
        if (flags and ReadingValidity.LOW_FPS != 0) return "FPS inestable — verifique condiciones del dispositivo"
        if (state == MeasurementState.WARMUP) return "Calentando sensor óptico — aguarde"
        if (calibrationMissing && state == MeasurementState.MEASURING) {
            return "SpO₂ requiere calibración con oxímetro de referencia"
        }
        return "Midiendo — mantenga el dedo inmóvil"
    }

    private fun hypertensionRiskFrom(
        bpm: Double?, screening: ArrhythmiaScreening.Summary,
        pi: Double, sqi: Double
    ): HypertensionRiskBand? {
        if (bpm == null || sqi < 0.55 || screening.rrCount < 8) return HypertensionRiskBand.UNCERTAIN
        // Heurística basada en morfología: CV alto y baja HRV → patrón
        // compatible con hipertensión. Bandas intencionalmente conservadoras.
        val cv = screening.coefficientOfVariation ?: return HypertensionRiskBand.UNCERTAIN
        val rmssd = screening.rmssdMs ?: return HypertensionRiskBand.UNCERTAIN
        val piBand = pi >= 1.0
        val stiff = cv < 0.03 && rmssd < 18.0 && bpm > 70.0 && piBand
        val borderline = cv < 0.05 && rmssd < 25.0 && bpm > 65.0
        return when {
            stiff -> HypertensionRiskBand.HYPERTENSIVE_PATTERN
            borderline -> HypertensionRiskBand.BORDERLINE
            else -> HypertensionRiskBand.NORMOTENSE
        }
    }
}
