export interface SqiInput {
  hasContact: boolean
  perfusionIndex: number
  clipHighRatio: number
  clipLowRatio: number
  motionScore: number
  fpsActual: number
  fpsTarget: number
  spectralCoherence: number
  rrCv: number | null
  rrCount: number
  roiSpatialStd: number
}

export type SqiBand = 'EXCELLENT' | 'GOOD' | 'DEGRADED' | 'INVALID'

export const SQI_BAND_LABEL: Record<SqiBand, string> = {
  EXCELLENT: 'EXCELENTE',
  GOOD: 'BUENO',
  DEGRADED: 'DEGRADADO',
  INVALID: 'INVÁLIDO'
}

export class SignalQualityIndex {
  evaluate(input: SqiInput): number {
    if (!input.hasContact) return 0
    const pi = this.pi01(input.perfusionIndex)
    const clip = this.clip01(input.clipHighRatio, input.clipLowRatio)
    const motion = 1 - Math.min(1, Math.max(0, input.motionScore))
    const fps = this.fps01(input.fpsActual, input.fpsTarget)
    const coh = Math.min(1, Math.max(0, input.spectralCoherence))
    const rr = this.rrConsistency01(input.rrCv, input.rrCount)
    const roi = this.roiStability01(input.roiSpatialStd)
    const hard = pi * clip * motion
    const soft = 0.3 * fps + 0.25 * coh + 0.25 * rr + 0.2 * roi
    return Math.min(1, Math.max(0, hard * soft))
  }

  private pi01(pi: number): number {
    if (pi <= 0.05) return 0
    if (pi >= 2) return 1
    return Math.min(1, Math.max(0, (pi - 0.05) / (2 - 0.05)))
  }

  private clip01(high: number, low: number): number {
    const h = Math.max(0, 0.18 - high) / 0.18
    const l = Math.max(0, 0.15 - low) / 0.15
    return h * l
  }

  private fps01(actual: number, target: number): number {
    if (target <= 0) return 0
    const ratio = actual / target
    if (ratio >= 0.9) return 1
    if (ratio >= 0.6) return Math.min(1, Math.max(0, (ratio - 0.6) / 0.3))
    return 0
  }

  private rrConsistency01(cv: number | null, count: number): number {
    if (cv === null || count < 3) return 0
    const base = Math.max(0, 0.25 - cv) / 0.25
    const weight = Math.min(1, Math.max(0, count / (count + 5)))
    return base * weight
  }

  private roiStability01(roiStd: number): number {
    // Bajo flash blanco con dedo apoyado, la desviación estándar
    // espacial del canal rojo suele estar en 6–35 (niveles RGB 8 bits).
    // Si supera 60 hay micro-movimiento o cobertura desigual.
    if (roiStd <= 6) return 1
    if (roiStd >= 60) return 0
    return Math.max(0, Math.min(1, (60 - roiStd) / 54))
  }

  band(sqi: number): SqiBand {
    if (sqi >= 0.75) return 'EXCELLENT'
    if (sqi >= 0.55) return 'GOOD'
    if (sqi >= 0.35) return 'DEGRADED'
    return 'INVALID'
  }
}
