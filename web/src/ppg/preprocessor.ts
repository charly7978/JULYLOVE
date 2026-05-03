import { BandpassFilter, Biquad } from './filters'

export interface PreprocessorOutput {
  /** Solo DC removido (HP 0.4 Hz): morfología PPG natural, para dibujar. */
  detrended: number
  /** Banda 0.5-4 Hz: óptimo para detección de picos. */
  filtered: number
  /** Señal morfológica suavizada (detrended + LP 3 Hz): ideal para pantalla. */
  display: number
  dc: number
  ac: number
  perfusionIndex: number
  /** True si el frame es un artefacto (cambio abrupto del flash o dedo). */
  artifact: boolean
}

/**
 * Preprocesamiento de canal PPG de alta robustez.
 *
 *  1. Seguidor DC exponencial (EMA con τ≈2 s).
 *  2. Detection de ARTEFACTOS: si |raw − DC| / DC > 15 %, el frame se
 *     declara artefacto y el bandpass NO se alimenta con ese valor (usa el
 *     DC como sustituto). Esto evita que cambios abruptos de iluminación
 *     del flash o del dedo desastabilicen los filtros IIR durante varios
 *     segundos.
 *  3. Detrend por high-pass Butterworth de 0.4 Hz (NO por media móvil,
 *     cuya respuesta a escalones es un step gigante que tarda ~N samples
 *     en desaparecer y se ve como un spike enorme en pantalla).
 *  4. Bandpass Butterworth 0.5–4 Hz sobre la señal detrended para el
 *     detector de picos.
 *  5. LP Butterworth 3 Hz sobre la señal detrended para la visualización:
 *     morfología PPG natural (subida sistólica rápida + bajada lenta).
 *  6. AC peak-to-peak en ventana 2 s + PI = 100·AC/DC.
 */
export class PpgPreprocessor {
  private readonly hp1: Biquad
  private readonly hp2: Biquad
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
    this.hp1 = Biquad.highPass(0.4, sampleRate, Math.SQRT1_2)
    this.hp2 = Biquad.highPass(0.4, sampleRate, Math.SQRT1_2)
    this.band = new BandpassFilter(sampleRate, lowHz, highHz)
    this.displayLp1 = Biquad.lowPass(3.0, sampleRate, Math.SQRT1_2)
    this.displayLp2 = Biquad.lowPass(3.0, sampleRate, Math.SQRT1_2)
    this.acBuffer = new Float64Array(Math.max(30, Math.floor(sampleRate * 2)))
  }

  reset(): void {
    this.hp1.reset()
    this.hp2.reset()
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
    }
    // Artefacto: salto > 15 % respecto al DC actual.
    const dev = Math.abs(rawSample - this.dcEma) / Math.max(1, this.dcEma)
    const artifact = dev > 0.15
    // DC sigue al valor estable (EMA). En un artefacto, el DC sigue
    // lentamente para no contaminarse.
    const alpha = artifact ? 1 / (this.sampleRate * 6) : 1 / (this.sampleRate * 2)
    this.dcEma += alpha * (rawSample - this.dcEma)

    // Para los filtros usamos el rawSample si no es artefacto; si lo es,
    // sustituimos por el DC (equivalente a "no novedad", mantiene los IIR
    // sin excitar).
    const clean = artifact ? this.dcEma : rawSample
    const hp = this.hp2.process(this.hp1.process(clean))
    const filtered = this.band.process(hp)
    const display = this.displayLp2.process(this.displayLp1.process(hp))

    this.acBuffer[this.acIndex] = filtered
    this.acIndex = (this.acIndex + 1) % this.acBuffer.length
    if (this.acFilled < this.acBuffer.length) this.acFilled++

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

    return { detrended: hp, filtered, display, dc, ac, perfusionIndex: pi, artifact }
  }
}
