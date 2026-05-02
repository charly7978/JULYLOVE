import { describe, expect, it } from 'vitest'
import { HeartRateFusion } from '../fusion'

describe('HeartRateFusion', () => {
  const f = new HeartRateFusion()
  it('null cuando SQI es extremadamente bajo', () => {
    const r = f.fuse(72, 10, 74, 0.9, 0.03)
    expect(r.bpm).toBeNull()
    expect(r.source).toBe('bajo_sqi')
  })

  it('fusión cuando RR y espectro concuerdan', () => {
    const r = f.fuse(72, 10, 73, 0.9, 0.7)
    expect(r.bpm).not.toBeNull()
    expect(r.source).toBe('fusion')
  })

  it('fallback a RR cuando espectro discrepa', () => {
    const r = f.fuse(72, 10, 100, 0.9, 0.8)
    expect(r.bpm).toBe(72)
    expect(r.source).toBe('rr_disagree')
  })

  it('sin RR y coherencia baja devuelve null', () => {
    const r = f.fuse(null, 0, 70, 0.1, 0.7)
    expect(r.bpm).toBeNull()
  })
})
