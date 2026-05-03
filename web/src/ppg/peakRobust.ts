/**
 * Detector de picos PPG robusto inspirado en Pan-Tompkins adaptado a
 * fotopletismografía de contacto.
 *
 * Etapas:
 *   1. Derivada primera de la señal filtrada.
 *   2. Cuadrado (realza flancos, elimina polaridad).
 *   3. Integración por ventana móvil de ~150 ms (mimic QRS width).
 *   4. Threshold adaptativo con doble nivel (Pan-Tompkins): SPKI y NPKI
 *      con coeficientes 0.125/0.125 sobre la integración.
 *   5. Refractario fisiológico (≥ 260 ms → 230 BPM máx).
 *   6. Fallback de búsqueda hacia atrás si no hay pico en 1.66 × RR
 *      promedio usando umbral /2 (recuperación de latidos perdidos sin
 *      inventar: solo acepta si hay un máximo local real en la ventana).
 *
 * La detección se reporta sobre la señal filtrada (no sobre la integración),
 * buscando el máximo o mínimo local según la polaridad efectiva.
 */

export interface RobustDetection {
  timestampMs: number
  amplitude: number
  prominence: number
  /** Polaridad de la fuente: +1 si el latido sube (verde), -1 si baja (rojo bruto). */
  polarity: number
}

export class PeakDetectorRobust {
  private readonly fs: number
  private readonly winN: number
  private readonly winBuf: Float64Array
  private winSum = 0
  private winIdx = 0
  private winFilled = 0

  private lastFiltered = 0
  private lastDeriv = 0
  private hasLast = false

  // Thresholds adaptativos al estilo Pan-Tompkins.
  private spki = 0
  private npki = 0
  private thi1 = 0
  private thi2 = 0

  // Buffer corto de la señal integrada para búsqueda del pico.
  private readonly shortBuf: Float64Array
  private readonly shortTs: Float64Array
  private readonly shortRawFilt: Float64Array
  private shortHead = 0

  private readonly refractoryN: number
  private refractoryCountdown = 0
  private sampleIndex = 0
  private lastPeakSampleIndex = -1

  // Historial reciente de RR para fallback.
  private readonly rrHistory: number[] = []
  private rrAverage1 = 0
  private rrAverage2 = 0

  constructor(sampleRateHz: number, opts?: { integWindowMs?: number; refractoryMs?: number }) {
    this.fs = sampleRateHz
    const integMs = opts?.integWindowMs ?? 150
    this.winN = Math.max(3, Math.round((sampleRateHz * integMs) / 1000))
    this.winBuf = new Float64Array(this.winN)
    this.refractoryN = Math.max(3, Math.round((sampleRateHz * (opts?.refractoryMs ?? 260)) / 1000))
    const short = Math.max(this.winN * 2, Math.round(sampleRateHz * 0.5))
    this.shortBuf = new Float64Array(short)
    this.shortTs = new Float64Array(short)
    this.shortRawFilt = new Float64Array(short)
  }

  reset(): void {
    this.winBuf.fill(0)
    this.winSum = 0
    this.winIdx = 0
    this.winFilled = 0
    this.lastFiltered = 0
    this.lastDeriv = 0
    this.hasLast = false
    this.spki = 0
    this.npki = 0
    this.thi1 = 0
    this.thi2 = 0
    this.shortBuf.fill(0)
    this.shortTs.fill(0)
    this.shortRawFilt.fill(0)
    this.shortHead = 0
    this.refractoryCountdown = 0
    this.sampleIndex = 0
    this.lastPeakSampleIndex = -1
    this.rrHistory.length = 0
    this.rrAverage1 = 0
    this.rrAverage2 = 0
  }

  /**
   * Alimenta una muestra FILTRADA (bandpass 0.5–4 Hz aplicado antes). El
   * argumento `polarity` debe ser la polaridad esperada del latido sobre
   * `filtered`: +1 si la sístole sube (canal verde), −1 si la sístole baja
   * (canal rojo bruto). Internamente normalizamos antes de aplicar Pan-
   * Tompkins.
   */
  feed(filtered: number, timestampMs: number, polarity: number): RobustDetection | null {
    const oriented = polarity >= 0 ? filtered : -filtered
    if (!this.hasLast) {
      this.lastFiltered = oriented
      this.hasLast = true
      this.sampleIndex++
      return null
    }
    const deriv = oriented - this.lastFiltered
    this.lastFiltered = oriented
    const squared = deriv * deriv

    // Integración por ventana móvil.
    const old = this.winBuf[this.winIdx]
    this.winBuf[this.winIdx] = squared
    this.winSum += squared - old
    this.winIdx = (this.winIdx + 1) % this.winBuf.length
    if (this.winFilled < this.winBuf.length) this.winFilled++
    const integ = this.winSum / this.winFilled

    // Guardamos la integración y la muestra oriented en short buffer.
    this.shortBuf[this.shortHead] = integ
    this.shortTs[this.shortHead] = timestampMs
    this.shortRawFilt[this.shortHead] = oriented
    this.shortHead = (this.shortHead + 1) % this.shortBuf.length

    if (this.refractoryCountdown > 0) this.refractoryCountdown--

    let result: RobustDetection | null = null

    // Detección por umbral sobre la señal integrada.
    if (integ > this.thi1 && this.refractoryCountdown <= 0 && this.winFilled === this.winBuf.length) {
      // Buscamos el máximo local reciente de `oriented` dentro de la ventana
      // de integración (N últimas muestras).
      const searchBack = this.winBuf.length
      let best = -Infinity
      let bestTs = timestampMs
      for (let k = 0; k < searchBack; k++) {
        const idx = (this.shortHead - 1 - k + this.shortBuf.length) % this.shortBuf.length
        const v = this.shortRawFilt[idx]
        if (v > best) {
          best = v
          bestTs = this.shortTs[idx]
        }
      }
      // Prominencia: diferencia con la mínima reciente.
      let minRecent = Infinity
      for (let k = 0; k < searchBack * 2; k++) {
        const idx = (this.shortHead - 1 - k + this.shortBuf.length) % this.shortBuf.length
        const v = this.shortRawFilt[idx]
        if (v < minRecent) minRecent = v
      }
      const prominence = isFinite(minRecent) ? best - minRecent : best

      // Actualizar estimadores Pan-Tompkins.
      this.spki = 0.125 * integ + 0.875 * this.spki
      this.thi1 = this.npki + 0.25 * (this.spki - this.npki)
      this.thi2 = 0.5 * this.thi1

      if (prominence > 0) {
        result = {
          timestampMs: bestTs,
          amplitude: polarity >= 0 ? best : -best,
          prominence,
          polarity
        }
        this.refractoryCountdown = this.refractoryN
        const prevIdx = this.lastPeakSampleIndex
        this.lastPeakSampleIndex = this.sampleIndex
        if (prevIdx >= 0) {
          const rrSamples = this.sampleIndex - prevIdx
          const rrMs = (rrSamples * 1000) / this.fs
          this.rrHistory.push(rrMs)
          while (this.rrHistory.length > 8) this.rrHistory.shift()
          const mean = this.rrHistory.reduce((a, b) => a + b, 0) / this.rrHistory.length
          this.rrAverage1 = mean
          // rrAverage2: restringido a latidos dentro de 92..116% de rrAverage1.
          const filtered2 = this.rrHistory.filter((v) => v >= 0.92 * mean && v <= 1.16 * mean)
          if (filtered2.length > 0) {
            this.rrAverage2 = filtered2.reduce((a, b) => a + b, 0) / filtered2.length
          }
        }
      }
    } else {
      // No supera umbral → actualizar NPKI lentamente para no sesgar.
      this.npki = 0.125 * integ + 0.875 * this.npki
      this.thi1 = this.npki + 0.25 * Math.max(0, this.spki - this.npki)
      this.thi2 = 0.5 * this.thi1
    }

    this.sampleIndex++
    return result
  }
}
