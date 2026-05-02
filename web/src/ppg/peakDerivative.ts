export interface DerivativeDetection {
  sampleIndex: number
  amplitude: number
  timestampMs: number
  prominence: number
}

export class PeakDetectorDerivative {
  private lastSample = Number.NaN
  private lastDeriv = 0
  private refractoryCountdown = 0
  private readonly refractoryN: number
  private readonly minProminence: number
  private sampleIndex = 0
  private minSinceLastPeak = Number.POSITIVE_INFINITY

  constructor(sampleRateHz: number, opts?: { refractoryMs?: number; minProminence?: number }) {
    this.refractoryN = Math.max(3, Math.floor(((opts?.refractoryMs ?? 260) * sampleRateHz) / 1000))
    this.minProminence = opts?.minProminence ?? 1e-4
  }

  reset(): void {
    this.lastSample = Number.NaN
    this.lastDeriv = 0
    this.refractoryCountdown = 0
    this.sampleIndex = 0
    this.minSinceLastPeak = Number.POSITIVE_INFINITY
  }

  feed(filtered: number, timestampMs: number): DerivativeDetection | null {
    if (this.refractoryCountdown > 0) this.refractoryCountdown--
    if (filtered < this.minSinceLastPeak) this.minSinceLastPeak = filtered
    let out: DerivativeDetection | null = null
    if (!Number.isNaN(this.lastSample)) {
      const deriv = filtered - this.lastSample
      const crossedDown = this.lastDeriv > 0 && deriv <= 0
      if (crossedDown) {
        const prominence = Math.abs(this.lastSample - this.minSinceLastPeak)
        if (this.refractoryCountdown <= 0 && prominence >= this.minProminence) {
          out = {
            sampleIndex: this.sampleIndex - 1,
            amplitude: this.lastSample,
            timestampMs,
            prominence
          }
          this.refractoryCountdown = this.refractoryN
          this.minSinceLastPeak = filtered
        }
      }
      this.lastDeriv = deriv
    }
    this.lastSample = filtered
    this.sampleIndex++
    return out
  }
}
