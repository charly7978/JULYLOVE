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

/**
 * Simula frames de un dedo sobre flash a `bpm`. En cámara + flash la sístole
 * aparece como una BAJADA del canal rojo y una BAJADA mayor del canal
 * verde. Este generador reproduce esa polaridad física.
 */
function fingerFrame(t: number, bpm: number): CameraFrameStats {
  const hz = bpm / 60
  // Pulso PPG típico: rápido ascenso (dicrotic notch asimétrico). Aquí una
  // aproximación con senoide rectificada fuerte.
  const phase = (t / 1000) * hz
  const frac = phase - Math.floor(phase)
  // `1 - frac` cae rápido en sístole y vuelve en diástole. Para imitar PPG
  // real usamos una senoide desplazada y elevada a potencia 3.
  const ppg = Math.pow(Math.sin(2 * Math.PI * frac - Math.PI / 2) * 0.5 + 0.5, 3)
  // En sístole (ppg=1) la luz reflejada baja → canal rojo baja.
  const redBase = 200
  const greenBase = 155
  const red = redBase - 8 * ppg
  const green = greenBase - 12 * ppg
  const blue = 120 - 1 * ppg
  return {
    timestampMs: t,
    width: 320,
    height: 240,
    redMean: red,
    greenMean: green,
    blueMean: blue,
    clipHighRatio: 0.01,
    clipLowRatio: 0,
    roiCoverage: 0.9,
    roiVariance: 25
  }
}

describe('PpgPipeline', () => {
  it('sin dedo (ambient) no produce sample, bpm ni latidos', () => {
    const p = new PpgPipeline(30)
    for (let i = 0; i < 150; i++) {
      const step = p.process(frame(i * 33), 0, null)
      expect(step.sample).toBeNull()
      expect(step.reading.state).toBe('NO_CONTACT')
      expect(step.reading.bpm).toBeNull()
      expect(step.beat).toBeNull()
    }
  })

  it('contacto parcial tampoco expone bpm', () => {
    const p = new PpgPipeline(30)
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

  it('dedo con PPG real de 72 BPM → detecta BPM dentro de ±10', () => {
    const p = new PpgPipeline(30)
    const durationSec = 20
    const dtMs = 1000 / 30
    let lastReading = null as ReturnType<typeof p.process>['reading'] | null
    let lastBeats = 0
    for (let t = 0; t < durationSec * 1000; t += dtMs) {
      const step = p.process(fingerFrame(t, 72), 0.02, null)
      if (step.reading.beatsDetected > lastBeats) lastBeats = step.reading.beatsDetected
      lastReading = step.reading
    }
    expect(lastBeats).toBeGreaterThanOrEqual(10)
    expect(lastReading?.bpm).not.toBeNull()
    expect(lastReading!.bpm!).toBeGreaterThan(62)
    expect(lastReading!.bpm!).toBeLessThan(82)
  })

  it('dedo con 100 BPM → detecta BPM dentro de ±12', () => {
    const p = new PpgPipeline(30)
    const dtMs = 1000 / 30
    let lastReading = null as ReturnType<typeof p.process>['reading'] | null
    for (let t = 0; t < 20000; t += dtMs) {
      const step = p.process(fingerFrame(t, 100), 0.02, null)
      lastReading = step.reading
    }
    expect(lastReading?.bpm).not.toBeNull()
    expect(lastReading!.bpm!).toBeGreaterThan(88)
    expect(lastReading!.bpm!).toBeLessThan(112)
  })
})
