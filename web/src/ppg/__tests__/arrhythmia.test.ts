import { describe, expect, it } from 'vitest'
import { ArrhythmiaScreening } from '../arrhythmia'
import type { BeatEvent } from '../types'

function beat(rr: number): BeatEvent {
  return { timestampMs: 0, amplitude: 1, rrMs: rr, bpmInstant: 60000 / rr, quality: 0.9, type: 'NORMAL', reason: '' }
}

describe('ArrhythmiaScreening', () => {
  it('ritmo regular produce CV bajo y sin flag', () => {
    const s = new ArrhythmiaScreening()
    for (let i = 0; i < 20; i++) s.ingest(beat(900))
    const r = s.compute(0.9)
    expect(r.flagIrregular).toBe(false)
    expect((r.coefficientOfVariation ?? 1) < 0.01).toBe(true)
  })

  it('ritmo irregular sobrepasa umbral', () => {
    const s = new ArrhythmiaScreening()
    ;[900, 700, 1200, 850, 600, 1400, 900, 700, 1100, 800, 650, 1300].forEach((rr) => s.ingest(beat(rr)))
    const r = s.compute(0.9)
    expect((r.coefficientOfVariation ?? 0) > 0.12).toBe(true)
    expect(r.flagIrregular).toBe(true)
  })

  it('SQI bajo bloquea cribado', () => {
    const s = new ArrhythmiaScreening()
    for (let i = 0; i < 20; i++) s.ingest(beat(900))
    expect(s.compute(0.1).sdnnMs).toBeNull()
  })
})
