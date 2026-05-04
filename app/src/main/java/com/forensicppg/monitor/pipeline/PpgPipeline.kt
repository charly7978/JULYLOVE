package com.forensicppg.monitor.pipeline

import com.forensicppg.monitor.domain.DiagnosticsSnapshot
import com.forensicppg.monitor.domain.ConfirmedBeat
import com.forensicppg.monitor.domain.HypertensionRiskBand
import com.forensicppg.monitor.domain.PpgSample
import com.forensicppg.monitor.domain.PpgValidityState
import com.forensicppg.monitor.domain.ReadingValidity
import com.forensicppg.monitor.domain.VitalReading
import com.forensicppg.monitor.forensic.AuditTrail
import com.forensicppg.monitor.ppg.CalibrationProfile
import com.forensicppg.monitor.ppg.PpgPeakDetector
import com.forensicppg.monitor.ppg.PpgPhysiologyClassifier
import com.forensicppg.monitor.ppg.PpgSignalProcessor
import com.forensicppg.monitor.ppg.PpgSignalQuality
import com.forensicppg.monitor.ppg.RhythmAnalyzer
import com.forensicppg.monitor.ppg.Spo2Estimator
import kotlin.math.abs

/** Cadena única: muestra → DSP → SQI duro → evidencia fisiológica → picos → vitales sólo con PPG_VALID. */
class PpgPipeline(
    sampleRateHz: Double,
    @Suppress("unused") private val auditTrail: AuditTrail? = null
) {
    data class AcquisitionMetrics(
        val frameDrops: Long,
        val measuredFpsHz: Double,
        val jitterMs: Double,
        val torchEnabled: Boolean,
        val manualSensorApplied: Boolean,
        val targetFpsHint: Int
    )

    data class Step(
        val sample: PpgSample,
        val reading: VitalReading,
        val confirmedBeat: ConfirmedBeat?
    )

    private val sr = sampleRateHz.coerceIn(15.5, 90.0)

    private val dsp = PpgSignalProcessor(sr, bufferSeconds = 25.0)
    private val rhythm = RhythmAnalyzer()
    private val peak = PpgPeakDetector(sr, rhythm)
    private val sqi = PpgSignalQuality()

    val spo2Estimator: Spo2Estimator = Spo2Estimator(sr, windowSeconds = 10.0)

    private var pipelineStartMonoNs: Long? = null
    private var maskGoodSinceMonoNs: Long? = null
    private var lastContactScore = 0.0

    fun reset() {
        dsp.reset()
        rhythm.reset()
        peak.reset()
        spo2Estimator.reset()
        pipelineStartMonoNs = null
        maskGoodSinceMonoNs = null
        lastContactScore = 0.0
    }

    fun peakStats(): PpgPeakDetector.DetectorStats = peak.stats

    fun process(
        ppg: PpgSample,
        imuMotion01: Double,
        calibration: CalibrationProfile?,
        acq: AcquisitionMetrics
    ): Step {
        val mono = ppg.monotonicRealtimeNs
        if (pipelineStartMonoNs == null) pipelineStartMonoNs = mono

        if (abs(ppg.contactScore - lastContactScore) > 0.30) {
            dsp.reset()
            rhythm.reset()
            peak.reset()
            spo2Estimator.reset()
            maskGoodSinceMonoNs = null
            pipelineStartMonoNs = mono
        }
        lastContactScore = ppg.contactScore

        if (ppg.maskCoverage >= 0.70) {
            if (maskGoodSinceMonoNs == null) maskGoodSinceMonoNs = mono
        } else {
            maskGoodSinceMonoNs = null
        }
        val maskSustained2s =
            maskGoodSinceMonoNs != null && (mono - maskGoodSinceMonoNs!!) >= 2_000_000_000L

        val stabilizationNs = 2_800_000_000L
        val inStabilization =
            pipelineStartMonoNs != null && (mono - pipelineStartMonoNs!!) < stabilizationNs

        spo2Estimator.push(ppg.rawRed, ppg.rawGreen, ppg.rawBlue)

        val dspOut = dsp.ingest(ppg)
        val spec = dspOut.spectrumSummary
        val hzValOk = spec.dominantFreqHz in 0.70..3.5

        val fusedMotion = kotlin.math.min(
            1.0,
            kotlin.math.max(ppg.motionScoreOptical, imuMotion01.coerceIn(0.0, 1.0))
        )

        val preRhythm = rhythm.summarize()
        val rrCvPre = preRhythm.coefficientVar
        val rrN = rhythm.rrIntervalCount()

        val sqiValue = sqi.compose(
            PpgSignalQuality.ComposerInput(
                spectralHeartFrac = spec.heartBandFraction,
                spectralSnrDb = spec.snrHeartDbEstimate,
                autocorr01 = spec.autocorrPulseStrength,
                coherenceRg = spec.coherenceRg,
                clippingHigh = ppg.clippingHighRatio,
                clippingLow = ppg.clippingLowRatio,
                motionCombined01 = fusedMotion,
                rrCv = rrCvPre,
                rrCount = rrN,
                maskCoverage = ppg.maskCoverage,
                maskSustained2s = maskSustained2s,
                greenAcDc = ppg.roiStats.greenAcDc,
                dominantInValidationBand = hzValOk
            )
        )

        val physiology = PpgPhysiologyClassifier.classify(
            sqiComposite = sqiValue,
            spectral = spec,
            opticalMotionSmoothed = fusedMotion.coerceAtMost(1.05),
            clippingHighRatio = ppg.clippingHighRatio,
            clippingLowRatio = ppg.clippingLowRatio,
            maskCoverage = ppg.maskCoverage,
            maskSustained2s = maskSustained2s,
            peakConfirmedCount = peak.stats.confirmedSession,
            rrIntervalCount = rrN,
            roiFingerProfileScore = ppg.roiStats.fingerProfileScore(),
            greenAcDcBandEstimate = ppg.roiStats.greenAcDc,
            contactScore = ppg.contactScore
        )

        val beat = peak.onSample(
            timestampNs = ppg.timestampNs,
            filteredWave = dspOut.filteredWave,
            derivativeWave = dspOut.derivativeSmoothed,
            sqiComposite = sqiValue,
            validityState = physiology,
            opticalMotionSmoothed = fusedMotion.coerceAtMost(1.5),
            maskCoverage = ppg.maskCoverage,
            stabilizationActive = inStabilization
        )
        beat?.let { b ->
            rhythm.registerRr(b.rrIntervalMs, b.timestampNs, b.rhythmMarker)
        }

        val rhythmPost = rhythm.summarize()
        val rrTail = rhythm.tachogramTail(24)
        val rrMedianMs = rrTail.takeIf { it.size >= 4 }
            ?.sorted()
            ?.let { v -> v[v.size / 2] }

        val ppgConfirmed =
            physiology.ordinal >= PpgValidityState.PPG_VALID.ordinal &&
                !inStabilization &&
                maskSustained2s

        val enoughRrCount =
            ppgConfirmed &&
                rrTail.size >= 5 &&
                peak.stats.confirmedSession >= 5 &&
                rrCvPre != null &&
                rrCvPre < 0.14

        val bpmMedian =
            rrMedianMs?.takeIf { enoughRrCount && it in 300.0..1500.0 }?.let { 60000.0 / it }
        val bpmInstantRecent =
            rrTail.lastOrNull()
                ?.takeIf { enoughRrCount && it in 300.0..1500.0 }
                ?.let { rr -> 60000.0 / rr }

        val bpmConfidence = when {
            !enoughRrCount -> 0.0
            else ->
                (
                    (sqiValue * 0.62) +
                        (
                            (1.0 - ((rhythmPost.irregularityIx ?: 0.6).coerceIn(0.0, 3.5) / 3.5))
                                .coerceIn(0.0, 1.0) * 0.38
                            )
                    ).coerceIn(0.0, 1.0)
        }

        val allowsDerivedVitals = physiology.ordinal >= PpgValidityState.PPG_VALID.ordinal && ppgConfirmed

        val allowsClinicalOximetry =
            allowsDerivedVitals && calibration != null && sqiValue >= 0.52

        val piUse = (ppg.roiStats.perfusionIndexGreenPct / 100.0).coerceIn(0.0, 10.5)

        val spo =
            spo2Estimator.estimate(
                calibration,
                validityStateAllowsOximetry = allowsClinicalOximetry,
                perfusionIndex = piUse,
                sqi = sqiValue,
                motionScore = fusedMotion.coerceAtMost(1.0),
                clipHighRatio = ppg.clippingHighRatio
            )

        val spoClinicalDisplay =
            if (allowsDerivedVitals) {
                spo.spo2Clinical?.takeIf { spo.clinicallyValidDisplay }
            } else {
                null
            }

        var flags = ReadingValidity.OK
        if (ppg.clippingHighRatio >= 0.08) flags = flags or ReadingValidity.CLIPPING_HIGH
        if (ppg.clippingLowRatio >= 0.05) flags = flags or ReadingValidity.CLIPPING_LOW
        if (fusedMotion >= 0.20) flags = flags or ReadingValidity.MOTION_HIGH
        if (ppg.lowLightSuspected) flags = flags or ReadingValidity.NO_FINGER
        if (!enoughRrCount) flags = flags or ReadingValidity.NOT_ENOUGH_BEATS
        if (allowsDerivedVitals && calibration == null) flags = flags or ReadingValidity.CALIBRATION_MISSING
        if (physiology.ordinal < PpgValidityState.PPG_VALID.ordinal) flags = flags or ReadingValidity.SIGNAL_INCOHERENT

        val tachySus =
            allowsDerivedVitals && rhythmPost.meanMs != null && rhythmPost.meanMs!! < 514.0
        val bradySus =
            allowsDerivedVitals && rhythmPost.meanMs != null && rhythmPost.meanMs!! > 1275.0
        val pauseSus =
            allowsDerivedVitals &&
                (
                    rrTail.lastOrNull()?.let { rr ->
                        rr > 1750 || beat?.rhythmMarker ==
                            com.forensicppg.monitor.domain.BeatRhythmMarker.PAUSE_SUSPECT
                    } ?: false
                    )

        val msgPrimary = composeMessage(
            physiology,
            fusedMotion,
            allowsDerivedVitals,
            enoughRrCount,
            spoClinicalDisplay,
            inStabilization,
            maskSustained2s
        )

        val ex = ppg.exposureDiagnostics
        val diag = DiagnosticsSnapshot(
            measuredFps = acq.measuredFpsHz,
            targetFps = acq.targetFpsHint.coerceAtLeast(15),
            frameDropCount = acq.frameDrops,
            frameJitterMeanMs = acq.jitterMs,
            torchEnabled = acq.torchEnabled,
            manualSensorApplied = acq.manualSensorApplied,
            hardwareLimitNote = ex.hardwareLimitNote,
            lastExposureNs = ex.exposureTimeNs,
            lastIso = ex.iso,
            peakConfirmedCountSession = peak.stats.confirmedSession,
            peakRejectedCountSession = peak.stats.rejectedSession,
            lastRejectionDigest = peak.stats.rejectDigest,
            spo2CalibrationStatus = calibration?.profileId ?: "sin_perfil_guardado",
            rhythmDigest =
                rhythmPost.pattern.labelEs.take(118) +
                    ";RRn=${rhythmPost.meanMs?.let { "%.0f".format(it) } ?: "-" }",
            sensorZloR = ex.sensorZloR,
            sensorZloG = ex.sensorZloG,
            sensorZloB = ex.sensorZloB,
            zloSourceNote = ex.zloSourceSummary,
            ispAcquisitionSummary = ex.ispAcquisitionSummary
        )

        val hypo =
            if (allowsDerivedVitals) {
                hypertensionHint(bpmMedian, rhythmPost, ppg.roiStats.perfusionIndexGreenPct, sqiValue)
            } else {
                null
            }

        val showWave =
            physiology.ordinal >= PpgValidityState.PPG_VALID.ordinal &&
                maskSustained2s &&
                !inStabilization &&
                sqiValue >= 0.48

        val displayWaveOut = if (showWave) dspOut.displaySmoothed else 0.0

        val sampleOut =
            ppg.copy(
                filteredPrimary = dspOut.filteredWave,
                displayWave = displayWaveOut,
                waveformDisplayAllowed = showWave,
                sqi = sqiValue,
                filteredSecondary = null
            )

        val rrRecent = tachogramMini(rrTail, 52)

        return Step(
            sample = sampleOut,
            reading = VitalReading(
                bpmInstant = if (enoughRrCount) bpmInstantRecent?.coerceIn(42.0, 200.0) else null,
                bpmSmoothed = if (enoughRrCount) bpmMedian?.coerceIn(42.0, 200.0) else null,
                bpmAverage = if (enoughRrCount) bpmMedian else null,
                bpmConfidence = bpmConfidence,
                beatsValidUsed = rrTail.size.coerceAtMost(240),
                bpmWindowSeconds = (rrTail.size / sr).coerceAtMost(72.5),
                spo2 = spoClinicalDisplay?.coerceAtMost(104.8),
                spo2Confidence = if (spoClinicalDisplay != null) spo.spo2Confidence else 0.0,
                spo2RatioR = if (allowsDerivedVitals) spo.ratioOfRatios else null,
                spo2CalibrationStatus = if (calibration == null) "NO_CALIBRADA" else "CALIBR_${calibration.calibrationSamples}pts",
                spo2WindowSecondsUsed = if (allowsDerivedVitals) 10.0 else 0.0,
                spo2ExperimentalIndex =
                    if (allowsDerivedVitals) spo.ratioOfRatios?.takeUnless { spo.clinicallyValidDisplay } else null,
                sqi = sqiValue,
                snrBandDbEstimate = spec.snrHeartDbEstimate,
                dominantHeartHz = spec.dominantFreqHz,
                perfusionIndex = ppg.roiStats.perfusionIndexGreenPct,
                maskCoverage = ppg.maskCoverage,
                contactScore = ppg.contactScore,
                motionScore = fusedMotion.coerceAtMost(1.0),
                rrMs = if (allowsDerivedVitals) rhythmPost.meanMs else null,
                rrSdnnMs = if (allowsDerivedVitals) rhythmPost.sdnn else null,
                rmssdMs = if (allowsDerivedVitals) rhythmPost.rmssd else null,
                pnn50 = if (allowsDerivedVitals) rhythmPost.pnn50 else null,
                irregularityCoefficient = if (allowsDerivedVitals) rhythmPost.irregularityIx else null,
                beatsDetected = peak.stats.confirmedSession,
                abnormalBeatCandidates = if (allowsDerivedVitals) rhythmPost.ectopicSuspects else 0,
                validityState = physiology,
                validityFlags = flags,
                rhythmPatternHint = rhythmPost.pattern,
                tachySuspected = tachySus,
                bradySuspected = bradySus,
                pauseSuspected = pauseSus,
                rrRecentMs = if (allowsDerivedVitals) rrRecent else emptyList(),
                irregularSegmentTimestampsNs =
                    if (allowsDerivedVitals) rhythm.irregularityTimestamps() else emptyList(),
                messagePrimary = msgPrimary,
                hypertensionRisk = hypo,
                peakConfirmations = peak.stats.confirmedSession,
                peakRejectedCount = peak.stats.rejectedSession,
                rejectionTrace = peak.stats.rejectDigest,
                diagnostics = diag,
                lastRawWaveY = ppg.rawGreen,
                lastFilteredWaveY = if (showWave) dspOut.displaySmoothed else null,
                clippingSuspectedHigh = ppg.clippingHighRatio >= 0.08,
                clippingSuspectedLow = ppg.clippingLowRatio >= 0.05
            ),
            confirmedBeat = if (allowsDerivedVitals) beat else null
        )
    }

    private fun tachogramMini(src: List<Double>, cap: Int): List<Double> =
        if (src.isEmpty()) emptyList() else src.takeLast(cap.coerceAtMost(src.size))

    private fun composeMessage(
        v: PpgValidityState,
        motion: Double,
        ppgOk: Boolean,
        enoughBpm: Boolean,
        spoDisp: Double?,
        stabilizing: Boolean,
        mask2s: Boolean
    ): String {
        if (stabilizing) return "Estabilizando contacto (2–3 s) — sin BPM aún"
        if (motion >= 0.20) return "Movimiento — mantener dedo y teléfono quietos"
        if (v == PpgValidityState.CLIPPING) return "Saturación — aflojá presión sobre lente/flash"
        if (v == PpgValidityState.LOW_LIGHT) return "Luz/flash insuficiente — cubrir lente y flash juntos"
        if (v == PpgValidityState.BAD_CONTACT) return "Contacto insuficiente — yema índice sobre lente+flash"
        if (!mask2s) return "Mantener cobertura estable del dedo (máscara ≥70%)"
        if (v == PpgValidityState.MOTION) return "Movimiento detectado"
        if (v == PpgValidityState.QUIET_NO_PULSE) return "Quieto pero sin pulso cardíaco verificable"
        if (v == PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL) return "NO_PPG — objeto o superficie no fisiológica"
        if (v == PpgValidityState.RAW_OPTICAL_ONLY) return "Óptico sin periodicidad cardíaca confirmada"
        if (v == PpgValidityState.PPG_CANDIDATE) return "Acumulando latidos confirmados…"
        if (!enoughBpm && ppgOk) return "PPG confirmado — calculando BPM (≥5 latidos)"
        if (spoDisp == null && ppgOk)
            return "SpO₂ requiere calibración con oxímetro de referencia"
        if (v == PpgValidityState.PPG_VALID || v == PpgValidityState.BIOMETRIC_VALID)
            return "PPG confirmado (${v.labelEs})"
        return v.labelEs
    }

    private fun hypertensionHint(
        bpm: Double?,
        r: RhythmAnalyzer.RhythmSummary,
        perfusionPct: Double,
        sqi: Double
    ): HypertensionRiskBand? {
        if (bpm == null || sqi < 0.52 || r.irregularityIx == null) return HypertensionRiskBand.UNCERTAIN
        if (rhythm.rrIntervalCount() < 8) return HypertensionRiskBand.UNCERTAIN
        val cv = r.coefficientVar ?: return HypertensionRiskBand.UNCERTAIN
        val rm = r.rmssd ?: return HypertensionRiskBand.UNCERTAIN
        val piBandOk = perfusionPct >= 53.8
        val stiffPattern = cv < 0.03 && rm < 18.8 && bpm > 71.8 && piBandOk
        val border = cv < 0.058 && rm < 25.5 && bpm > 64.9
        return when {
            stiffPattern -> HypertensionRiskBand.HYPERTENSIVE_PATTERN
            border -> HypertensionRiskBand.BORDERLINE
            else -> HypertensionRiskBand.NORMOTENSE
        }
    }

    companion object {
        fun emptyReading(): VitalReading = VitalReading()
    }
}
