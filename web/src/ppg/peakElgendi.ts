// Detector Elgendi 2013: dos ventanas móviles + threshold adaptativo sobre la
// señal filtrada cuadrada, con período refractario fisiológico.

export interface ElgendiDetection {
  sampleIndex: number
  amplitude: number
  timestampMs: number
}

export class PeakDetectorElgendi {
  private readonly shortBuf: Float64Array
  private readonly longBuf: Float64Array
  private shortSum = 0
  private longSum = 0
  private shortIdx = 0
  private longIdx = 0
  private shortFilled = 0
  private longFilled = 0
  private readonly refractoryN: number
  private refractoryCountdown = 0
  private readonly offsetBeta: number
  private readonly minProminence: number
  private inBlock = false
  private blockPeakValue = 0
  private blockPeakIndex = -1
  private blockPeakTimestampMs = 0
  private sampleIndex = 0

  constructor(sampleRateHz: number, opts?: {
    shortWindowMs?: number
    longWindowMs?: number
    offsetBeta?: number
    refractoryMs?: number
    minProminence?: number
  }) {
    const shortMs = opts?.shortWindowMs ?? 111
    const longMs = opts?.longWindowMs ?? 667
    const shortN = Math.max(3, Math.floor((sampleRateHz * shortMs) / 1000))
    const longN = Math.max(shortN + 2, Math.floor((sampleRateHz * longMs) / 1000))
    this.shortBuf = new Float64Array(shortN)
    this.longBuf = new Float64Array(longN)
    this.refractoryN = Math.max(3, Math.floor(((opts?.refractoryMs ?? 280) * sampleRateHz) / 1000))
    this.offsetBeta = opts?.offsetBeta ?? 0.02
    this.minProminence = opts?.minProminence ?? 1e-5
  }

  reset(): void {
    this.shortBuf.fill(0)
    this.longBuf.fill(0)
    this.shortSum = 0
    this.longSum = 0
    this.shortIdx = 0
    this.longIdx = 0
    this.shortFilled = 0
    this.longFilled = 0
    this.refractoryCountdown = 0
    this.inBlock = false
    this.blockPeakValue = 0
    this.blockPeakIndex = -1
    this.blockPeakTimestampMs = 0
    this.sampleIndex = 0
  }

  feed(filtered: number, timestampMs: number): ElgendiDetection | null {
    const squared = filtered > 0 ? filtered * filtered : 0

    const oldS = this.shortBuf[this.shortIdx]
    this.shortBuf[this.shortIdx] = squared
    this.shortSum += squared - oldS
    this.shortIdx = (this.shortIdx + 1) % this.shortBuf.length
    if (this.shortFilled < this.shortBuf.length) this.shortFilled++

    const oldL = this.longBuf[this.longIdx]
    this.longBuf[this.longIdx] = squared
    this.longSum += squared - oldL
    this.longIdx = (this.longIdx + 1) % this.longBuf.length
    if (this.longFilled < this.longBuf.length) this.longFilled++

    const maPeak = this.shortSum / this.shortFilled
    const maBeat = this.longSum / this.longFilled
    const threshold = maBeat * (1 + this.offsetBeta)

    if (this.refractoryCountdown > 0) this.refractoryCountdown--

    let out: ElgendiDetection | null = null
    if (maPeak > threshold) {
      if (!this.inBlock) {
        this.inBlock = true
        this.blockPeakValue = filtered
        this.blockPeakIndex = this.sampleIndex
        this.blockPeakTimestampMs = timestampMs
      } else if (filtered > this.blockPeakValue) {
        this.blockPeakValue = filtered
        this.blockPeakIndex = this.sampleIndex
        this.blockPeakTimestampMs = timestampMs
      }
    } else if (this.inBlock) {
      if (this.refractoryCountdown <= 0 && this.blockPeakValue > this.minProminence) {
        out = {
          sampleIndex: this.blockPeakIndex,
          amplitude: this.blockPeakValue,
          timestampMs: this.blockPeakTimestampMs
        }
        this.refractoryCountdown = this.refractoryN
      }
      this.inBlock = false
      this.blockPeakValue = 0
      this.blockPeakIndex = -1
    }
    this.sampleIndex++
    return out
  }
}
