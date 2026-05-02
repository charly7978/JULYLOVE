import { describe, expect, it } from 'vitest'
import { BeatClassifier } from '../beatClassifier'
import type { BeatEvent } from '../types'

function beat(rr: number | null): BeatEvent {
  return {
    timestampMs: 0,
    amplitude: 1,
    rrMs: rr,
    bpmInstant: rr !== null ? 60000 / rr : null,
    quality: 0,
    type: 'NORMAL',
    reason: ''
  }
}

describe('BeatClassifier', () => {
  it('primer latido es NORMAL', () => {
    const c = new BeatClassifier()
    expect(c.classify(beat(null), 0.8).type).toBe('NORMAL')
  })

  it('premature se detecta', () => {
    const c = new BeatClassifier()
    for (let i = 0; i < 5; i++) c.classify(beat(1000), 0.8)
    expect(c.classify(beat(650), 0.8).type).toBe('SUSPECT_PREMATURE')
  })

  it('pause se detecta', () => {
    const c = new BeatClassifier()
    for (let i = 0; i < 5; i++) c.classify(beat(900), 0.8)
    expect(c.classify(beat(1500), 0.8).type).toBe('SUSPECT_PAUSE')
  })

  it('SQI bajo → INVALID_SIGNAL', () => {
    const c = new BeatClassifier()
    expect(c.classify(beat(800), 0.1).type).toBe('INVALID_SIGNAL')
  })
})
