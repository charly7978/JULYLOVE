import { BandpassFilter, Detrender } from './filters'

export interface CalibrationProfile {
  profileId: string
  deviceModel: string
  cameraId: string
  exposureTimeNs: number | null
  iso: number | null
  torchIntensity: number | null
  coefficientA: number
  coefficientB: number
  createdAtMs: number
  algorithmVersion: string
  calibrationSamples: number
  minPerfusionIndex: number
  notes: string
}

export interface CalibrationPoint {
  capturedAtMs: number
  referenceSpo2: number
  ratioOfRatios: number
  sqi: number
  perfusionIndex: number
  motionScore: number
}

export interface Spo2Result {
  spo2: number | null
  confidence: number
  ratioOfRatios: number | null
  redAcDc: number | null
  blueAcDc: number | null
  greenAcDc: number | null
  reason: string
}

/**
 * Estimador SpO₂ por ratio-of-ratios (RoR) sobre cámara con flash blanco.
 *
 * Modelo físico:
 *   bajo iluminación blanca, la absorción depende del coeficiente molar
 *   ε(λ) de HbO₂ y Hb. Para 540 nm (verde) ε es alto y prácticamente
 *   isobestico, mientras que en 660 nm (rojo) ε de Hb es alto pero el
 *   de HbO₂ es bajo. La ratio canónica para fuentes blancas (Scully
 *   2012, Lamonaca 2017, Ding 2018, Wieringa 2005) es:
 *
 *        R = (AC_red / DC_red) / (AC_green / DC_green)
 *
 *   El AC se calcula peak-to-peak sobre una ventana de 4–6 s del canal
 *   ya filtrado en banda 0.5–4 Hz. El DC se sigue con EMA τ≈3 s del
 *   canal crudo.
 *
 * Calibración:
 *   - Sin perfil: SpO₂ = A − B·R con (A, B) = (110, 25), reportada con
 *     reason="provisional_no_clinico" y confianza ≤ 0.55 (no clínica).
 *   - Con perfil ajustado (≥3 puntos contra oxímetro de referencia,
 *     mínimos cuadrados ordinarios) se reemplazan A y B.
 *
 * Suavizado de salida: EMA τ ≈ 3 s. Gates: perfusión, clipping alto,
 * clipping bajo, movimiento.
 */
export class Spo2Estimator {
  private readonly fs: number
  private readonly bandR: BandpassFilter
  private readonly bandG: BandpassFilter
  private readonly bandB: BandpassFilter
  private readonly detR: Detrender
  private readonly detG: Detrender
  private readonly detB: Detrender
  private dcR = 0
  private dcG = 0
  private dcB = 0
  private hasDc = false
  private readonly alphaDc: number
  private readonly acWinR: Float64Array
  private readonly acWinG: Float64Array
  private readonly acWinB: Float64Array
  private acIdx = 0
  private acFilled = 0
  private readonly ratioWin: number[] = []
  private spo2Ema: number | null = null
  private readonly emaAlpha: number

  constructor(fs: number, windowSeconds = 5) {
    this.fs = fs
    this.bandR = new BandpassFilter(fs, 0.5, 4)
    this.bandG = new BandpassFilter(fs, 0.5, 4)
    this.bandB = new BandpassFilter(fs, 0.5, 4)
    const detW = Math.max(30, Math.floor(fs * 3))
    this.detR = new Detrender(detW)
    this.detG = new Detrender(detW)
    this.detB = new Detrender(detW)
    this.alphaDc = 1 / Math.max(1, fs * 3)
    const n = Math.max(60, Math.floor(fs * windowSeconds))
    this.acWinR = new Float64Array(n)
    this.acWinG = new Float64Array(n)
    this.acWinB = new Float64Array(n)
    // EMA τ ≈ 3 s
    this.emaAlpha = 1 - Math.exp(-1 / (fs * 3))
  }

  reset(): void {
    this.bandR.reset()
    this.bandG.reset()
    this.bandB.reset()
    this.detR.reset()
    this.detG.reset()
    this.detB.reset()
    this.dcR = 0
    this.dcG = 0
    this.dcB = 0
    this.hasDc = false
    this.acWinR.fill(0)
    this.acWinG.fill(0)
    this.acWinB.fill(0)
    this.acIdx = 0
    this.acFilled = 0
    this.ratioWin.length = 0
    this.spo2Ema = null
  }

  push(redMean: number, greenMean: number, blueMean: number): void {
    if (!this.hasDc) {
      this.dcR = redMean
      this.dcG = greenMean
      this.dcB = blueMean
      this.hasDc = true
    } else {
      this.dcR += this.alphaDc * (redMean - this.dcR)
      this.dcG += this.alphaDc * (greenMean - this.dcG)
      this.dcB += this.alphaDc * (blueMean - this.dcB)
    }
    const rAc = this.bandR.process(this.detR.process(redMean))
    const gAc = this.bandG.process(this.detG.process(greenMean))
    const bAc = this.bandB.process(this.detB.process(blueMean))
    this.acWinR[this.acIdx] = rAc
    this.acWinG[this.acIdx] = gAc
    this.acWinB[this.acIdx] = bAc
    this.acIdx = (this.acIdx + 1) % this.acWinR.length
    if (this.acFilled < this.acWinR.length) this.acFilled++
  }

  estimate(
    calibration: CalibrationProfile | null,
    perfusionIndex: number,
    sqi: number,
    motionScore: number,
    clipHighRatio: number
  ): Spo2Result {
    if (this.acFilled < Math.floor(this.acWinR.length * 0.6)) {
      return this.empty('ventana_incompleta')
    }
    const rAcPp = peakToPeak(this.acWinR, this.acFilled)
    const gAcPp = peakToPeak(this.acWinG, this.acFilled)
    const bAcPp = peakToPeak(this.acWinB, this.acFilled)
    const rRatio = this.dcR > 1 ? rAcPp / this.dcR : 0
    const gRatio = this.dcG > 1 ? gAcPp / this.dcG : 0
    const bRatio = this.dcB > 1 ? bAcPp / this.dcB : 0
    if (rRatio <= 0 || gRatio <= 0) return this.empty('ac_dc_insuficiente')
    // Ratio canónico R/G para flash blanco: Hb absorbe verde mucho más
    // que rojo (ε_Hb 540nm ≫ ε_Hb 660nm) y HbO2 absorbe poco rojo.
    const ratio = rRatio / gRatio
    this.ratioWin.push(ratio)
    while (this.ratioWin.length > 90) this.ratioWin.shift()
    const rMedian = median(this.ratioWin)
    if (!(rMedian >= 0.25 && rMedian <= 3.5)) return this.empty('r_fuera_rango')

    // Gates de calidad.
    if (perfusionIndex < 0.2) return this.empty('perfusion_baja')
    if (motionScore > 0.5) return this.empty('movimiento')
    if (clipHighRatio > 0.3) return this.empty('clipping')

    // Curva empírica. Con calibración: los coeficientes se reemplazan.
    const A = calibration?.coefficientA ?? 110
    const B = calibration?.coefficientB ?? 25
    const raw = A - B * rMedian
    const clamped = Math.min(100, Math.max(70, raw))

    // EMA de salida para estabilidad visual.
    if (this.spo2Ema === null) this.spo2Ema = clamped
    else this.spo2Ema = this.spo2Ema + this.emaAlpha * (clamped - this.spo2Ema)

    const base = Math.max(0, Math.min(1, sqi * (1 - Math.min(1, Math.max(0, motionScore)))))
    const confidence = Math.min(1, Math.max(0, calibration ? base : Math.min(0.55, base)))

    return {
      spo2: this.spo2Ema,
      confidence,
      ratioOfRatios: rMedian,
      redAcDc: rRatio,
      greenAcDc: gRatio,
      blueAcDc: bRatio,
      reason: calibration ? 'ok' : 'provisional_no_clinico'
    }
  }

  private empty(reason: string): Spo2Result {
    return {
      spo2: null,
      confidence: 0,
      ratioOfRatios: null,
      redAcDc: null,
      blueAcDc: null,
      greenAcDc: null,
      reason
    }
  }
}

function peakToPeak(buf: Float64Array, filled: number): number {
  let mx = -Infinity
  let mn = Infinity
  for (let i = 0; i < filled; i++) {
    const v = buf[i]
    if (v > mx) mx = v
    if (v < mn) mn = v
  }
  return Math.max(0, mx - mn)
}

function median(arr: number[]): number {
  if (arr.length === 0) return 0
  const s = arr.slice().sort((a, b) => a - b)
  const n = s.length
  return n % 2 === 0 ? (s[n / 2 - 1] + s[n / 2]) / 2 : s[(n - 1) / 2]
}

const STORAGE_KEY = 'forensic-ppg-calibration-profiles-v2'

export class DeviceCalibrationManager {
  private readonly algorithmVersion = 'spo2-ppg-web-v2'

  loadAll(): CalibrationProfile[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (!raw) return []
      const arr = JSON.parse(raw)
      if (!Array.isArray(arr)) return []
      return arr as CalibrationProfile[]
    } catch {
      return []
    }
  }

  saveAll(profiles: CalibrationProfile[]): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(profiles))
  }

  find(cameraId: string, deviceModel: string): CalibrationProfile | null {
    return (
      this.loadAll().find(
        (p) =>
          p.cameraId === cameraId &&
          p.deviceModel === deviceModel &&
          p.algorithmVersion === this.algorithmVersion
      ) ?? null
    )
  }

  fit(
    cameraId: string,
    deviceModel: string,
    exposureTimeNs: number | null,
    iso: number | null,
    torchIntensity: number | null,
    points: CalibrationPoint[],
    notes = ''
  ): CalibrationProfile | null {
    const valid = points.filter(
      (p) => p.sqi >= 0.5 && p.motionScore < 0.3 && p.perfusionIndex > 0.3
    )
    if (valid.length < 3) return null
    const n = valid.length
    const sumX = valid.reduce((a, p) => a + p.ratioOfRatios, 0)
    const sumY = valid.reduce((a, p) => a + p.referenceSpo2, 0)
    const sumXY = valid.reduce((a, p) => a + p.ratioOfRatios * p.referenceSpo2, 0)
    const sumXX = valid.reduce((a, p) => a + p.ratioOfRatios * p.ratioOfRatios, 0)
    const denom = n * sumXX - sumX * sumX
    if (Math.abs(denom) < 1e-9) return null
    const slope = (n * sumXY - sumX * sumY) / denom
    const intercept = (sumY - slope * sumX) / n
    const profile: CalibrationProfile = {
      profileId: crypto.randomUUID(),
      deviceModel,
      cameraId,
      exposureTimeNs,
      iso,
      torchIntensity,
      coefficientA: intercept,
      coefficientB: -slope,
      createdAtMs: Date.now(),
      algorithmVersion: this.algorithmVersion,
      calibrationSamples: valid.length,
      minPerfusionIndex: Math.min(...valid.map((v) => v.perfusionIndex)),
      notes
    }
    const all = this.loadAll().filter(
      (p) =>
        !(
          p.cameraId === profile.cameraId &&
          p.deviceModel === profile.deviceModel &&
          p.algorithmVersion === profile.algorithmVersion
        )
    )
    all.push(profile)
    this.saveAll(all)
    return profile
  }
}
