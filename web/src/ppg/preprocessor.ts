import { BandpassFilter, Detrender } from './filters'

export interface PreprocessorOutput {
  detrended: number
  filtered: number
  dc: number
  ac: number
  perfusionIndex: number
}

export class PpgPreprocessor {
  private readonly detrender: Detrender
  private readonly band: BandpassFilter
  private readonly sampleRate: number
  private dcEma = 0
  private initialized = false
  private readonly acBuffer: Float64Array
  private acIndex = 0
  private acMax = Number.NEGATIVE_INFINITY
  private acMin = Number.POSITIVE_INFINITY

  constructor(sampleRate: number, lowHz = 0.5, highHz = 4.0) {
    this.sampleRate = sampleRate
    this.detrender = new Detrender(Math.max(30, Math.floor(sampleRate * 4)))
    this.band = new BandpassFilter(sampleRate, lowHz, highHz)
    this.acBuffer = new Float64Array(Math.max(30, Math.floor(sampleRate * 2)))
  }

  reset(): void {
    this.detrender.reset()
    this.band.reset()
    this.dcEma = 0
    this.initialized = false
    this.acBuffer.fill(0)
    this.acIndex = 0
    this.acMax = Number.NEGATIVE_INFINITY
    this.acMin = Number.POSITIVE_INFINITY
  }

  process(rawSample: number): PreprocessorOutput {
    if (!this.initialized) {
      this.dcEma = rawSample
      this.initialized = true
    } else {
      const alpha = 1 / (this.sampleRate * 2)
      this.dcEma += alpha * (rawSample - this.dcEma)
    }
    const detrended = this.detrender.process(rawSample)
    const filtered = this.band.process(detrended)

    this.acBuffer[this.acIndex] = filtered
    this.acIndex = (this.acIndex + 1) % this.acBuffer.length
    if (this.acIndex === 0) {
      let mx = Number.NEGATIVE_INFINITY
      let mn = Number.POSITIVE_INFINITY
      for (let i = 0; i < this.acBuffer.length; i++) {
        const v = this.acBuffer[i]
        if (v > mx) mx = v
        if (v < mn) mn = v
      }
      this.acMax = mx
      this.acMin = mn
    }
    const ac = Math.max(0, this.acMax - this.acMin)
    const dc = Math.max(1, this.dcEma)
    const pi = Math.min(50, Math.max(0, (100 * ac) / dc))
    return { detrended, filtered, dc, ac, perfusionIndex: pi }
  }
}
