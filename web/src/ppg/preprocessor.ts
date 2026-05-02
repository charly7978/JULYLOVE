import { BandpassFilter, Biquad, Detrender } from './filters'

export interface PreprocessorOutput {
  /** Solo DC removido: morfología PPG natural, para dibujar. */
  detrended: number
  /** Banda 0.5-4 Hz: óptimo para detección de picos. */
  filtered: number
  /** Señal morfológica suavizada (detrended + LP 2.5 Hz): ideal para pantalla. */
  display: number
  dc: number
  ac: number
  perfusionIndex: number
}

/**
 * Preprocesamiento de canal PPG:
 *  1. Detrender por media móvil (ventana ~4 s).
 *  2. Bandpass Butterworth orden 4 (0.5-4 Hz).
 *  3. Seguidor de DC por EMA lento (~2 s).
 *  4. AC = peak-to-peak de la señal filtrada sobre ventana corta (~2 s).
 *  5. Perfusion index = 100 * AC / DC (%).
 *
 * AC se calcula cada muestra recorriendo el buffer circular chico (2 s x fs).
 * No es caro (≈60 comparaciones por frame) y garantiza que `perfusionIndex`
 * no quede a 0 mientras el buffer se llena.
 */
export class PpgPreprocessor {
  private readonly detrender: Detrender
  private readonly band: BandpassFilter
  private readonly displayLp1: Biquad
  private readonly displayLp2: Biquad
  private readonly sampleRate: number
  private dcEma = 0
  private initialized = false
  private readonly acBuffer: Float64Array
  private acIndex = 0
  private acFilled = 0

  constructor(sampleRate: number, lowHz = 0.5, highHz = 4.0) {
    this.sampleRate = sampleRate
    this.detrender = new Detrender(Math.max(30, Math.floor(sampleRate * 4)))
    this.band = new BandpassFilter(sampleRate, lowHz, highHz)
    // LP display ≈ 3 Hz con Q de Butterworth orden 4 en cascada (2 biquads).
    this.displayLp1 = Biquad.lowPass(3.5, sampleRate, Math.SQRT1_2)
    this.displayLp2 = Biquad.lowPass(3.5, sampleRate, Math.SQRT1_2)
    this.acBuffer = new Float64Array(Math.max(30, Math.floor(sampleRate * 2)))
  }

  reset(): void {
    this.detrender.reset()
    this.band.reset()
    this.displayLp1.reset()
    this.displayLp2.reset()
    this.dcEma = 0
    this.initialized = false
    this.acBuffer.fill(0)
    this.acIndex = 0
    this.acFilled = 0
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
    const display = this.displayLp2.process(this.displayLp1.process(detrended))

    this.acBuffer[this.acIndex] = filtered
    this.acIndex = (this.acIndex + 1) % this.acBuffer.length
    if (this.acFilled < this.acBuffer.length) this.acFilled++

    // AC peak-to-peak sobre el buffer realmente lleno. Coste O(N) por muestra
    // pero N es ≤ 60 en 30 Hz; costo despreciable.
    let mx = Number.NEGATIVE_INFINITY
    let mn = Number.POSITIVE_INFINITY
    for (let i = 0; i < this.acFilled; i++) {
      const v = this.acBuffer[i]
      if (v > mx) mx = v
      if (v < mn) mn = v
    }
    const ac = Number.isFinite(mx) && Number.isFinite(mn) ? Math.max(0, mx - mn) : 0
    const dc = Math.max(1, this.dcEma)
    const pi = Math.min(50, Math.max(0, (100 * ac) / dc))
    return { detrended, filtered, display, dc, ac, perfusionIndex: pi }
  }
}
