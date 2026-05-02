// Tipos compartidos del pipeline PPG. Todos los valores numéricos que pueden
// no existir se modelan como `number | null`; jamás se usan defaults cosméticos.

export interface CameraFrameStats {
  timestampMs: number
  width: number
  height: number
  redMean: number
  greenMean: number
  blueMean: number
  clipHighRatio: number
  clipLowRatio: number
  roiCoverage: number
  roiVariance: number
}

export interface PpgSample {
  timestampMs: number
  raw: number
  /** Señal post-bandpass, usada para análisis (detección de picos). */
  filtered: number
  /** Señal morfológica (detrended + LP suave), usada para dibujar. */
  display: number
  sqi: number
  perfusionIndex: number
  motionScore: number
  valid: boolean
}

export type BeatType =
  | 'NORMAL'
  | 'SUSPECT_PREMATURE'
  | 'SUSPECT_PAUSE'
  | 'SUSPECT_MISSED'
  | 'IRREGULAR'
  | 'INVALID_SIGNAL'

export interface BeatEvent {
  timestampMs: number
  amplitude: number
  rrMs: number | null
  bpmInstant: number | null
  quality: number
  type: BeatType
  reason: string
}

export type MeasurementState =
  | 'NO_CONTACT'
  | 'CONTACT_PARTIAL'
  | 'WARMUP'
  | 'MEASURING'
  | 'DEGRADED'
  | 'INVALID'
  | 'CALIBRATION_REQUIRED'

export const MEASUREMENT_STATE_LABEL: Record<MeasurementState, string> = {
  NO_CONTACT: 'SIN CONTACTO',
  CONTACT_PARTIAL: 'CONTACTO PARCIAL',
  WARMUP: 'CALENTAMIENTO',
  MEASURING: 'MIDIENDO',
  DEGRADED: 'SEÑAL DEGRADADA',
  INVALID: 'SIN LECTURA VÁLIDA',
  CALIBRATION_REQUIRED: 'CALIBRACIÓN REQUERIDA'
}

export const stateAllowsMetrics = (s: MeasurementState): boolean =>
  s === 'MEASURING' || s === 'DEGRADED'

export type HypertensionRiskBand =
  | 'NORMOTENSE'
  | 'BORDERLINE'
  | 'HYPERTENSIVE_PATTERN'
  | 'UNCERTAIN'

export const HYPERTENSION_LABEL: Record<HypertensionRiskBand, { label: string; desc: string }> = {
  NORMOTENSE: { label: 'NORMOTENSO', desc: 'Patrón compatible con rango normal' },
  BORDERLINE: { label: 'LIMÍTROFE', desc: 'Patrón PPG compatible con rango elevado — verificar con médico' },
  HYPERTENSIVE_PATTERN: { label: 'PATRÓN HIPERTENSIVO', desc: 'Morfología PPG compatible con hipertensión — consultar médico' },
  UNCERTAIN: { label: 'INDETERMINADO', desc: 'Evidencia insuficiente para cribado' }
}

export interface VitalReading {
  bpm: number | null
  bpmConfidence: number
  spo2: number | null
  spo2Confidence: number
  sqi: number
  perfusionIndex: number
  motionScore: number
  rrMs: number | null
  rrSdnnMs: number | null
  pnn50: number | null
  beatsDetected: number
  abnormalBeats: number
  state: MeasurementState
  validityFlags: number
  message: string
  hypertensionRisk: HypertensionRiskBand | null
}

export const VALIDITY = {
  OK: 0,
  NO_FINGER: 1 << 0,
  PARTIAL_CONTACT: 1 << 1,
  CLIPPING_HIGH: 1 << 2,
  CLIPPING_LOW: 1 << 3,
  MOTION_HIGH: 1 << 4,
  LOW_PERFUSION: 1 << 5,
  LOW_FPS: 1 << 6,
  UNSTABLE_RR: 1 << 7,
  NOT_ENOUGH_BEATS: 1 << 8,
  CALIBRATION_MISSING: 1 << 9,
  SIGNAL_INCOHERENT: 1 << 10
}
