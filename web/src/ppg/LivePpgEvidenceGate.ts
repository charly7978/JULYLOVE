import { PROCESSING } from '../constants/processing'

export type EvidenceState = 'NO_VALID_PPG' | 'PROBABLE_PPG' | 'VALID_LIVE_PPG'

export interface EvidenceInput {
  chromaPassed: boolean
  hasPulseEvidence: boolean
  sqi: number
  perfusionIndex: number
  motionScore: number
  clipHighRatio: number
  clipLowRatio: number
}

export interface EvidenceResult {
  state: EvidenceState
  shouldPublish: boolean
  reasonCodes: string[]
}

export class LivePpgEvidenceGate {
  private goodFrames = 0
  private badFrames = 0
  private state: EvidenceState = 'NO_VALID_PPG'
  private pulseHold = 0

  reset(): void {
    this.goodFrames = 0
    this.badFrames = 0
    this.state = 'NO_VALID_PPG'
    this.pulseHold = 0
  }

  evaluate(input: EvidenceInput): EvidenceResult {
    const reasonCodes: string[] = []
    if (input.hasPulseEvidence) this.pulseHold = PROCESSING.EVIDENCE_PULSE_HOLD_FRAMES
    else this.pulseHold = Math.max(0, this.pulseHold - 1)
    const pulseWindowOk = this.pulseHold > 0
    if (!input.chromaPassed) reasonCodes.push('GATE_CHROMA_FAIL')
    if (!pulseWindowOk) reasonCodes.push('GATE_NO_PULSE_EVIDENCE')
    if (input.sqi < PROCESSING.MIN_SQI_FOR_VALID) reasonCodes.push('GATE_SQI_LOW')
    if (input.perfusionIndex < PROCESSING.MIN_PERFUSION_FOR_VALID) reasonCodes.push('GATE_PERFUSION_LOW')
    if (input.motionScore > PROCESSING.MAX_MOTION_FOR_VALID) reasonCodes.push('GATE_MOTION_HIGH')
    if (input.clipHighRatio > PROCESSING.MAX_CLIP_HIGH_FOR_VALID) reasonCodes.push('GATE_CLIP_HIGH')
    if (input.clipLowRatio > PROCESSING.MAX_CLIP_LOW_FOR_VALID) reasonCodes.push('GATE_CLIP_LOW')

    const good = reasonCodes.length === 0
    if (good) {
      this.goodFrames++
      this.badFrames = 0
    } else {
      this.badFrames++
      this.goodFrames = Math.max(0, this.goodFrames - 1)
    }

    if (this.badFrames >= PROCESSING.EVIDENCE_BAD_FRAMES_TO_INVALIDATE) {
      this.state = 'NO_VALID_PPG'
    } else if (this.goodFrames >= PROCESSING.EVIDENCE_GOOD_FRAMES_TO_VALIDATE) {
      this.state = 'VALID_LIVE_PPG'
    } else if (this.goodFrames > 0) {
      this.state = 'PROBABLE_PPG'
    } else {
      this.state = 'NO_VALID_PPG'
    }

    return {
      state: this.state,
      shouldPublish: this.state === 'VALID_LIVE_PPG',
      reasonCodes
    }
  }
}
