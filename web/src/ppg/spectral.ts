// Estimador espectral por barrido Goertzel-like sobre ventana deslizante.
// O(N*K) por estimación; se ejecuta cada ~1 s.

export interface SpectralResult {
  bpm: number | null
  coherence: number
  samplesUsed: number
}

export class SpectralHeartRateEstimator {
  private readonly buffer: Float64Array
  private index = 0
  private filled = 0
  private readonly sampleRate: number
  private readonly minBpm: number
  private readonly maxBpm: number

  constructor(sampleRate: number, opts?: { windowSeconds?: number; minBpm?: number; maxBpm?: number }) {
    this.sampleRate = sampleRate
    const windowSeconds = opts?.windowSeconds ?? 8
    this.buffer = new Float64Array(Math.max(32, Math.floor(sampleRate * windowSeconds)))
    this.minBpm = opts?.minBpm ?? 30
    this.maxBpm = opts?.maxBpm ?? 240
  }

  reset(): void {
    this.buffer.fill(0)
    this.index = 0
    this.filled = 0
  }

  push(sample: number): void {
    this.buffer[this.index] = sample
    this.index = (this.index + 1) % this.buffer.length
    if (this.filled < this.buffer.length) this.filled++
  }

  estimate(): SpectralResult {
    const n = this.buffer.length
    if (this.filled < n) return { bpm: null, coherence: 0, samplesUsed: this.filled }
    const start = this.index
    let peakMag = 0
    let peakBpm = 0
    const mags = new Float64Array(this.maxBpm - this.minBpm + 1)
    for (let bpm = this.minBpm; bpm <= this.maxBpm; bpm++) {
      const omega = (2 * Math.PI * (bpm / 60)) / this.sampleRate
      let re = 0
      let im = 0
      for (let i = 0; i < n; i++) {
        const s = this.buffer[(start + i) % n]
        re += s * Math.cos(omega * i)
        im += s * Math.sin(omega * i)
      }
      const mag = Math.sqrt(re * re + im * im)
      mags[bpm - this.minBpm] = mag
      if (mag > peakMag) {
        peakMag = mag
        peakBpm = bpm
      }
    }
    const sorted = Array.from(mags).sort((a, b) => a - b)
    const median = sorted[Math.floor(sorted.length / 2)]
    const coherence = median > 0 ? Math.min(20, peakMag / median) / 20 : 0
    return { bpm: peakBpm, coherence, samplesUsed: n }
  }
}
