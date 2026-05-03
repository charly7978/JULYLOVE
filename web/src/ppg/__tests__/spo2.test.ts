import { describe, expect, it } from 'vitest'
import { Spo2Estimator, type CalibrationProfile } from '../spo2'

function feed(est: Spo2Estimator, fs: number, seconds: number) {
  const n = fs * seconds
  const beatHz = 1.2
  for (let i = 0; i < n; i++) {
    const t = i / fs
    // Bajo flash blanco + dedo: G es más pulsátil que R (Hb absorbe
    // más verde). Modelamos AC_R/DC_R ≈ 1.4% y AC_G/DC_G ≈ 1.0% para
    // saturación normal (~98%): R = 0.014/0.010 ≈ 1.4 → 110 − 25·1.4 ≈ 75
    // [se aclara]: usamos coeficientes fisiológicos para validar curva.
    est.push(
      140 + 2.0 * Math.sin(2 * Math.PI * beatHz * t),
      80 + 0.9 * Math.sin(2 * Math.PI * beatHz * t + 0.1),
      40 + 0.3 * Math.sin(2 * Math.PI * beatHz * t + 0.2)
    )
  }
}

const cal: CalibrationProfile = {
  profileId: 't',
  deviceModel: 't',
  cameraId: '0',
  exposureTimeNs: null,
  iso: null,
  torchIntensity: null,
  coefficientA: 110,
  coefficientB: 25,
  createdAtMs: 0,
  algorithmVersion: 'spo2-ppg-web-v1',
  calibrationSamples: 3,
  minPerfusionIndex: 0.5,
  notes: ''
}

describe('Spo2Estimator', () => {
  it('sin calibración devuelve estimación provisional con confianza <= 0.5', () => {
    const e = new Spo2Estimator(30)
    feed(e, 30, 10)
    const r = e.estimate(null, 1.5, 0.8, 0.05, 0.01)
    expect(r.spo2).not.toBeNull()
    expect(r.reason).toBe('provisional_no_clinico')
    expect(r.confidence).toBeLessThanOrEqual(0.55)
  })

  it('devuelve número con calibración y señal aceptable', () => {
    const e = new Spo2Estimator(30)
    feed(e, 30, 10)
    const r = e.estimate(cal, 1.5, 0.8, 0.05, 0.01)
    expect(r.spo2).not.toBeNull()
    expect(r.reason).toBe('ok')
  })

  it('rechaza con movimiento alto', () => {
    const e = new Spo2Estimator(30)
    feed(e, 30, 10)
    expect(e.estimate(cal, 1.5, 0.8, 0.9, 0.01).spo2).toBeNull()
  })

  it('ratio R/G crece cuando AC_R relativo crece (saturación baja)', () => {
    const fs = 30
    // Caso "más absorción de rojo" (saturación más baja): incrementamos
    // amplitud AC del rojo manteniendo verde fijo. La RoR debe subir.
    const baseline = new Spo2Estimator(fs)
    feed(baseline, fs, 8)
    const rBase = baseline.estimate(cal, 1.5, 0.8, 0.05, 0.01).ratioOfRatios as number

    const lower = new Spo2Estimator(fs)
    const beatHz = 1.2
    for (let i = 0; i < fs * 8; i++) {
      const t = i / fs
      lower.push(
        140 + 4.0 * Math.sin(2 * Math.PI * beatHz * t),
        80 + 0.9 * Math.sin(2 * Math.PI * beatHz * t),
        40 + 0.3 * Math.sin(2 * Math.PI * beatHz * t)
      )
    }
    const rLower = lower.estimate(cal, 1.5, 0.8, 0.05, 0.01).ratioOfRatios as number
    expect(rLower).toBeGreaterThan(rBase)
  })
})
