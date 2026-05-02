import type { CameraFrameStats } from './types'
import { VALIDITY } from './types'

export interface ChromaticGateResult {
  passed: boolean
  flags: number
  reasonCodes: string[]
  rgRatio: number
  rbRatio: number
}

export class ChromaticGate {
  evaluate(frame: CameraFrameStats): ChromaticGateResult {
    const rgRatio = frame.greenMean > 1 ? frame.redMean / frame.greenMean : 0
    const rbRatio = frame.blueMean > 1 ? frame.redMean / frame.blueMean : 0
    const reasonCodes: string[] = []
    let flags = VALIDITY.OK
    if (rgRatio < 1.15 || rbRatio < 1.2) {
      flags |= VALIDITY.NO_FINGER
      reasonCodes.push('CHROMA_RED_DOMINANCE_FAIL')
    }
    if (frame.roiCoverage < 0.5) {
      flags |= VALIDITY.NO_FINGER
      reasonCodes.push('ROI_COVERAGE_LOW')
    }
    if (frame.clipHighRatio > 0.2) {
      flags |= VALIDITY.CLIPPING_HIGH
      reasonCodes.push('CLIPPING_HIGH')
    }
    if (frame.clipLowRatio > 0.2) {
      flags |= VALIDITY.CLIPPING_LOW
      reasonCodes.push('CLIPPING_LOW')
    }
    return {
      passed: reasonCodes.length === 0,
      flags,
      reasonCodes,
      rgRatio,
      rbRatio
    }
  }
}
