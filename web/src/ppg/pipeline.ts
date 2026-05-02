import { ArrhythmiaScreening, type ArrhythmiaSummary } from './arrhythmia'
import { BeatClassifier } from './beatClassifier'
import { ChromaticGate } from './ChromaticGate'
import { FingerContactDetector } from './contact'
import { HeartRateFusion } from './fusion'
import { LivePpgEvidenceGate } from './LivePpgEvidenceGate'
import { PeakDetectorSsf } from './peakSsf'
import { PpgPreprocessor } from './preprocessor'
import { SignalQualityIndex } from './sqi'
import { SpectralHeartRateEstimator } from './spectral'
import { Spo2Estimator, type CalibrationProfile, type Spo2Result } from './spo2'
import { PROCESSING } from '../constants/processing'
import type {
  BeatEvent,
  BeatType,
  CameraFrameStats,
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
  bpm: 0,
  bpmConfidence: 0,
  spo2: 0,
  spo2Confidence: 0,
  sqi: 0,
  perfusionIndex: 0,
  motionScore: 0,
  rrMs: 0,
  rrSdnnMs: 0,
  pnn50: 0,
  beatsDetected: 0,
  abnormalBeats: 0,
  state,
  validityFlags: VALIDITY.NO_FINGER,
  message,
  hypertensionRisk: 'NO_VALID_PPG',
  bloodPressureSystolic: 0,
  bloodPressureDiastolic: 0,
  glucoseMgDl: 0,
  lipidsMgDl: 0,
  arrhythmiaStatus: 'NO_VALID_PPG',
  reasonCodes: ['NO_VALID_PPG']
})

/**
 * Pipeline PPG real, fail-closed, con una sola fuente de verdad biométrica.
 *
 * Cadena:
 *   frame -> preprocesado -> detector SSF -> SQI/chroma -> evidencia viva
 *   -> publicación clínica.
 *
 * Regla madre:
 *   si la evidencia viva no es válida, biomarcadores se publican en cero.
 */
export class PpgPipeline {
  private readonly fsNominal: number
  private readonly preRed: PpgPreprocessor
  private readonly preGreen: PpgPreprocessor
  private readonly spo2: Spo2Estimator
  private readonly spectral: SpectralHeartRateEstimator
  private readonly detector: PeakDetectorSsf
  private readonly classifier: BeatClassifier
  private readonly screening: ArrhythmiaScreening
  private readonly sqi: SignalQualityIndex
  private readonly fusion: HeartRateFusion
  private readonly contact: FingerContactDetector
  private readonly chroma: ChromaticGate
  private readonly evidence: LivePpgEvidenceGate

  private lastBeatTsMs: number | null = null
  private totalBeats = 0
  private abnormalBeats = 0
  private fpsMovingAvg = 0
  private fpsJitter = 0
  private lastFrameTsMs: number | null = null
  private bpmSmooth: number | null = null
  private readonly bpmEmaAlpha = 0.15

  private readonly acBufRed: Float64Array
  private readonly acBufGreen: Float64Array
  private acIdx = 0
  private acFilled = 0

  constructor(fsNominal = 30) {
    this.fsNominal = fsNominal
    this.preRed = new PpgPreprocessor(fsNominal, 0.5, 4.0)
    this.preGreen = new PpgPreprocessor(fsNominal, 0.5, 4.0)
    this.spo2 = new Spo2Estimator(fsNominal)
    this.spectral = new SpectralHeartRateEstimator(fsNominal, { windowSeconds: 8 })
    this.detector = new PeakDetectorSsf(fsNominal, { ssfWindowMs: 125, refractoryMs: 280, searchWindowMs: 240 })
    this.classifier = new BeatClassifier()
    this.screening = new ArrhythmiaScreening()
    this.sqi = new SignalQualityIndex()
    this.fusion = new HeartRateFusion()
    this.contact = new FingerContactDetector()
    this.chroma = new ChromaticGate()
    this.evidence = new LivePpgEvidenceGate()
    const bufN = Math.round(fsNominal * 3)
    this.acBufRed = new Float64Array(bufN)
    this.acBufGreen = new Float64Array(bufN)
  }

  setTargetFps(_: number): void { /* fs nominal fija; el pipeline procesa 1 muestra por frame */ }

  reset(): void {
    this.preRed.reset()
    this.preGreen.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.detector.reset()
    this.classifier.reset()
    this.screening.reset()
    this.contact.reset()
    this.evidence.reset()
    this.lastBeatTsMs = null
    this.totalBeats = 0
    this.abnormalBeats = 0
    this.fpsMovingAvg = 0
    this.fpsJitter = 0
    this.lastFrameTsMs = null
    this.bpmSmooth = null
    this.acBufRed.fill(0)
    this.acBufGreen.fill(0)
    this.acIdx = 0
    this.acFilled = 0
  }

  private resetOnContactLost(): void {
    this.preRed.reset()
    this.preGreen.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.detector.reset()
    this.classifier.reset()
    this.screening.reset()
    this.evidence.reset()
    this.lastBeatTsMs = null
    this.totalBeats = 0
    this.abnormalBeats = 0
    this.bpmSmooth = null
    this.acBufRed.fill(0)
    this.acBufGreen.fill(0)
    this.acIdx = 0
    this.acFilled = 0
  }

  fpsActual(): number { return this.fpsMovingAvg }
  jitterMs(): number { return this.fpsJitter }

  process(
    frame: CameraFrameStats,
    motionScore: number,
    calibration: CalibrationProfile | null
  ): PipelineStep {
    this.updateFps(frame.timestampMs)

    const contactDecision = this.contact.evaluate(frame, motionScore, this.fpsMovingAvg)

    if (contactDecision.state === 'NO_CONTACT') {
      this.resetOnContactLost()
      const reasonCodes = ['NO_VALID_PPG', ...contactDecision.reasonCodes]
      return {
        sample: null,
        reading: {
          ...BLANK_READING('NO_CONTACT', 'Coloque el dedo sobre la cámara y el flash'),
          reasonCodes
        },
        beat: null,
        acceptedFrame: false,
        spo2Debug: null,
        screening: null
      }
    }

    // Alimentamos los filtros siempre durante contacto probable para que
    // converjan antes de alcanzar VALID_LIVE_PPG.
    const redOut = this.preRed.process(frame.redMean)
    const greenOut = this.preGreen.process(frame.greenMean)
    this.spo2.push(frame.redMean, frame.greenMean, frame.blueMean)

    this.acBufRed[this.acIdx] = redOut.filtered
    this.acBufGreen[this.acIdx] = greenOut.filtered
    this.acIdx = (this.acIdx + 1) % this.acBufRed.length
    if (this.acFilled < this.acBufRed.length) this.acFilled++

    const ampRed = amplitude(this.acBufRed, this.acFilled)
    const ampGreen = amplitude(this.acBufGreen, this.acFilled)
    const useGreen = ampGreen > ampRed * 1.05
    const chosenFiltered = useGreen ? greenOut.filtered : redOut.filtered
    const chosenDisplay = useGreen ? greenOut.display : redOut.display
    const chosenPolarity = 1
    const chosenPi = useGreen ? greenOut.perfusionIndex : redOut.perfusionIndex

    this.spectral.push(-chosenFiltered)

    const canAnalyze = this.acFilled >= Math.round(this.fsNominal * 2)
    let beatCandidate: BeatEvent | null = null
    let pulseEvidence = false
    if (canAnalyze) {
      // El detector SSF ya valida rango fisiológico, coherencia y amplitud
      // internamente, así que aquí sólo aplicamos SQI mínimo.
      const d = this.detector.feed(chosenFiltered, frame.timestampMs, chosenPolarity)
      if (d) {
        const prev = this.lastBeatTsMs
        const rr = prev !== null ? d.timestampMs - prev : null
        const bpmInstant = rr !== null && rr > 0 ? 60000 / rr : null
        const sqiQuick = this.estimateSqiQuick(chosenPi, motionScore, frame.clipHighRatio)
        if (sqiQuick >= PROCESSING.MIN_SQI_QUICK_FOR_BEAT) {
          const raw: BeatEvent = {
            timestampMs: d.timestampMs,
            amplitude: d.amplitude,
            rrMs: rr,
            bpmInstant,
            quality: sqiQuick,
            type: 'NORMAL' as BeatType,
            reason: ''
          }
          this.lastBeatTsMs = d.timestampMs
          this.totalBeats++
          beatCandidate = raw
          pulseEvidence = true
          const classified = this.classifier.classify(raw, sqiQuick)
          if (classified.type !== 'INVALID_SIGNAL') {
            this.screening.ingest(classified)
            beatCandidate = classified
            if (classified.type !== 'NORMAL') this.abnormalBeats++
          }
        }
      }
    }

    const screeningSummary = this.screening.compute(1.0)
    const sqiValue = this.sqi.evaluate({
      hasContact: true,
      perfusionIndex: chosenPi,
      clipHighRatio: frame.clipHighRatio,
      clipLowRatio: frame.clipLowRatio,
      motionScore,
      fpsActual: this.fpsMovingAvg || this.fsNominal,
      fpsTarget: this.fsNominal,
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
      chosenPi,
      sqiValue,
      motionScore,
      frame.clipHighRatio
    )

    const chromaResult = this.chroma.evaluate(frame)
    const forceValidFromSustainedRhythm =
      this.totalBeats >= PROCESSING.MIN_VALID_BEATS &&
      screeningSummary.rrCount >= PROCESSING.MIN_VALID_RR_COUNT &&
      sqiValue >= PROCESSING.MIN_SQI_FOR_VALID &&
      chromaResult.passed &&
      chosenPi >= PROCESSING.MIN_PERFUSION_FOR_VALID &&
      motionScore <= PROCESSING.MAX_MOTION_FOR_VALID
    const evidenceResult = this.evidence.evaluate({
      chromaPassed: chromaResult.passed,
      hasPulseEvidence: pulseEvidence || forceValidFromSustainedRhythm,
      sqi: sqiValue,
      perfusionIndex: chosenPi,
      motionScore,
      clipHighRatio: frame.clipHighRatio,
      clipLowRatio: frame.clipLowRatio
    })

    let allFlags = contactDecision.flags | chromaResult.flags
    if (this.fpsMovingAvg > 1 && this.fpsMovingAvg < 12) allFlags |= VALIDITY.LOW_FPS
    if (chosenPi < 0.2) allFlags |= VALIDITY.LOW_PERFUSION
    if (screeningSummary.rrCount < PROCESSING.MIN_VALID_RR_COUNT) allFlags |= VALIDITY.NOT_ENOUGH_BEATS

    const finalState = evidenceResult.state === 'VALID_LIVE_PPG' ? 'VALID_LIVE_PPG' : 'PROBABLE_PPG'

    const canShowBpm =
      finalState === 'VALID_LIVE_PPG' &&
      evidenceResult.shouldPublish &&
      fused.bpm !== null &&
      screeningSummary.rrCount >= PROCESSING.MIN_VALID_RR_COUNT &&
      sqiValue >= PROCESSING.MIN_SQI_FOR_VALID
    const canShowSpo2 = canShowBpm && spo2Result.spo2 !== null
    const canScreenRhythm = finalState === 'VALID_LIVE_PPG' && screeningSummary.rrCount >= PROCESSING.MIN_VALID_RR_COUNT

    // EMA del BPM publicado: evita saltos 70→120→90 entre RR consecutivos.
    let bpmPublished = 0
    if (canShowBpm && fused.bpm !== null) {
      if (this.bpmSmooth === null) this.bpmSmooth = fused.bpm
      else this.bpmSmooth += this.bpmEmaAlpha * (fused.bpm - this.bpmSmooth)
      bpmPublished = Math.max(0, this.bpmSmooth)
    } else {
      this.bpmSmooth = null
    }

    const sample: PpgSample = {
      timestampMs: frame.timestampMs,
      raw: useGreen ? frame.greenMean : frame.redMean,
      filtered: chosenFiltered,
      display: -chosenDisplay,
      sqi: sqiValue,
      perfusionIndex: chosenPi,
      motionScore,
      valid: finalState === 'VALID_LIVE_PPG'
    }

    const combinedReasons = [
      ...contactDecision.reasonCodes,
      ...chromaResult.reasonCodes,
      ...evidenceResult.reasonCodes
    ]
    if (spo2Result.reason !== 'ok') combinedReasons.push(`SPO2_${spo2Result.reason}`)
    const reasonCodes = combinedReasons.length > 0 ? Array.from(new Set(combinedReasons)) : ['PPG_VALID']

    const reading: VitalReading = {
      bpm: bpmPublished,
      bpmConfidence: canShowBpm ? fused.confidence : 0,
      spo2: canShowSpo2 ? (spo2Result.spo2 ?? 0) : 0,
      spo2Confidence: canShowSpo2 ? spo2Result.confidence : 0,
      sqi: sqiValue,
      perfusionIndex: chosenPi,
      motionScore,
      rrMs: canShowBpm ? (screeningSummary.meanRr ?? 0) : 0,
      rrSdnnMs: canShowBpm ? (screeningSummary.sdnnMs ?? 0) : 0,
      pnn50: canShowBpm ? (screeningSummary.pnn50 ?? 0) : 0,
      beatsDetected: this.totalBeats,
      abnormalBeats: this.abnormalBeats,
      state: finalState,
      validityFlags: allFlags,
      message: this.composeMessage(finalState, allFlags, evidenceResult.reasonCodes),
      hypertensionRisk: 'NO_VALID_PPG',
      bloodPressureSystolic: 0,
      bloodPressureDiastolic: 0,
      glucoseMgDl: 0,
      lipidsMgDl: 0,
      arrhythmiaStatus: canScreenRhythm
        ? screeningSummary.flagIrregular || this.abnormalBeats > 0
          ? 'PATRON_IRREGULAR'
          : 'SIN_HALLAZGOS'
        : 'NO_VALID_PPG',
      reasonCodes
    }

    return {
      sample,
      reading,
      beat: finalState === 'VALID_LIVE_PPG' ? beatCandidate : null,
      acceptedFrame: stateAllowsMetrics(finalState),
      spo2Debug: spo2Result,
      screening: screeningSummary
    }
  }

  private estimateSqiQuick(pi: number, motion: number, clipHigh: number): number {
    if (motion > PROCESSING.MAX_MOTION_FOR_VALID) return 0
    const piN = Math.min(1, Math.max(0, (pi - PROCESSING.MIN_PERFUSION_FOR_VALID) / 1.2))
    const m = 1 - Math.min(1, Math.max(0, motion))
    const c = 1 - Math.min(1, Math.max(0, clipHigh / 0.3))
    return Math.min(1, Math.max(0, piN * m * c))
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

  private composeMessage(
    state: MeasurementState,
    flags: number,
    evidenceReasons: string[]
  ): string {
    if (state === 'NO_CONTACT') return 'Coloque el dedo sobre la cámara y el flash'
    if (state === 'PROBABLE_PPG') return 'Adquiriendo evidencia pulsátil — mantenga el dedo inmóvil'
    if (flags & VALIDITY.MOTION_HIGH) return 'Movimiento excesivo — inmovilice el dedo'
    if (flags & VALIDITY.CLIPPING_HIGH) return 'Saturación óptica — reduzca la presión'
    if (flags & VALIDITY.CLIPPING_LOW) return 'Imagen demasiado oscura — revise el flash'
    if (flags & VALIDITY.LOW_PERFUSION) return 'Baja perfusión — caliente el dedo'
    if (flags & VALIDITY.LOW_FPS) return 'FPS inestable — verifique condiciones del dispositivo'
    if (state !== 'VALID_LIVE_PPG') return 'Entrada no validada'
    if (evidenceReasons.length > 0) return 'PPG inestable — sostenga presión y quietud'
    return 'PPG válido en vivo'
  }
}

function amplitude(buf: Float64Array, filled: number): number {
  if (filled < 3) return 0
  let mx = -Infinity
  let mn = Infinity
  for (let i = 0; i < filled; i++) {
    const v = buf[i]
    if (v > mx) mx = v
    if (v < mn) mn = v
  }
  return mx - mn
}
