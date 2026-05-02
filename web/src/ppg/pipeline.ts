import { ArrhythmiaScreening, type ArrhythmiaSummary } from './arrhythmia'
import { BeatClassifier } from './beatClassifier'
import { FingerContactDetector } from './contact'
import { HeartRateFusion } from './fusion'
import { PeakDetectorRobust } from './peakRobust'
import { PpgPreprocessor } from './preprocessor'
import { UniformResampler } from './resampler'
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

/**
 * Pipeline PPG profesional.
 *
 * La cámara del celular sobre el dedo actúa como reflectómetro: en sístole la
 * sangre absorbe más luz → el canal ROJO BAJA, y el canal VERDE baja de forma
 * todavía más marcada (mayor absorbancia de hemoglobina). Trabajamos con
 * ambos canales en paralelo, seleccionando automáticamente el que aporte
 * mayor amplitud AC filtrada. El detector robusto normaliza la polaridad
 * internamente.
 *
 * La frecuencia de muestreo se fija en 30 Hz mediante el `UniformResampler`
 * (independiente del jitter de `requestAnimationFrame`). Todo el DSP corre a
 * 30 Hz estable: detrender 4 s, bandpass Butterworth orden 4 (0.5-4 Hz),
 * detector robusto Pan-Tompkins-like, screening HRV.
 */
export class PpgPipeline {
  private readonly fs: number
  private readonly resampler: UniformResampler
  private readonly preRed: PpgPreprocessor
  private readonly preGreen: PpgPreprocessor
  private readonly spo2: Spo2Estimator
  private readonly spectral: SpectralHeartRateEstimator
  private readonly detector: PeakDetectorRobust
  private readonly classifier: BeatClassifier
  private readonly screening: ArrhythmiaScreening
  private readonly sqi: SignalQualityIndex
  private readonly fusion: HeartRateFusion
  private readonly contact: FingerContactDetector

  private lastBeatTsMs: number | null = null
  private totalBeats = 0
  private abnormalBeats = 0
  private fpsMovingAvg = 0
  private fpsJitter = 0
  private lastFrameTsMs: number | null = null
  private measuringSinceMs: number | null = null

  // Buffer para detectar qué canal tiene mayor amplitud AC reciente.
  private readonly acBufRed: Float64Array
  private readonly acBufGreen: Float64Array
  private acIdx = 0
  private acFilled = 0

  constructor(fs = 30) {
    this.fs = fs
    this.resampler = new UniformResampler(fs)
    this.preRed = new PpgPreprocessor(fs, 0.5, 4.0)
    this.preGreen = new PpgPreprocessor(fs, 0.5, 4.0)
    this.spo2 = new Spo2Estimator(fs)
    this.spectral = new SpectralHeartRateEstimator(fs, { windowSeconds: 8 })
    this.detector = new PeakDetectorRobust(fs, { integWindowMs: 150, refractoryMs: 260 })
    this.classifier = new BeatClassifier()
    this.screening = new ArrhythmiaScreening()
    this.sqi = new SignalQualityIndex()
    this.fusion = new HeartRateFusion()
    this.contact = new FingerContactDetector()
    const bufN = Math.round(fs * 3)
    this.acBufRed = new Float64Array(bufN)
    this.acBufGreen = new Float64Array(bufN)
  }

  setTargetFps(_: number): void { /* fs queda fijo; se ignora */ }

  reset(): void {
    this.resampler.reset()
    this.preRed.reset()
    this.preGreen.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.detector.reset()
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
    this.acBufRed.fill(0)
    this.acBufGreen.fill(0)
    this.acIdx = 0
    this.acFilled = 0
  }

  private resetOnContactLost(): void {
    this.resampler.reset()
    this.preRed.reset()
    this.preGreen.reset()
    this.spo2.reset()
    this.spectral.reset()
    this.detector.reset()
    this.classifier.reset()
    this.screening.reset()
    this.lastBeatTsMs = null
    this.totalBeats = 0
    this.abnormalBeats = 0
    this.measuringSinceMs = null
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
      return {
        sample: null,
        reading: BLANK_READING('NO_CONTACT', 'Coloque el dedo sobre la cámara y el flash'),
        beat: null,
        acceptedFrame: false,
        spo2Debug: null,
        screening: null
      }
    }
    if (contactDecision.state === 'CONTACT_PARTIAL') {
      // Estado transitorio "subiendo al contacto". NO reseteamos el pipeline
      // porque eso destruiría el detrender/bandpass que recién empezó a
      // converger; sólo evitamos publicar métricas.
      return {
        sample: null,
        reading: BLANK_READING('CONTACT_PARTIAL', 'Acomodando dedo — manténgalo quieto'),
        beat: null,
        acceptedFrame: false,
        spo2Debug: null,
        screening: null
      }
    }

    // Remuestreamos a fs fijo; emite 0+ muestras interpoladas.
    const uniform = this.resampler.push(
      frame.timestampMs,
      frame.redMean,
      frame.greenMean,
      frame.blueMean
    )

    let lastSample: PpgSample | null = null
    let lastBeat: BeatEvent | null = null
    let lastSqi = 0
    let lastPi = 0

    for (const s of uniform) {
      const redOut = this.preRed.process(s.red)
      const greenOut = this.preGreen.process(s.green)
      this.spo2.push(s.red, s.green, s.blue)

      this.acBufRed[this.acIdx] = redOut.filtered
      this.acBufGreen[this.acIdx] = greenOut.filtered
      this.acIdx = (this.acIdx + 1) % this.acBufRed.length
      if (this.acFilled < this.acBufRed.length) this.acFilled++

      const ampRed = amplitude(this.acBufRed, this.acFilled)
      const ampGreen = amplitude(this.acBufGreen, this.acFilled)
      // Elegimos el canal con mayor AC reciente. El verde suele ganar con
      // buena perfusión; el rojo gana con baja perfusión pero limpio.
      const useGreen = ampGreen > ampRed * 1.05
      const chosenFiltered = useGreen ? greenOut.filtered : redOut.filtered
      const chosenPolarity = useGreen ? -1 : -1 // Ambos bajan en sístole.
      const chosenPi = useGreen ? greenOut.perfusionIndex : redOut.perfusionIndex

      this.spectral.push(-chosenFiltered)
      lastPi = chosenPi

      let beat: BeatEvent | null = null
      if (stateAllowsMetrics(contactDecision.state)) {
        const d = this.detector.feed(chosenFiltered, s.ts, chosenPolarity)
        if (d) {
          const prev = this.lastBeatTsMs
          const rr = prev !== null ? d.timestampMs - prev : null
          const bpmInstant = rr !== null && rr > 0 ? 60000 / rr : null
          const sqiQuick = this.estimateSqiQuick(chosenPi, motionScore, frame.clipHighRatio)
          const raw: BeatEvent = {
            timestampMs: d.timestampMs,
            amplitude: d.amplitude,
            rrMs: rr,
            bpmInstant,
            quality: sqiQuick,
            type: 'NORMAL' as BeatType,
            reason: ''
          }
          const classified = this.classifier.classify(raw, sqiQuick)
          if (classified.type !== 'INVALID_SIGNAL') {
            this.screening.ingest(classified)
            this.lastBeatTsMs = d.timestampMs
            this.totalBeats++
            if (classified.type !== 'NORMAL') this.abnormalBeats++
            beat = classified
          }
        }
      }

      const screeningSummary = this.screening.compute(1.0)
      const sqiValue = this.sqi.evaluate({
        hasContact: stateAllowsMetrics(contactDecision.state) || contactDecision.state === 'WARMUP',
        perfusionIndex: chosenPi,
        clipHighRatio: frame.clipHighRatio,
        clipLowRatio: frame.clipLowRatio,
        motionScore,
        fpsActual: this.fs, // trabajamos a fs fijo
        fpsTarget: this.fs,
        spectralCoherence: 0,
        rrCv: screeningSummary.coefficientOfVariation,
        rrCount: screeningSummary.rrCount,
        roiSpatialStd: Math.sqrt(Math.max(0, frame.roiVariance))
      })
      lastSqi = sqiValue

      lastSample = {
        timestampMs: s.ts,
        raw: useGreen ? s.green : s.red,
        filtered: -chosenFiltered, // Para que en pantalla la sístole vaya hacia arriba.
        display: -chosenFiltered,
        sqi: sqiValue,
        perfusionIndex: chosenPi,
        motionScore,
        valid: stateAllowsMetrics(contactDecision.state)
      }
      if (beat) lastBeat = beat
    }

    if (lastSample === null) {
      // Aún no acumulamos muestras (primer frame tras contacto).
      return {
        sample: null,
        reading: BLANK_READING(contactDecision.state, 'Calentando sensor óptico — aguarde'),
        beat: null,
        acceptedFrame: false,
        spo2Debug: null,
        screening: null
      }
    }

    const screeningSummary = this.screening.compute(1.0)
    const spec = this.spectral.estimate()
    const medianRr = this.classifier.medianRr()
    const rrBpm = medianRr !== null ? 60000 / medianRr : null
    const fused = this.fusion.fuse(rrBpm, screeningSummary.rrCount, spec.bpm, spec.coherence, lastSqi)
    const spo2Result = this.spo2.estimate(
      calibration,
      lastPi,
      lastSqi,
      motionScore,
      frame.clipHighRatio
    )

    let allFlags = contactDecision.flags
    if (this.fpsMovingAvg > 1 && this.fpsMovingAvg < 15) allFlags |= VALIDITY.LOW_FPS
    if (lastPi < 0.3) allFlags |= VALIDITY.LOW_PERFUSION
    if (!calibration && stateAllowsMetrics(contactDecision.state)) allFlags |= VALIDITY.CALIBRATION_MISSING
    if (screeningSummary.rrCount < 3) allFlags |= VALIDITY.NOT_ENOUGH_BEATS

    // Gate BPM: basta con ≥3 latidos reales y SQI aceptable.
    const canShowBpm =
      stateAllowsMetrics(contactDecision.state) &&
      fused.bpm !== null &&
      this.totalBeats >= 3 &&
      lastSqi >= 0.25

    // SpO2 provisional sin calibración con confianza explícita.
    const canShowSpo2 =
      canShowBpm && spo2Result.spo2 !== null

    const reading: VitalReading = {
      bpm: canShowBpm ? fused.bpm : null,
      bpmConfidence: canShowBpm ? fused.confidence : 0,
      spo2: canShowSpo2 ? spo2Result.spo2 : null,
      spo2Confidence: canShowSpo2 ? spo2Result.confidence : 0,
      sqi: lastSqi,
      perfusionIndex: lastPi,
      motionScore,
      rrMs: canShowBpm ? screeningSummary.meanRr : null,
      rrSdnnMs: canShowBpm ? screeningSummary.sdnnMs : null,
      pnn50: canShowBpm ? screeningSummary.pnn50 : null,
      beatsDetected: this.totalBeats,
      abnormalBeats: this.abnormalBeats,
      state: contactDecision.state,
      validityFlags: allFlags,
      message: this.composeMessage(contactDecision.state, allFlags, !calibration, canShowBpm),
      hypertensionRisk: canShowBpm
        ? this.hypertensionRisk(fused.bpm, screeningSummary, lastPi, lastSqi)
        : null
    }

    return {
      sample: lastSample,
      reading,
      beat: lastBeat,
      acceptedFrame: stateAllowsMetrics(contactDecision.state),
      spo2Debug: spo2Result,
      screening: screeningSummary
    }
  }

  private estimateSqiQuick(pi: number, motion: number, clipHigh: number): number {
    if (motion > 0.7) return 0
    if (clipHigh > 0.25) return 0.1
    const piN = Math.min(1, Math.max(0, pi / 3))
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

  private composeMessage(
    state: MeasurementState,
    flags: number,
    calibrationMissing: boolean,
    measuringBpm: boolean
  ): string {
    if (state === 'NO_CONTACT') return 'Coloque el dedo sobre la cámara y el flash'
    if (state === 'CONTACT_PARTIAL') return 'Contacto parcial — cubra por completo la lente'
    if (flags & VALIDITY.MOTION_HIGH) return 'Movimiento excesivo — inmovilice el dedo'
    if (flags & VALIDITY.CLIPPING_HIGH) return 'Saturación óptica — reduzca la presión'
    if (flags & VALIDITY.CLIPPING_LOW) return 'Imagen demasiado oscura — revise el flash'
    if (flags & VALIDITY.LOW_PERFUSION) return 'Baja perfusión — caliente el dedo'
    if (flags & VALIDITY.LOW_FPS) return 'FPS inestable — verifique condiciones del dispositivo'
    if (state === 'WARMUP') return 'Calentando sensor óptico — aguarde'
    if (!measuringBpm) return 'Acumulando latidos válidos — no mover'
    if (calibrationMissing) return 'SpO₂ estimación provisional — calibrar para uso clínico'
    return 'Midiendo — mantenga el dedo inmóvil'
  }

  private hypertensionRisk(
    bpm: number | null,
    screening: ArrhythmiaSummary,
    pi: number,
    sqi: number
  ): HypertensionRiskBand | null {
    if (bpm === null || sqi < 0.4 || screening.rrCount < 8) return 'UNCERTAIN'
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
