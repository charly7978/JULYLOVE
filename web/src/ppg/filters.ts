// Filtros IIR biquad (Direct Form II Transposed) + bandpass Butterworth orden 4
// y detrender por media móvil. Portado desde la implementación Kotlin del
// proyecto Android.

export class Biquad {
  private z1 = 0
  private z2 = 0

  constructor(
    private readonly b0: number,
    private readonly b1: number,
    private readonly b2: number,
    private readonly a1: number,
    private readonly a2: number
  ) {}

  process(x: number): number {
    const y = this.b0 * x + this.z1
    this.z1 = this.b1 * x - this.a1 * y + this.z2
    this.z2 = this.b2 * x - this.a2 * y
    return y
  }

  reset(): void {
    this.z1 = 0
    this.z2 = 0
  }

  static lowPass(cutoffHz: number, sampleRate: number, q = Math.SQRT1_2): Biquad {
    const w0 = (2 * Math.PI * cutoffHz) / sampleRate
    const cosW = Math.cos(w0)
    const sinW = Math.sin(w0)
    const alpha = sinW / (2 * q)
    const a0 = 1 + alpha
    return new Biquad(
      (1 - cosW) / 2 / a0,
      (1 - cosW) / a0,
      (1 - cosW) / 2 / a0,
      (-2 * cosW) / a0,
      (1 - alpha) / a0
    )
  }

  static highPass(cutoffHz: number, sampleRate: number, q = Math.SQRT1_2): Biquad {
    const w0 = (2 * Math.PI * cutoffHz) / sampleRate
    const cosW = Math.cos(w0)
    const sinW = Math.sin(w0)
    const alpha = sinW / (2 * q)
    const a0 = 1 + alpha
    return new Biquad(
      (1 + cosW) / 2 / a0,
      -(1 + cosW) / a0,
      (1 + cosW) / 2 / a0,
      (-2 * cosW) / a0,
      (1 - alpha) / a0
    )
  }
}

export class BandpassFilter {
  private readonly hp1: Biquad
  private readonly hp2: Biquad
  private readonly lp1: Biquad
  private readonly lp2: Biquad

  constructor(sampleRate: number, lowHz = 0.5, highHz = 4.0) {
    if (sampleRate <= 0) throw new Error('sampleRate must be positive')
    if (!(lowHz > 0 && highHz > lowHz && highHz < sampleRate / 2)) {
      throw new Error(`band ${lowHz}..${highHz} invalid at fs=${sampleRate}`)
    }
    this.hp1 = Biquad.highPass(lowHz, sampleRate)
    this.hp2 = Biquad.highPass(lowHz, sampleRate)
    this.lp1 = Biquad.lowPass(highHz, sampleRate)
    this.lp2 = Biquad.lowPass(highHz, sampleRate)
  }

  process(x: number): number {
    return this.lp2.process(this.lp1.process(this.hp2.process(this.hp1.process(x))))
  }

  reset(): void {
    this.hp1.reset()
    this.hp2.reset()
    this.lp1.reset()
    this.lp2.reset()
  }
}

export class Detrender {
  private readonly buffer: Float64Array
  private index = 0
  private filled = 0
  private sum = 0

  constructor(windowSamples: number) {
    const size = Math.max(2, Math.floor(windowSamples))
    this.buffer = new Float64Array(size)
  }

  process(sample: number): number {
    const old = this.buffer[this.index]
    this.buffer[this.index] = sample
    this.sum += sample - old
    this.index = (this.index + 1) % this.buffer.length
    if (this.filled < this.buffer.length) this.filled++
    const mean = this.sum / this.filled
    return sample - mean
  }

  reset(): void {
    this.buffer.fill(0)
    this.index = 0
    this.filled = 0
    this.sum = 0
  }
}
