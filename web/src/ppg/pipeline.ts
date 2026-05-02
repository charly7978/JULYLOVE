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
  sample: PpgSample | null
  reading: VitalReading
  beat: BeatEvent | null
  acceptedFrame: boolean
  spo2Debug: Spo2Result | null
  screening: ArrhythmiaSummary | null
}

const BLANK_READING = (state: MeasurementState, message: string): VitalReading => ({
  bpm: null,
  bpmConfidence: 0,
  spo2: null,
  spo2Confidence: 0,
  sqi: 0,
  perfusionIndex: 0,
  motionScore: 0,
  rrMs: null,
  rrSdnnMs: null,
  pnn50: null,
  beatsDetected: 0,
  abnormalBeats: 0,
  state,
  validityFlags: VALIDITY.NO_FINGER,
  message,
  hypertensionRisk: null
})

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
  private measuringSinceMs: number | null = null

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

  /** Reset completo, usado al (re)iniciar la medición. */
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
    this.measuringSinceMs = null
  }

  /** Reset parcial al perder contacto: evita arrastrar filtros/picos viejos. */
  private resetOnContactLost(): void {
    this.preprocessor.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.elgendi.reset()
    this.derivative.reset()
    this.classifier.reset()
    this.screening.reset()
    this.lastBeatTsMs = null
    this.totalBeats = 0
    this.abnormalBeats = 0
    this.measuringSinceMs = null
  }

  fpsActual(): number { return this.fpsMovingAvg }
  jitterMs(): number { return this.fpsJitter }

  process(
    frame: CameraFrameStats,
    motionScore: number,
    calibration: CalibrationProfile | null
  ): PipelineStep {
    this.updateFps(frame.timestampMs)

    // Evaluación de contacto ANTES de tocar los filtros. Si no hay contacto,
    // la salida es un reading en blanco y los filtros se resetean, de modo
    // que al volver el dedo no haya residuo.
    const pre0 = { perfusionIndex: 0, filtered: 0, detrended: 0, ac: 0, dc: 0 }
    const contactDecision = this.contact.evaluate(frame, motionScore, this.fpsMovingAvg)
    if (contactDecision.state === 'NO_CONTACT') {
      this.resetOnContactLost()
      const r = BLANK_READING('NO_CONTACT', 'Coloque el dedo sobre la cámara y el flash')
      return { sample: null, reading: r, beat: null, acceptedFrame: false, spo2Debug: null, screening: null }
    }
    if (contactDecision.state === 'CONTACT_PARTIAL') {
      this.resetOnContactLost()
      const r = BLANK_READING('CONTACT_PARTIAL', 'Contacto parcial — cubra por completo la lente')
      return { sample: null, reading: r, beat: null, acceptedFrame: false, spo2Debug: null, screening: null }
    }

    const pre = this.preprocessor.process(frame.redMean)
    this.spo2.push(frame.redMean, frame.greenMean, frame.blueMean)
    this.spectral.push(pre.filtered)

    let beat: BeatEvent | null = null
    if (stateAllowsMetrics(contactDecision.state)) {
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
        const sqiQuick = this.estimateSqiQuick(pre.perfusionIndex, motionScore, frame.clipHighRatio)
        const classified = this.classifier.classify(raw, sqiQuick)
        const confirmed = confirm !== null && pre.perfusionIndex > 0.5 && classified.type !== 'INVALID_SIGNAL'
        if (confirmed) {
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
      hasContact: stateAllowsMetrics(contactDecision.state) || contactDecision.state === 'WARMUP',
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
    const spo2Result = this.spo2.estimate(
      calibration,
      pre.perfusionIndex,
      sqiValue,
      motionScore,
      frame.clipHighRatio
    )

    let allFlags = contactDecision.flags
    if (this.fpsMovingAvg > 1 && this.fpsMovingAvg < 15) allFlags |= VALIDITY.LOW_FPS
    if (pre.perfusionIndex < 0.3) allFlags |= VALIDITY.LOW_PERFUSION
    if (!calibration && stateAllowsMetrics(contactDecision.state)) allFlags |= VALIDITY.CALIBRATION_MISSING
    if (screeningSummary.rrCount < 5) allFlags |= VALIDITY.NOT_ENOUGH_BEATS

    // Sólo si el estado permite métricas Y hay latidos confirmados mínimos
    // publicamos BPM/SpO2. Esto evita "números sin dedo".
    const canShowBpm =
      stateAllowsMetrics(contactDecision.state) &&
      fused.bpm !== null &&
      fused.confidence >= 0.4 &&
      this.totalBeats >= 4 &&
      sqiValue >= 0.4 &&
      pre.perfusionIndex >= 0.4
    const canShowSpo2 =
      canShowBpm && calibration !== null && spo2Result.spo2 !== null && spo2Result.confidence >= 0.4

    const reading: VitalReading = {
      bpm: canShowBpm ? fused.bpm : null,
      bpmConfidence: canShowBpm ? fused.confidence : 0,
      spo2: canShowSpo2 ? spo2Result.spo2 : null,
      spo2Confidence: canShowSpo2 ? spo2Result.confidence : 0,
      sqi: sqiValue,
      perfusionIndex: pre.perfusionIndex,
      motionScore,
      rrMs: canShowBpm ? screeningSummary.meanRr : null,
      rrSdnnMs: canShowBpm ? screeningSummary.sdnnMs : null,
      pnn50: canShowBpm ? screeningSummary.pnn50 : null,
      beatsDetected: this.totalBeats,
      abnormalBeats: this.abnormalBeats,
      state: contactDecision.state,
      validityFlags: allFlags,
      message: this.composeMessage(contactDecision.state, allFlags, !calibration),
      hypertensionRisk: canShowBpm
        ? this.hypertensionRisk(fused.bpm, screeningSummary, pre.perfusionIndex, sqiValue)
        : null
    }

    const sample: PpgSample = {
      timestampMs: frame.timestampMs,
      raw: frame.redMean,
      filtered: pre.filtered,
      display: pre.filtered,
      sqi: sqiValue,
      perfusionIndex: pre.perfusionIndex,
      motionScore,
      valid: stateAllowsMetrics(contactDecision.state)
    }

    return {
      sample,
      reading,
      beat,
      acceptedFrame: stateAllowsMetrics(contactDecision.state),
      spo2Debug: spo2Result,
      screening: screeningSummary
    }
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
    if (flags & VALIDITY.NOT_ENOUGH_BEATS) return 'Acumulando latidos válidos — no mover'
    if (calibrationMissing && state === 'MEASURING') return 'SpO₂ requiere calibración con oxímetro de referencia'
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
