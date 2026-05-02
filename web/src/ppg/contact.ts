import type { CameraFrameStats, MeasurementState } from './types'
import { VALIDITY } from './types'

export interface ContactEval {
  state: MeasurementState
  flags: number
  fingerProbability: number
}

/**
 * Detector de contacto del dedo sobre cámara + flash. Reglas:
 *
 *  1. Debe haber cobertura ≥ 70 % de píxeles compatibles con "dedo iluminado".
 *  2. La media del canal rojo debe estar en [80, 250] (ni saturado ni oscuro).
 *  3. R > 1.25 × G y R > 1.35 × B (dominancia por hemoglobina + flash).
 *  4. La varianza espacial del rojo debe ser baja (dedo uniforme).
 *
 * Si falla alguna condición, emite `NO_CONTACT` o `CONTACT_PARTIAL` de
 * inmediato; para entrar en `WARMUP` se exige N frames consecutivos OK y
 * luego un warm-up temporal (2.5 s) antes de permitir medición.
 */
export class FingerContactDetector {
  private okStreak = 0
  private badStreak = 0
  private measuringSinceMs: number | null = null
  private lastState: MeasurementState = 'NO_CONTACT'

  constructor(
    private readonly warmupMs = 2500,
    private readonly minRedGreenRatio = 1.25,
    private readonly minRedBlueRatio = 1.35,
    private readonly minRedMean = 80,
    private readonly maxRedMean = 250,
    private readonly maxClipHigh = 0.18,
    private readonly maxClipLow = 0.15,
    private readonly minCoverageSolid = 0.7,
    private readonly minCoveragePartial = 0.35,
    private readonly holdSamples = 8
  ) {}

  reset(): void {
    this.okStreak = 0
    this.badStreak = 0
    this.measuringSinceMs = null
    this.lastState = 'NO_CONTACT'
  }

  evaluate(frame: CameraFrameStats, motionScore: number, fps: number): ContactEval {
    let flags = 0
    const rgRatio = frame.greenMean > 1 ? frame.redMean / frame.greenMean : 0
    const rbRatio = frame.blueMean > 1 ? frame.redMean / frame.blueMean : 0

    const solidContact =
      frame.redMean >= this.minRedMean &&
      frame.redMean <= this.maxRedMean &&
      rgRatio >= this.minRedGreenRatio &&
      rbRatio >= this.minRedBlueRatio &&
      frame.roiCoverage >= this.minCoverageSolid &&
      frame.clipHighRatio <= this.maxClipHigh &&
      frame.clipLowRatio <= this.maxClipLow

    const partialContact =
      !solidContact &&
      frame.redMean >= this.minRedMean * 0.7 &&
      rgRatio >= this.minRedGreenRatio * 0.9 &&
      frame.roiCoverage >= this.minCoveragePartial

    if (!solidContact && !partialContact) flags |= VALIDITY.NO_FINGER
    else if (!solidContact) flags |= VALIDITY.PARTIAL_CONTACT

    if (frame.clipHighRatio > this.maxClipHigh) flags |= VALIDITY.CLIPPING_HIGH
    if (frame.clipLowRatio > this.maxClipLow) flags |= VALIDITY.CLIPPING_LOW
    if (frame.redMean > this.maxRedMean) flags |= VALIDITY.CLIPPING_HIGH
    if (motionScore > 0.6) flags |= VALIDITY.MOTION_HIGH
    if (fps > 1 && fps < 15) flags |= VALIDITY.LOW_FPS

    const state = this.nextState(solidContact, partialContact, flags, frame.timestampMs)
    this.lastState = state
    return { state, flags, fingerProbability: Math.max(0, Math.min(1, frame.roiCoverage)) }
  }

  private nextState(
    solid: boolean,
    partial: boolean,
    flags: number,
    timestampMs: number
  ): MeasurementState {
    if (!solid && !partial) {
      this.okStreak = 0
      this.badStreak = Math.min(this.holdSamples * 4, this.badStreak + 1)
      this.measuringSinceMs = null
      return 'NO_CONTACT'
    }
    if (partial) {
      this.okStreak = 0
      this.badStreak = Math.min(this.holdSamples * 4, this.badStreak + 1)
      this.measuringSinceMs = null
      return 'CONTACT_PARTIAL'
    }
    // Solid.
    this.badStreak = 0
    this.okStreak = Math.min(this.holdSamples * 8, this.okStreak + 1)

    if (this.okStreak < this.holdSamples) return 'WARMUP'
    if (this.measuringSinceMs === null) this.measuringSinceMs = timestampMs
    const elapsed = timestampMs - this.measuringSinceMs
    if (elapsed < this.warmupMs) return 'WARMUP'

    const degradeMask =
      VALIDITY.CLIPPING_HIGH |
      VALIDITY.CLIPPING_LOW |
      VALIDITY.MOTION_HIGH |
      VALIDITY.LOW_FPS |
      VALIDITY.LOW_PERFUSION
    if (flags & degradeMask) return 'DEGRADED'
    return 'MEASURING'
  }
}
