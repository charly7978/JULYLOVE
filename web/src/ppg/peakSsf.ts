/**
 * Detector de picos PPG basado en Slope Sum Function (Zong et al., 2003,
 * PhysioNet challenge, algoritmo de referencia para PPG arterial).
 *
 * La SSF en la muestra i es:
 *     z[i] = Σ_{k=i-w+1}^{i} max(0, Δx[k])
 * donde Δx[k] = x[k] − x[k-1]. Es la suma acumulada de pendientes
 * positivas dentro de la ventana w (≈125 ms para PPG).
 *
 * Propiedades clave:
 *   - z es SIEMPRE ≥ 0 y presenta un máximo claro poco antes del pico
 *     sistólico (∂x/∂t positivo máximo = onset de la sístole).
 *   - Es robusta al nivel DC, a polaridad, y al ruido blanco.
 *   - El threshold adaptativo se calibra sobre un valor de referencia
 *     z_ref = 3·mean(z) tras 2 s de calibración inicial, y luego se
 *     adapta por EMA al pico real encontrado.
 *
 * Referencias:
 *   Zong W, Heldt T, Moody GB, Mark RG. "An open-source algorithm to
 *   detect onset of arterial blood pressure pulses" Computers in
 *   Cardiology 2003:259-262.
 *
 * Aceptación de picos:
 *   - Cruce ascendente de z sobre threshold → arma ventana de búsqueda.
 *   - Se localiza el MÁXIMO local de la señal FILTRADA dentro de la
 *     ventana de 200 ms posterior al cruce.
 *   - Validación fisiológica: 300 ms ≤ RR ≤ 1600 ms.
 *   - Validación de coherencia: |RR − medianRR_8| / medianRR ≤ 0.40.
 *   - Validación de amplitud: prominencia ≥ 0.35 · amplitud_mediana_reciente.
 */

export interface SsfDetection {
  timestampMs: number
  amplitude: number
  prominence: number
  ssfPeak: number
}

export class PeakDetectorSsf {
  private readonly fs: number
  private readonly winN: number
  private readonly winBuf: Float64Array
  private winSum = 0
  private winIdx = 0
  private winFilled = 0

  private prevFiltered = 0
  private hasPrev = false
  private sampleIndex = 0

  // Threshold adaptativo.
  private thresholdZ = 0
  private ssfMeanEma = 0
  private ssfPeakEma = 0
  private initialized = false
  private readonly learnSamples: number

  // Estado de búsqueda de pico tras cruce.
  private searching = false
  private searchDeadlineSample = 0
  private searchBestAmp = -Infinity
  private searchBestTs = 0
  private searchBestIdx = -1
  private searchMinAmp = Infinity
  private searchZPeak = 0
  private readonly searchWindowN: number

  // Período refractario.
  private readonly refractoryN: number
  private refractoryCountdown = 0
  private lastPeakIdx = -1

  // Historial de amplitudes para validación.
  private readonly ampHistory: number[] = []
  private readonly rrHistory: number[] = []

  constructor(sampleRateHz: number, opts?: { ssfWindowMs?: number; refractoryMs?: number; searchWindowMs?: number }) {
    this.fs = sampleRateHz
    const ssfMs = opts?.ssfWindowMs ?? 125
    this.winN = Math.max(3, Math.round((sampleRateHz * ssfMs) / 1000))
    this.winBuf = new Float64Array(this.winN)
    this.refractoryN = Math.max(3, Math.round((sampleRateHz * (opts?.refractoryMs ?? 280)) / 1000))
    this.searchWindowN = Math.max(3, Math.round((sampleRateHz * (opts?.searchWindowMs ?? 220)) / 1000))
    this.learnSamples = Math.max(30, Math.floor(sampleRateHz * 2))
  }

  reset(): void {
    this.winBuf.fill(0)
    this.winSum = 0
    this.winIdx = 0
    this.winFilled = 0
    this.prevFiltered = 0
    this.hasPrev = false
    this.sampleIndex = 0
    this.thresholdZ = 0
    this.ssfMeanEma = 0
    this.ssfPeakEma = 0
    this.initialized = false
    this.searching = false
    this.searchDeadlineSample = 0
    this.searchBestAmp = -Infinity
    this.searchBestTs = 0
    this.searchBestIdx = -1
    this.searchMinAmp = Infinity
    this.searchZPeak = 0
    this.refractoryCountdown = 0
    this.lastPeakIdx = -1
    this.ampHistory.length = 0
    this.rrHistory.length = 0
  }

  /**
   * Alimenta una muestra filtrada (bandpass 0.5–4 Hz). `polarity` indica
   * la dirección esperada del pico sistólico sobre la señal filtrada:
   * −1 si la sístole es una caída (rojo bruto) y +1 si sube.
   */
  feed(filtered: number, timestampMs: number, polarity: number): SsfDetection | null {
    // Normalizar polaridad: el detector trabaja sobre una señal cuya sístole SUBE.
    const x = polarity >= 0 ? filtered : -filtered

    if (!this.hasPrev) {
      this.prevFiltered = x
      this.hasPrev = true
      this.sampleIndex++
      return null
    }
    const slope = x - this.prevFiltered
    this.prevFiltered = x
    const positive = slope > 0 ? slope : 0

    // Ventana móvil SSF.
    const old = this.winBuf[this.winIdx]
    this.winBuf[this.winIdx] = positive
    this.winSum += positive - old
    this.winIdx = (this.winIdx + 1) % this.winBuf.length
    if (this.winFilled < this.winBuf.length) this.winFilled++
    const z = this.winSum

    // Actualizar estimadores de background lentos.
    if (this.ssfMeanEma === 0) this.ssfMeanEma = z
    else this.ssfMeanEma += 0.01 * (z - this.ssfMeanEma)

    // Calibración inicial de threshold.
    if (!this.initialized) {
      if (this.sampleIndex >= this.learnSamples) {
        this.thresholdZ = 3 * Math.max(1e-9, this.ssfMeanEma)
        this.ssfPeakEma = this.thresholdZ
        this.initialized = true
      }
      this.sampleIndex++
      return null
    }

    if (this.refractoryCountdown > 0) this.refractoryCountdown--

    let result: SsfDetection | null = null

    // Detección por cruce ascendente sobre threshold.
    if (!this.searching && z > this.thresholdZ && this.refractoryCountdown <= 0) {
      this.searching = true
      this.searchDeadlineSample = this.sampleIndex + this.searchWindowN
      this.searchBestAmp = x
      this.searchBestTs = timestampMs
      this.searchBestIdx = this.sampleIndex
      this.searchMinAmp = x
      this.searchZPeak = z
    }

    if (this.searching) {
      if (x > this.searchBestAmp) {
        this.searchBestAmp = x
        this.searchBestTs = timestampMs
        this.searchBestIdx = this.sampleIndex
      }
      if (x < this.searchMinAmp) this.searchMinAmp = x
      if (z > this.searchZPeak) this.searchZPeak = z

      const timedOut = this.sampleIndex >= this.searchDeadlineSample
      const below = z < this.thresholdZ * 0.5
      if (timedOut || below) {
        const prominence = this.searchBestAmp - this.searchMinAmp
        const medAmp = this.medianAmp()
        const ampOk = medAmp === 0 || prominence >= 0.35 * medAmp
        const rr = this.lastPeakIdx >= 0 ? ((this.searchBestIdx - this.lastPeakIdx) * 1000) / this.fs : null
        const rrAbsoluteOk = rr === null || (rr >= 300 && rr <= 1600)
        const rrCoherentOk = rr === null || this.checkCoherence(rr)

        if (ampOk && rrAbsoluteOk && rrCoherentOk) {
          result = {
            timestampMs: this.searchBestTs,
            amplitude: polarity >= 0 ? this.searchBestAmp : -this.searchBestAmp,
            prominence,
            ssfPeak: this.searchZPeak
          }
          // Actualizar threshold: Pan-Tompkins-style. SSF_PEAK_EMA adapta.
          this.ssfPeakEma = 0.125 * this.searchZPeak + 0.875 * this.ssfPeakEma
          this.thresholdZ = this.ssfMeanEma + 0.25 * (this.ssfPeakEma - this.ssfMeanEma)
          this.thresholdZ = Math.max(this.thresholdZ, 2 * this.ssfMeanEma)
          this.refractoryCountdown = this.refractoryN
          this.ampHistory.push(prominence)
          while (this.ampHistory.length > 12) this.ampHistory.shift()
          if (rr !== null) {
            this.rrHistory.push(rr)
            while (this.rrHistory.length > 8) this.rrHistory.shift()
          }
          this.lastPeakIdx = this.searchBestIdx
        } else {
          // Rechazo: ajustamos lentamente threshold hacia arriba para
          // evitar cascadas de falsos positivos.
          this.thresholdZ = Math.max(this.thresholdZ, this.searchZPeak * 0.9)
        }

        this.searching = false
        this.searchBestAmp = -Infinity
        this.searchBestIdx = -1
        this.searchMinAmp = Infinity
        this.searchZPeak = 0
      }
    }

    this.sampleIndex++
    return result
  }

  private medianAmp(): number {
    const n = this.ampHistory.length
    if (n === 0) return 0
    const s = this.ampHistory.slice().sort((a, b) => a - b)
    return n % 2 === 0 ? (s[n / 2 - 1] + s[n / 2]) / 2 : s[(n - 1) / 2]
  }

  private checkCoherence(rr: number): boolean {
    const n = this.rrHistory.length
    if (n < 2) return true
    const s = this.rrHistory.slice().sort((a, b) => a - b)
    const median = n % 2 === 0 ? (s[n / 2 - 1] + s[n / 2]) / 2 : s[(n - 1) / 2]
    if (median <= 0) return true
    return Math.abs(rr - median) / median <= 0.40
  }
}
