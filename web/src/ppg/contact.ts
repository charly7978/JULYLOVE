import type { CameraFrameStats, MeasurementState } from './types'
import { VALIDITY } from './types'

export interface ContactEval {
  state: MeasurementState
  flags: number
}

export class FingerContactDetector {
  private stableFor = 0
  private unstableFor = 0
  private measuringSinceMs: number | null = null
  private lastState: MeasurementState = 'NO_CONTACT'

  constructor(
    private readonly warmupMs = 2500,
    private readonly minRedGreenRatio = 1.25,
    private readonly minRedBlueRatio = 1.3,
    private readonly minRedMean = 60,
    private readonly maxRedMean = 245,
    private readonly maxClipHigh = 0.18,
    private readonly maxClipLow = 0.15,
    private readonly maxStdForFinger = 60,
    private readonly holdSamples = 4
  ) {}

  reset(): void {
    this.stableFor = 0
    this.unstableFor = 0
    this.measuringSinceMs = null
    this.lastState = 'NO_CONTACT'
  }

  evaluate(frame: CameraFrameStats, motionScore: number, fps: number): ContactEval {
    let flags = 0
    let contact = true

    const rgRatio = frame.greenMean > 1 ? frame.redMean / frame.greenMean : 0
    const rbRatio = frame.blueMean > 1 ? frame.redMean / frame.blueMean : 0

    if (frame.redMean < this.minRedMean) {
      contact = false
      flags |= VALIDITY.NO_FINGER
    }
    if (rgRatio < this.minRedGreenRatio) {
      contact = false
      flags |= VALIDITY.NO_FINGER
    }
    if (rbRatio < this.minRedBlueRatio) {
      contact = false
      flags |= VALIDITY.NO_FINGER
    }
    if (frame.roiCoverage < 0.6) {
      contact = false
      flags |= VALIDITY.PARTIAL_CONTACT
    }
    if (frame.clipHighRatio > this.maxClipHigh) flags |= VALIDITY.CLIPPING_HIGH
    if (frame.clipLowRatio > this.maxClipLow) flags |= VALIDITY.CLIPPING_LOW
    if (frame.redMean > this.maxRedMean) flags |= VALIDITY.CLIPPING_HIGH

    const spatialStd = Math.sqrt(Math.max(0, frame.roiVariance))
    if (spatialStd > this.maxStdForFinger) {
      flags |= VALIDITY.PARTIAL_CONTACT
      contact = false
    }
    if (motionScore > 0.6) flags |= VALIDITY.MOTION_HIGH
    if (fps > 1 && fps < 18) flags |= VALIDITY.LOW_FPS

    const state = this.nextState(contact, flags, frame.timestampMs)
    this.lastState = state
    return { state, flags }
  }

  private nextState(contact: boolean, flags: number, timestampMs: number): MeasurementState {
    if (!contact) {
      this.stableFor = 0
      this.unstableFor = Math.min(this.holdSamples * 2, this.unstableFor + 1)
      this.measuringSinceMs = null
      return flags & VALIDITY.NO_FINGER ? 'NO_CONTACT' : 'CONTACT_PARTIAL'
    }
    this.stableFor = Math.min(this.holdSamples * 4, this.stableFor + 1)
    this.unstableFor = 0

    if (this.stableFor < this.holdSamples) return 'WARMUP'

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
