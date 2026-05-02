import { describe, expect, it } from 'vitest'
import { Spo2Estimator, type CalibrationProfile } from '../spo2'

function feed(est: Spo2Estimator, fs: number, seconds: number) {
  const n = fs * seconds
  const beatHz = 1.2
  for (let i = 0; i < n; i++) {
    const t = i / fs
    est.push(
      140 + 2 * Math.sin(2 * Math.PI * beatHz * t),
      80 + 0.8 * Math.sin(2 * Math.PI * beatHz * t + 0.1),
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
  it('devuelve null sin calibración', () => {
    const e = new Spo2Estimator(30)
    feed(e, 30, 10)
    expect(e.estimate(null, 1.5, 0.8, 0.05, 0.01).spo2).toBeNull()
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
})
