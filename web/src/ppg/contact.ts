import { PROCESSING } from '../constants/processing'
import type { CameraFrameStats, MeasurementState } from './types'
import { VALIDITY } from './types'

export interface ContactEval {
  state: MeasurementState
  flags: number
  fingerProbability: number
  reasonCodes: string[]
}

/**
 * Detector de contacto del dedo sobre cámara + flash con histéresis
 * ASIMÉTRICA para evitar "microcortes" que destrozan la medición.
 *
 * Principios:
 *
 *  - Entrar a "hay dedo" es relativamente fácil: basta con que varios frames
 *    consecutivos cumplan un conjunto razonable de condiciones ópticas.
 *  - Salir a "no hay dedo" es costoso: exige muchos frames seguidos malos
 *    antes de bajar el estado. Esto permite absorber parpadeos del flash,
 *    pequeños micro-desplazamientos, autoexposición que varía o cualquier
 *    glitch transitorio.
 *  - Dentro del estado "dedo", los umbrales son más permisivos (umbral de
 *    histéresis) que los umbrales para entrar.
 *
 * Estados emitidos:
 *   NO_CONTACT → CONTACT_PARTIAL → WARMUP → MEASURING/DEGRADED
 *
 * La UI considera la onda visible en WARMUP/MEASURING/DEGRADED.
 */
export class FingerContactDetector {
  private okStreak = 0
  private badStreak = 0
  private lastState: MeasurementState = 'NO_CONTACT'
  private readonly enterStreak: number
  private readonly exitStreak: number

  constructor(opts?: {
    enterStreak?: number
    exitStreak?: number
  }) {
    this.enterStreak = opts?.enterStreak ?? 4
    this.exitStreak = opts?.exitStreak ?? 6
  }

  reset(): void {
    this.okStreak = 0
    this.badStreak = 0
    this.lastState = 'NO_CONTACT'
  }

  evaluate(frame: CameraFrameStats, motionScore: number, fps: number): ContactEval {
    let flags = 0
    const reasonCodes: string[] = []
    const rgRatio = frame.greenMean > 1 ? frame.redMean / frame.greenMean : 0
    const rbRatio = frame.blueMean > 1 ? frame.redMean / frame.blueMean : 0

    const inContact = this.lastState !== 'NO_CONTACT'

    const minR = inContact ? 50 : 70
    const maxR = 252
    const minRG = inContact ? 1.05 : 1.2
    const minRB = inContact ? 1.08 : 1.25
    const minCoverage = inContact ? 0.32 : 0.52

    const solid =
      frame.redMean >= minR &&
      frame.redMean <= maxR &&
      rgRatio >= minRG &&
      rbRatio >= minRB &&
      frame.roiCoverage >= minCoverage &&
      frame.clipHighRatio <= PROCESSING.MAX_CLIP_HIGH_FOR_VALID &&
      frame.clipLowRatio <= PROCESSING.MAX_CLIP_LOW_FOR_VALID

    if (!solid) {
      if (frame.redMean < minR) reasonCodes.push('CONTACT_RED_LOW')
      if (frame.roiCoverage < minCoverage) reasonCodes.push('CONTACT_COVERAGE_LOW')
      if (rgRatio < minRG || rbRatio < minRB) reasonCodes.push('CONTACT_CHROMA_LOW')
      flags |= VALIDITY.NO_FINGER
    }
    if (frame.clipHighRatio > PROCESSING.MAX_CLIP_HIGH_FOR_VALID) {
      flags |= VALIDITY.CLIPPING_HIGH
      reasonCodes.push('CONTACT_CLIP_HIGH')
    }
    if (frame.clipLowRatio > PROCESSING.MAX_CLIP_LOW_FOR_VALID) {
      flags |= VALIDITY.CLIPPING_LOW
      reasonCodes.push('CONTACT_CLIP_LOW')
    }
    if (motionScore > PROCESSING.MAX_MOTION_FOR_VALID) {
      flags |= VALIDITY.MOTION_HIGH
      reasonCodes.push('CONTACT_MOTION_HIGH')
    }
    if (fps > 1 && fps < 12) {
      flags |= VALIDITY.LOW_FPS
      reasonCodes.push('CONTACT_LOW_FPS')
    }

    const minimalEvidence = frame.redMean >= 35 && frame.roiCoverage >= 0.14 && rgRatio >= 0.95

    if (solid) {
      this.okStreak = Math.min(this.enterStreak * 8, this.okStreak + 1)
      this.badStreak = Math.max(0, this.badStreak - 3)
    } else if (inContact && minimalEvidence) {
      this.okStreak = Math.max(0, this.okStreak - 1)
      this.badStreak = Math.max(0, this.badStreak - 1)
      reasonCodes.push('CONTACT_HYSTERESIS_HOLD')
    } else {
      this.okStreak = Math.max(0, this.okStreak - 1)
      this.badStreak = Math.min(this.exitStreak * 3, this.badStreak + 1)
    }

    const state = this.nextState()
    this.lastState = state
    return {
      state,
      flags,
      fingerProbability: Math.max(0, Math.min(1, frame.roiCoverage)),
      reasonCodes
    }
  }

  private nextState(): MeasurementState {
    if (this.badStreak >= this.exitStreak) {
      this.okStreak = 0
      return 'NO_CONTACT'
    }
    if (this.okStreak >= this.enterStreak) return 'PROBABLE_PPG'
    return 'NO_CONTACT'
  }
}
