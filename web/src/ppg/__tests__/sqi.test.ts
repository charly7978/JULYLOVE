import { describe, expect, it } from 'vitest'
import { SignalQualityIndex } from '../sqi'

describe('SignalQualityIndex', () => {
  const sqi = new SignalQualityIndex()
  const base = {
    hasContact: true,
    perfusionIndex: 2.5,
    clipHighRatio: 0.01,
    clipLowRatio: 0,
    motionScore: 0.05,
    fpsActual: 30,
    fpsTarget: 30,
    spectralCoherence: 0.9,
    rrCv: 0.03,
    rrCount: 15,
    roiSpatialStd: 8
  }

  it('cero sin contacto', () => {
    expect(sqi.evaluate({ ...base, hasContact: false })).toBe(0)
  })

  it('alto en condiciones ideales', () => {
    expect(sqi.evaluate(base)).toBeGreaterThan(0.5)
  })

  it('cero con clipping fuerte', () => {
    expect(sqi.evaluate({ ...base, clipHighRatio: 0.5 })).toBe(0)
  })

  it('etiquetas de banda correctas', () => {
    expect(sqi.band(0.9)).toBe('EXCELLENT')
    expect(sqi.band(0.6)).toBe('GOOD')
    expect(sqi.band(0.4)).toBe('DEGRADED')
    expect(sqi.band(0.1)).toBe('INVALID')
  })
})
