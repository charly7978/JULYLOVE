import { ArrhythmiaScreening, type ArrhythmiaSummary } from './arrhythmia'
import { BeatClassifier } from './beatClassifier'
import { FingerContactDetector } from './contact'
import { HeartRateFusion } from './fusion'
import { PeakDetectorDerivative } from './peakDerivative'
import { PeakDetectorElgendi } from './peakElgendi'
import { PpgPreprocessor } from './preprocessor'
import { SignalQualityIndex } from './sqi'
import { SpectralHeartRateEstimator } from './spectral'
import { Spo2Estimator, type CalibrationProfile, type Spo2Result } from './spo2'
import type {
  BeatEvent,
  BeatType,
  CameraFrameStats,
  HypertensionRiskBand,
  MeasurementState,
  PpgSample,
  VitalReading
} from './types'
import { VALIDITY, stateAllowsMetrics } from './types'

export interface PipelineStep {
  sample: PpgSample
  reading: VitalReading
  beat: BeatEvent | null
  acceptedFrame: boolean
  spo2Debug: Spo2Result
  screening: ArrhythmiaSummary
}

export class PpgPipeline {
  private readonly sampleRate: number
  private readonly preprocessor: PpgPreprocessor
  private readonly spo2: Spo2Estimator
  private readonly spectral: SpectralHeartRateEstimator
  private readonly elgendi: PeakDetectorElgendi
  private readonly derivative: PeakDetectorDerivative
  private readonly classifier: BeatClassifier
  private readonly screening: ArrhythmiaScreening
  private readonly sqi: SignalQualityIndex
  private readonly fusion: HeartRateFusion
  private readonly contact: FingerContactDetector
  private lastBeatTsMs: number | null = null
  private totalBeats = 0
  private abnormalBeats = 0
  private targetFps: number
  private fpsMovingAvg = 0
  private fpsJitter = 0
  private lastFrameTsMs: number | null = null

  constructor(sampleRate: number) {
    this.sampleRate = sampleRate
    this.targetFps = sampleRate
    this.preprocessor = new PpgPreprocessor(sampleRate)
    this.spo2 = new Spo2Estimator(sampleRate)
    this.spectral = new SpectralHeartRateEstimator(sampleRate, { windowSeconds: 8 })
    this.elgendi = new PeakDetectorElgendi(sampleRate)
    this.derivative = new PeakDetectorDerivative(sampleRate)
    this.classifier = new BeatClassifier()
    this.screening = new ArrhythmiaScreening()
    this.sqi = new SignalQualityIndex()
    this.fusion = new HeartRateFusion()
    this.contact = new FingerContactDetector()
  }

  setTargetFps(fps: number): void {
    this.targetFps = fps
  }

  reset(): void {
    this.preprocessor.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.elgendi.reset()
    this.derivative.reset()
    this.classifier.reset()
    this.screening.reset()
    this.contact.reset()
    this.lastBeatTsMs = null
    this.totalBeats = 0
    this.abnormalBeats = 0
    this.fpsMovingAvg = 0
    this.fpsJitter = 0
    this.lastFrameTsMs = null
  }

  fpsActual(): number {
    return this.fpsMovingAvg
  }

  jitterMs(): number {
    return this.fpsJitter
  }

  process(frame: CameraFrameStats, motionScore: number, calibration: CalibrationProfile | null): PipelineStep {
    this.updateFps(frame.timestampMs)

    const pre = this.preprocessor.process(frame.redMean)
    this.spo2.push(frame.redMean, frame.greenMean, frame.blueMean)
    this.spectral.push(pre.filtered)

    const { state: contactState, flags: validityFlags } = this.contact.evaluate(
      frame,
      motionScore,
      this.fpsMovingAvg
    )

    let beat: BeatEvent | null = null
    if (stateAllowsMetrics(contactState)) {
      const peak = this.elgendi.feed(pre.filtered, frame.timestampMs)
      const confirm = this.derivative.feed(pre.filtered, frame.timestampMs)
      if (peak) {
        const prev = this.lastBeatTsMs
        const rr = prev !== null ? peak.timestampMs - prev : null
        const bpmInstant = rr !== null && rr > 0 ? 60000 / rr : null
        const raw: BeatEvent = {
          timestampMs: peak.timestampMs,
          amplitude: peak.amplitude,
          rrMs: rr,
          bpmInstant,
          quality: 0,
          type: 'NORMAL' as BeatType,
          reason: ''
        }
        const confirmed = confirm !== null || (pre.perfusionIndex > 0.3 && validityFlags === 0)
        const sqiQuick = this.estimateSqiQuick(pre.perfusionIndex, motionScore, frame.clipHighRatio)
        const classified = this.classifier.classify(raw, sqiQuick)
        if (classified.type !== 'INVALID_SIGNAL' && confirmed) {
          this.screening.ingest(classified)
          this.lastBeatTsMs = peak.timestampMs
          this.totalBeats++
          if (classified.type !== 'NORMAL') this.abnormalBeats++
          beat = classified
        }
      }
    }

    const screeningSummary = this.screening.compute(1.0)
    const sqiValue = this.sqi.evaluate({
      hasContact: stateAllowsMetrics(contactState) || contactState === 'WARMUP',
      perfusionIndex: pre.perfusionIndex,
      clipHighRatio: frame.clipHighRatio,
      clipLowRatio: frame.clipLowRatio,
      motionScore,
      fpsActual: this.fpsMovingAvg,
      fpsTarget: this.targetFps,
      spectralCoherence: 0,
      rrCv: screeningSummary.coefficientOfVariation,
      rrCount: screeningSummary.rrCount,
      roiSpatialStd: Math.sqrt(Math.max(0, frame.roiVariance))
    })

    const spec = this.spectral.estimate()
    const medianRr = this.classifier.medianRr()
    const rrBpm = medianRr !== null ? 60000 / medianRr : null
    const fused = this.fusion.fuse(rrBpm, screeningSummary.rrCount, spec.bpm, spec.coherence, sqiValue)
    const spo2Result = this.spo2.estimate(calibration, pre.perfusionIndex, sqiValue, motionScore, frame.clipHighRatio)

    let allFlags = validityFlags
    if (this.fpsMovingAvg > 1 && this.fpsMovingAvg < 18) allFlags |= VALIDITY.LOW_FPS
    if (pre.perfusionIndex < 0.3) allFlags |= VALIDITY.LOW_PERFUSION
    if (!calibration && stateAllowsMetrics(contactState)) allFlags |= VALIDITY.CALIBRATION_MISSING

    const finalState: MeasurementState = contactState
    const message = this.composeMessage(finalState, allFlags, !calibration)
    const risk = this.hypertensionRisk(fused.bpm, screeningSummary, pre.perfusionIndex, sqiValue)

    const sample: PpgSample = {
      timestampMs: frame.timestampMs,
      raw: frame.redMean,
      filtered: pre.filtered,
      display: pre.filtered,
      sqi: sqiValue,
      perfusionIndex: pre.perfusionIndex,
      motionScore,
      valid: stateAllowsMetrics(finalState)
    }

    const reading: VitalReading = {
      bpm: fused.bpm,
      bpmConfidence: fused.confidence,
      spo2: spo2Result.spo2,
      spo2Confidence: spo2Result.confidence,
      sqi: sqiValue,
      perfusionIndex: pre.perfusionIndex,
      motionScore,
      rrMs: screeningSummary.meanRr,
      rrSdnnMs: screeningSummary.sdnnMs,
      pnn50: screeningSummary.pnn50,
      beatsDetected: this.totalBeats,
      abnormalBeats: this.abnormalBeats,
      state: finalState,
      validityFlags: allFlags,
      message,
      hypertensionRisk: risk
    }

    return { sample, reading, beat, acceptedFrame: stateAllowsMetrics(finalState), spo2Debug: spo2Result, screening: screeningSummary }
  }

  private estimateSqiQuick(pi: number, motion: number, clipHigh: number): number {
    if (motion > 0.7) return 0
    if (clipHigh > 0.2) return 0.1
    const piN = Math.min(1, Math.max(0, pi / 4))
    const m = 1 - Math.min(1, Math.max(0, motion))
    return Math.min(1, Math.max(0, piN * m))
  }

  private updateFps(timestampMs: number): void {
    const prev = this.lastFrameTsMs
    if (prev !== null) {
      const dt = timestampMs - prev
      if (dt > 0) {
        const instant = 1000 / dt
        this.fpsMovingAvg = this.fpsMovingAvg === 0 ? instant : 0.9 * this.fpsMovingAvg + 0.1 * instant
        const jitter = Math.abs(dt - 1000 / Math.max(1, this.fpsMovingAvg))
        this.fpsJitter = 0.9 * this.fpsJitter + 0.1 * jitter
      }
    }
    this.lastFrameTsMs = timestampMs
  }

  private composeMessage(state: MeasurementState, flags: number, calibrationMissing: boolean): string {
    if (state === 'NO_CONTACT') return 'Coloque el dedo sobre la cámara y el flash'
    if (state === 'CONTACT_PARTIAL') return 'Contacto parcial — cubra por completo la lente'
    if (flags & VALIDITY.MOTION_HIGH) return 'Movimiento excesivo — inmovilice el dedo'
    if (flags & VALIDITY.CLIPPING_HIGH) return 'Saturación óptica — reduzca la presión'
    if (flags & VALIDITY.CLIPPING_LOW) return 'Imagen demasiado oscura — revise el flash'
    if (flags & VALIDITY.LOW_PERFUSION) return 'Baja perfusión — caliente el dedo'
    if (flags & VALIDITY.LOW_FPS) return 'FPS inestable — verifique condiciones del dispositivo'
    if (state === 'WARMUP') return 'Calentando sensor óptico — aguarde'
    if (calibrationMissing && state === 'MEASURING') {
      return 'SpO₂ requiere calibración con oxímetro de referencia'
    }
    return 'Midiendo — mantenga el dedo inmóvil'
  }

  private hypertensionRisk(
    bpm: number | null,
    screening: ArrhythmiaSummary,
    pi: number,
    sqi: number
  ): HypertensionRiskBand | null {
    if (bpm === null || sqi < 0.55 || screening.rrCount < 8) return 'UNCERTAIN'
    const cv = screening.coefficientOfVariation
    const rmssd = screening.rmssdMs
    if (cv === null || rmssd === null) return 'UNCERTAIN'
    const piBand = pi >= 1
    const stiff = cv < 0.03 && rmssd < 18 && bpm > 70 && piBand
    const borderline = cv < 0.05 && rmssd < 25 && bpm > 65
    if (stiff) return 'HYPERTENSIVE_PATTERN'
    if (borderline) return 'BORDERLINE'
    return 'NORMOTENSE'
  }
}
