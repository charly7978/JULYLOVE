import { describe, expect, it } from 'vitest'
import { PpgPipeline } from '../pipeline'
import type { CameraFrameStats } from '../types'

function frame(t: number, opts: Partial<CameraFrameStats> = {}): CameraFrameStats {
  return {
    timestampMs: t,
    width: 320,
    height: 240,
    redMean: opts.redMean ?? 30,
    greenMean: opts.greenMean ?? 80,
    blueMean: opts.blueMean ?? 100,
    clipHighRatio: opts.clipHighRatio ?? 0,
    clipLowRatio: opts.clipLowRatio ?? 0,
    roiCoverage: opts.roiCoverage ?? 0,
    roiVariance: opts.roiVariance ?? 20
  }
}

describe('PpgPipeline', () => {
  it('sin dedo (pantalla ambient) no produce sample, bpm ni latidos', () => {
    const p = new PpgPipeline(30)
    p.setTargetFps(30)
    // 5 segundos sin dedo: R bajo, R<G, sin cobertura.
    for (let i = 0; i < 150; i++) {
      const step = p.process(frame(i * 33), 0, null)
      expect(step.sample).toBeNull()
      expect(step.reading.state).toBe('NO_CONTACT')
      expect(step.reading.bpm).toBeNull()
      expect(step.reading.spo2).toBeNull()
      expect(step.beat).toBeNull()
    }
  })

  it('contacto parcial tampoco expone bpm', () => {
    const p = new PpgPipeline(30)
    p.setTargetFps(30)
    for (let i = 0; i < 150; i++) {
      const step = p.process(
        frame(i * 33, { redMean: 150, greenMean: 130, blueMean: 110, roiCoverage: 0.4 }),
        0,
        null
      )
      expect(step.reading.bpm).toBeNull()
      expect(['NO_CONTACT', 'CONTACT_PARTIAL']).toContain(step.reading.state)
    }
  })
})
