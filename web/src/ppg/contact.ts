import type { CameraFrameStats, MeasurementState } from './types'
import { VALIDITY } from './types'

export interface ContactEval {
  state: MeasurementState
  flags: number
  fingerProbability: number
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
  // Requisitos para ENTRAR a contacto sólido.
  private readonly enterMinR: number
  private readonly enterMaxR: number
  private readonly enterMinRG: number
  private readonly enterMinRB: number
  private readonly enterMinCoverage: number

  // Requisitos para MANTENER contacto (histéresis relajada).
  private readonly holdMinR: number
  private readonly holdMaxR: number
  private readonly holdMinRG: number
  private readonly holdMinRB: number
  private readonly holdMinCoverage: number

  private okStreak = 0
  private badStreak = 0
  private measuringSinceMs: number | null = null
  private lastState: MeasurementState = 'NO_CONTACT'
  private readonly warmupMs: number
  private readonly enterStreak: number
  private readonly exitStreak: number

  constructor(opts?: {
    warmupMs?: number
    enterStreak?: number
    exitStreak?: number
  }) {
    this.warmupMs = opts?.warmupMs ?? 1500 // antes 2500; baja el tiempo a primer latido
    this.enterStreak = opts?.enterStreak ?? 5 // ~170 ms a 30 Hz
    this.exitStreak = opts?.exitStreak ?? 25 // ~830 ms a 30 Hz — muy costoso salir

    this.enterMinR = 70
    this.enterMaxR = 252
    this.enterMinRG = 1.2
    this.enterMinRB = 1.3
    this.enterMinCoverage = 0.55

    // Histéresis: una vez adentro, los umbrales caen ~30-50 %.
    this.holdMinR = 45
    this.holdMaxR = 254
    this.holdMinRG = 1.02
    this.holdMinRB = 1.05
    this.holdMinCoverage = 0.3
  }

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

    const inContact =
      this.lastState === 'WARMUP' ||
      this.lastState === 'MEASURING' ||
      this.lastState === 'DEGRADED'

    // Umbrales según si estamos entrando o manteniendo.
    const minR = inContact ? this.holdMinR : this.enterMinR
    const maxR = inContact ? this.holdMaxR : this.enterMaxR
    const minRG = inContact ? this.holdMinRG : this.enterMinRG
    const minRB = inContact ? this.holdMinRB : this.enterMinRB
    const minCoverage = inContact ? this.holdMinCoverage : this.enterMinCoverage

    const solid =
      frame.redMean >= minR &&
      frame.redMean <= maxR &&
      rgRatio >= minRG &&
      rbRatio >= minRB &&
      frame.roiCoverage >= minCoverage &&
      frame.clipHighRatio <= 0.35 &&
      frame.clipLowRatio <= 0.25

    if (!solid) {
      // Motivo por el que no estamos en 'solid' para informar al usuario.
      if (frame.redMean < minR || frame.roiCoverage < minCoverage) flags |= VALIDITY.NO_FINGER
      if (rgRatio < minRG || rbRatio < minRB) flags |= VALIDITY.NO_FINGER
    }
    if (frame.clipHighRatio > 0.18) flags |= VALIDITY.CLIPPING_HIGH
    if (frame.clipLowRatio > 0.15) flags |= VALIDITY.CLIPPING_LOW
    if (motionScore > 0.7) flags |= VALIDITY.MOTION_HIGH
    if (fps > 1 && fps < 12) flags |= VALIDITY.LOW_FPS

    if (solid) {
      this.okStreak = Math.min(this.enterStreak * 8, this.okStreak + 1)
      this.badStreak = Math.max(0, this.badStreak - 2) // recuperación rápida
    } else {
      this.okStreak = Math.max(0, this.okStreak - 1)
      this.badStreak = Math.min(this.exitStreak * 3, this.badStreak + 1)
    }

    const state = this.nextState(solid, flags, frame.timestampMs)
    this.lastState = state
    return { state, flags, fingerProbability: Math.max(0, Math.min(1, frame.roiCoverage)) }
  }

  private nextState(solid: boolean, flags: number, timestampMs: number): MeasurementState {
    const prev = this.lastState
    const inContact =
      prev === 'WARMUP' || prev === 'MEASURING' || prev === 'DEGRADED'

    // Si estábamos NO_CONTACT/PARCIAL: necesitamos `enterStreak` frames sólidos.
    if (!inContact) {
      if (this.okStreak < this.enterStreak) {
        // Decidir entre NO_CONTACT y CONTACT_PARTIAL para UX.
        if (this.okStreak > 1) return 'CONTACT_PARTIAL'
        return 'NO_CONTACT'
      }
      // Acabamos de entrar a contacto; empezamos warm-up.
      this.measuringSinceMs = timestampMs
      return 'WARMUP'
    }

    // Si estábamos ya en contacto: sólo salimos después de `exitStreak` frames malos.
    if (this.badStreak >= this.exitStreak) {
      this.okStreak = 0
      this.measuringSinceMs = null
      this.lastState = 'NO_CONTACT'
      return 'NO_CONTACT'
    }

    // Mantenemos el estado "en contacto". Warm-up vs measuring.
    const since = this.measuringSinceMs ?? timestampMs
    if (timestampMs - since < this.warmupMs) return 'WARMUP'

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
