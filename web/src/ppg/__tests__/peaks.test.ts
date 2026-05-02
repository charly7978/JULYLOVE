import { describe, expect, it } from 'vitest'
import { BandpassFilter } from '../filters'
import { PeakDetectorElgendi } from '../peakElgendi'

function wave(fs: number, bpm: number, seconds: number) {
  const hz = bpm / 60
  const n = Math.floor(fs * seconds)
  const out = new Array(n)
  for (let i = 0; i < n; i++) out[i] = Math.sin((2 * Math.PI * hz * i) / fs)
  return out
}

describe('PeakDetectorElgendi', () => {
  it('cuenta latidos a 60 BPM dentro de tolerancia', () => {
    const fs = 60
    const samples = wave(fs, 60, 12)
    const filter = new BandpassFilter(fs, 0.5, 4)
    const det = new PeakDetectorElgendi(fs)
    let peaks = 0
    for (let i = 0; i < samples.length; i++) {
      const f = filter.process(samples[i])
      if (det.feed(f, (i * 1000) / fs)) peaks++
    }
    expect(peaks).toBeGreaterThanOrEqual(10)
    expect(peaks).toBeLessThanOrEqual(13)
  })

  it('cuenta latidos a 120 BPM dentro de tolerancia', () => {
    const fs = 60
    const samples = wave(fs, 120, 10)
    const filter = new BandpassFilter(fs, 0.5, 4)
    const det = new PeakDetectorElgendi(fs)
    let peaks = 0
    for (let i = 0; i < samples.length; i++) {
      const f = filter.process(samples[i])
      if (det.feed(f, (i * 1000) / fs)) peaks++
    }
    expect(peaks).toBeGreaterThanOrEqual(17)
    expect(peaks).toBeLessThanOrEqual(22)
  })

  it('no detecta picos en señal plana', () => {
    const fs = 60
    const det = new PeakDetectorElgendi(fs)
    const filter = new BandpassFilter(fs, 0.5, 4)
    let peaks = 0
    for (let i = 0; i < fs * 10; i++) if (det.feed(filter.process(0), (i * 1000) / fs)) peaks++
    expect(peaks).toBe(0)
  })
})
