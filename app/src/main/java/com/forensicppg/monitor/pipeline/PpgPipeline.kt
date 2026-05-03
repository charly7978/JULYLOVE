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

/** Orquesta única: muestra → DSP → clasificación evidencial → picos confirmados → SpO₂/HRV sólo válidos. */
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

    fun reset() {
        dsp.reset(); rhythm.reset(); peak.reset(); spo2Estimator.reset()
    }

    fun peakStats(): PpgPeakDetector.DetectorStats = peak.stats

    fun process(
        ppg: PpgSample,
        imuMotion01: Double,
        calibration: CalibrationProfile?,
        acq: AcquisitionMetrics
    ): Step {
        spo2Estimator.push(ppg.rawRed, ppg.rawGreen, ppg.rawBlue)

        val dspOut = dsp.ingest(ppg)
        val spec = dspOut.spectrumSummary

        val fusedMotion = kotlin.math.min(
            1.0,
            kotlin.math.max(ppg.motionScoreOptical, imuMotion01.coerceIn(0.0, 1.0))
        )

        /** Composición antes de nuevo RR (histograma tachograma estable) */
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
                rrCount = rrN
            )
        )

        val physiology = PpgPhysiologyClassifier.classify(
            sqiComposite = sqiValue,
            spectral = spec,
            opticalMotionSmoothed = fusedMotion.coerceAtMost(1.05),
            clippingHighRatio = ppg.clippingHighRatio,
            roiRedDominance = ppg.roiStats.redDominanceRg,
            greenAcDcBandEstimate = ppg.roiStats.greenAcDc
        )

        val beat = peak.onSample(
            timestampNs = ppg.timestampNs,
            filteredWave = dspOut.filteredWave,
            sqiComposite = sqiValue,
            validityState = physiology,
            opticalMotionSmoothed = fusedMotion.coerceAtMost(1.5)
        )
        beat?.let { b ->
            rhythm.registerRr(b.rrIntervalMs, b.timestampNs, b.rhythmMarker)
        }

        val rhythmPost = rhythm.summarize()
        val rrTail = rhythm.tachogramTail(18)
        val rrMedianMs = rrTail.takeIf { it.size >= 4 }
            ?.sorted()
            ?.let { v -> v[v.size / 2] }

        val enoughRrCount = rrTail.size >= 3 && peak.stats.confirmedSession >= 3
        val bpmMedian =
            rrMedianMs?.takeIf { enoughRrCount && it in 268.0..1900.0 }?.let { 60000.0 / it }
        val bpmInstantRecent =
            rrTail.lastOrNull()
                ?.takeIf { enoughRrCount && it in 268.0..1900.0 }
                ?.let { rr -> 60000.0 / rr }

        val bpmConfidence = when {
            !enoughRrCount -> 0.0
            else ->
                (
                    (sqiValue * 0.58) +
                        (
                            (1.0 - ((rhythmPost.irregularityIx ?: 0.6).coerceIn(0.0, 3.5) / 3.5))
                                .coerceIn(0.0, 1.0) * 0.42
                            )
                    ).coerceIn(0.0, 1.0)
        }

        /** SpO₂ clínica sólo con evidencia y calibración presente según clasificador y estimador. */
        val allowsClinicalOximetry =
            physiology.ordinal >= PpgValidityState.PPG_VALID.ordinal &&
                calibration != null && sqiValue >= 0.52

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

        val spoClinicalDisplay = spo.spo2Clinical?.takeIf { spo.clinicallyValidDisplay && spo.spo2Clinical != null }

        /** Flags bitmask */
        var flags = ReadingValidity.OK
        if (ppg.clippingHighRatio > 0.18) flags = flags or ReadingValidity.CLIPPING_HIGH
        if (ppg.lowLightSuspected || ppg.clippingLowRatio > 0.22) flags = flags or ReadingValidity.CLIPPING_LOW
        if (fusedMotion > 0.70) flags = flags or ReadingValidity.MOTION_HIGH
        if (ppg.lowLightSuspected) flags = flags or ReadingValidity.NO_FINGER /* reutilizada como cue de baja luminosidad táctil */
        if (!enoughRrCount || bpmMedian == null) flags = flags or ReadingValidity.NOT_ENOUGH_BEATS
        if (calibration == null && physiology.ordinal >= PpgValidityState.PPG_VALID.ordinal) {
            flags = flags or ReadingValidity.CALIBRATION_MISSING
        }
        if (physiology.ordinal < PpgValidityState.PPG_VALID.ordinal) flags = flags or ReadingValidity.SIGNAL_INCOHERENT

        val tachySus = rhythmPost.meanMs != null && rhythmPost.meanMs!! < 514.0
        val bradySus = rhythmPost.meanMs != null && rhythmPost.meanMs!! > 1275.0
        val pauseSus =
            rrTail.lastOrNull()?.let { rr ->
                rr > 1750 || beat?.rhythmMarker ==
                    com.forensicppg.monitor.domain.BeatRhythmMarker.PAUSE_SUSPECT
            } ?: false

        val msgPrimary = composeMessage(physiology, flags, fusedMotion, calibration, enoughRrCount, spoClinicalDisplay)

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
            hypertensionHint(bpmMedian, rhythmPost, ppg.roiStats.perfusionIndexGreenPct, sqiValue)

        val sampleOut =
            ppg.copy(
                filteredPrimary = dspOut.filteredWave,
                displayWave = dspOut.displaySmoothed,
                sqi = sqiValue,
                filteredSecondary = null
            )

        val rrRecent = tachogramMini(rrTail, 52)

        return Step(
            sample = sampleOut,
            reading = VitalReading(
                bpmInstant = if (enoughRrCount) bpmInstantRecent?.coerceIn(44.0, 229.0) else null,
                bpmSmoothed = if (enoughRrCount) bpmMedian?.coerceIn(44.0, 218.9) else null,
                bpmAverage = if (enoughRrCount) bpmMedian else null,
                bpmConfidence = bpmConfidence,
                beatsValidUsed = rrTail.size.coerceAtMost(240),
                bpmWindowSeconds = (rrTail.size / sr).coerceAtMost(72.5),
                spo2 = spoClinicalDisplay?.coerceAtMost(104.8),
                spo2Confidence = if (spoClinicalDisplay != null) spo.spo2Confidence else 0.0,
                spo2RatioR = spo.ratioOfRatios,
                spo2CalibrationStatus = if (calibration == null) "NO_CALIBRADA" else "CALIBR_${calibration.calibrationSamples}pts",
                spo2WindowSecondsUsed = 10.0,
                spo2ExperimentalIndex =
                    spo.ratioOfRatios?.takeUnless { spo.clinicallyValidDisplay },
                sqi = sqiValue,
                snrBandDbEstimate = spec.snrHeartDbEstimate,
                dominantHeartHz = spec.dominantFreqHz,
                perfusionIndex = ppg.roiStats.perfusionIndexGreenPct,
                motionScore = fusedMotion.coerceAtMost(1.3),
                rrMs = rhythmPost.meanMs,
                rrSdnnMs = rhythmPost.sdnn,
                rmssdMs = rhythmPost.rmssd,
                pnn50 = rhythmPost.pnn50,
                irregularityCoefficient = rhythmPost.irregularityIx,
                beatsDetected = peak.stats.confirmedSession,
                abnormalBeatCandidates = rhythmPost.ectopicSuspects,
                validityState = physiology,
                validityFlags = flags,
                rhythmPatternHint = rhythmPost.pattern,
                tachySuspected = tachySus,
                bradySuspected = bradySus,
                pauseSuspected = pauseSus,
                rrRecentMs = rrRecent,
                irregularSegmentTimestampsNs = rhythm.irregularityTimestamps(),
                messagePrimary = msgPrimary,
                hypertensionRisk = hypo,
                peakConfirmations = peak.stats.confirmedSession,
                peakRejectedCount = peak.stats.rejectedSession,
                rejectionTrace = peak.stats.rejectDigest,
                diagnostics = diag,
                lastRawWaveY = ppg.rawGreen,
                lastFilteredWaveY = dspOut.displaySmoothed,
                clippingSuspectedHigh = ppg.clippingHighRatio > 0.11,
                clippingSuspectedLow = ppg.clippingLowRatio > 0.16
            ),
            confirmedBeat = beat
        )
    }

    private fun tachogramMini(src: List<Double>, cap: Int): List<Double> =
        if (src.isEmpty()) emptyList() else src.takeLast(cap.coerceAtMost(src.size))

    private fun composeMessage(
        v: PpgValidityState,
        flags: Int,
        motion: Double,
        cal: CalibrationProfile?,
        enoughBpm: Boolean,
        spoDisp: Double?
    ): String {
        if (flags and ReadingValidity.MOTION_HIGH != 0) return "Movimiento alto — reduzca sacudidas"
        if (flags and ReadingValidity.CLIPPING_HIGH != 0) return "Saturación óptica / clip alto"
        if (flags and ReadingValidity.CLIPPING_LOW != 0) return "Señal oscura — flash o contacto"
        if (v == PpgValidityState.NO_PHYSIOLOGICAL_SIGNAL) return "Señal óptica no fisiológica (sin PPG confirmado)"
        if (v == PpgValidityState.RAW_OPTICAL_ONLY) return "Óptico crudo — sin componente cardíaco verificable"
        if (v == PpgValidityState.PPG_CANDIDATE) return "PPG candidato — acumulando evidencia de latidos"
        if (!enoughBpm) return "BPM calculando — sin intervalos RR confirmados suficientes"
        if (cal == null && spoDisp == null && v.ordinal >= PpgValidityState.PPG_VALID.ordinal)
            return "SpO₂ requiere calibración contra oxímetro de referencia"
        if (spoDisp == null && v.ordinal >= PpgValidityState.PPG_VALID.ordinal)
            return "Índice ratio-of-ratios sólo experimental (no valor clínico)"
        return "Monitor activo (${v.labelEs.lowercase()})"
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
