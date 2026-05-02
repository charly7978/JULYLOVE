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

export class Spo2Estimator {
  private readonly redBuf: Float64Array
  private readonly blueBuf: Float64Array
  private readonly greenBuf: Float64Array
  private index = 0
  private filled = 0
  private redDcEma = 0
  private blueDcEma = 0
  private greenDcEma = 0
  private hasDc = false
  private readonly alpha: number

  constructor(sampleRateHz: number, windowSeconds = 6) {
    const n = Math.max(60, Math.floor(sampleRateHz * windowSeconds))
    this.redBuf = new Float64Array(n)
    this.blueBuf = new Float64Array(n)
    this.greenBuf = new Float64Array(n)
    this.alpha = 1 / (sampleRateHz * 3)
  }

  reset(): void {
    this.redBuf.fill(0)
    this.blueBuf.fill(0)
    this.greenBuf.fill(0)
    this.index = 0
    this.filled = 0
    this.redDcEma = 0
    this.blueDcEma = 0
    this.greenDcEma = 0
    this.hasDc = false
  }

  push(redMean: number, greenMean: number, blueMean: number): void {
    if (!this.hasDc) {
      this.redDcEma = redMean
      this.greenDcEma = greenMean
      this.blueDcEma = blueMean
      this.hasDc = true
    } else {
      this.redDcEma += this.alpha * (redMean - this.redDcEma)
      this.greenDcEma += this.alpha * (greenMean - this.greenDcEma)
      this.blueDcEma += this.alpha * (blueMean - this.blueDcEma)
    }
    this.redBuf[this.index] = redMean - this.redDcEma
    this.blueBuf[this.index] = blueMean - this.blueDcEma
    this.greenBuf[this.index] = greenMean - this.greenDcEma
    this.index = (this.index + 1) % this.redBuf.length
    if (this.filled < this.redBuf.length) this.filled++
  }

  estimate(
    calibration: CalibrationProfile | null,
    perfusionIndex: number,
    sqi: number,
    motionScore: number,
    clipHighRatio: number
  ): Spo2Result {
    if (this.filled < this.redBuf.length) {
      return { spo2: null, confidence: 0, ratioOfRatios: null, redAcDc: null, blueAcDc: null, greenAcDc: null, reason: 'ventana_incompleta' }
    }
    const [rAc, rDc] = this.pp(this.redBuf, this.redDcEma)
    const [bAc, bDc] = this.pp(this.blueBuf, this.blueDcEma)
    const [gAc, gDc] = this.pp(this.greenBuf, this.greenDcEma)

    const rRatio = rDc > 1 ? rAc / rDc : 0
    const bRatio = bDc > 1 ? bAc / bDc : 0
    const gRatio = gDc > 1 ? gAc / gDc : 0
    if (rRatio <= 0 || bRatio <= 0) {
      return { spo2: null, confidence: 0, ratioOfRatios: null, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'ac_dc_insuficiente' }
    }
    const r = rRatio / bRatio
    if (r < 0.3 || r > 3) {
      return { spo2: null, confidence: 0, ratioOfRatios: r, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'r_fuera_rango' }
    }
    if (perfusionIndex < 0.5) return { spo2: null, confidence: 0, ratioOfRatios: r, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'perfusion_baja' }
    if (motionScore > 0.35) return { spo2: null, confidence: 0, ratioOfRatios: r, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'movimiento' }
    if (clipHighRatio > 0.18) return { spo2: null, confidence: 0, ratioOfRatios: r, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'clipping' }
    if (sqi < 0.45) return { spo2: null, confidence: 0, ratioOfRatios: r, redAcDc: rRatio, blueAcDc: bRatio, greenAcDc: gRatio, reason: 'sqi_bajo' }

    // Estimación provisional con fórmula empírica estándar. Sin
    // calibración clínica, la confidence nunca supera 0.5 y la UI muestra
    // el disclaimer "estimación provisional — no clínica".
    const A = calibration?.coefficientA ?? 110
    const B = calibration?.coefficientB ?? 25
    const raw = A - B * r
    const spo2 = Math.min(100, Math.max(70, raw))
    const confBase = sqi * (1 - Math.min(1, Math.max(0, motionScore)))
    const confidence = Math.min(1, Math.max(0, calibration ? confBase : Math.min(0.5, confBase)))
    return {
      spo2,
      confidence,
      ratioOfRatios: r,
      redAcDc: rRatio,
      blueAcDc: bRatio,
      greenAcDc: gRatio,
      reason: calibration ? 'ok' : 'provisional_no_clinico'
    }
  }

  private pp(buf: Float64Array, dcEma: number): [number, number] {
    let mx = Number.NEGATIVE_INFINITY
    let mn = Number.POSITIVE_INFINITY
    for (let i = 0; i < buf.length; i++) {
      const v = buf[i]
      if (v > mx) mx = v
      if (v < mn) mn = v
    }
    return [Math.max(0, mx - mn), dcEma]
  }
}

const STORAGE_KEY = 'forensic-ppg-calibration-profiles-v1'

export class DeviceCalibrationManager {
  private readonly algorithmVersion = 'spo2-ppg-web-v1'

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
        (p) => p.cameraId === cameraId && p.deviceModel === deviceModel && p.algorithmVersion === this.algorithmVersion
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
    const valid = points.filter((p) => p.sqi >= 0.5 && p.motionScore < 0.3 && p.perfusionIndex > 0.3)
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
      (p) => !(p.cameraId === profile.cameraId && p.deviceModel === profile.deviceModel && p.algorithmVersion === profile.algorithmVersion)
    )
    all.push(profile)
    this.saveAll(all)
    return profile
  }
}
