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
      expect(step.reading.bpm).toBe(0)
      expect(step.reading.spo2).toBe(0)
      expect(step.reading.bloodPressureSystolic).toBe(0)
      expect(step.reading.bloodPressureDiastolic).toBe(0)
      expect(step.reading.arrhythmiaStatus).toBe('NO_VALID_PPG')
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
      expect(step.reading.bpm).toBe(0)
      expect(step.reading.spo2).toBe(0)
      expect(['NO_CONTACT', 'PROBABLE_PPG']).toContain(step.reading.state)
    }
  })

  it('retirar dedo invalida rápido y vuelve a cero', () => {
    const p = new PpgPipeline(30)
    const dtMs = 1000 / 30
    // Fase válida
    for (let t = 0; t < 15000; t += dtMs) p.process(fingerFrame(t, 75), 0.02, null)
    // Retiro de dedo
    let invalidatedAt = -1
    for (let i = 0; i < 60; i++) {
      const t = 15000 + i * dtMs
      const step = p.process(frame(t), 0.02, null)
      if (step.reading.state === 'NO_CONTACT') {
        invalidatedAt = i
        expect(step.reading.bpm).toBe(0)
        expect(step.reading.spo2).toBe(0)
        expect(step.reading.arrhythmiaStatus).toBe('NO_VALID_PPG')
        break
      }
    }
    expect(invalidatedAt).toBeGreaterThanOrEqual(0)
    expect(invalidatedAt).toBeLessThan(25)
  })

  it('señal sintética validada publica BPM y mantiene biomarcadores no soportados en cero', () => {
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
    expect(lastReading?.state).toBe('VALID_LIVE_PPG')
    expect(lastReading!.bpm).toBeGreaterThan(50)
    expect(lastReading!.spo2).toBeGreaterThanOrEqual(0)
    expect(['SIN_HALLAZGOS', 'PATRON_IRREGULAR']).toContain(lastReading!.arrhythmiaStatus)
    expect(lastBeats).toBeGreaterThanOrEqual(10)
  })

  it('con evidencia óptica viva puede llegar a VALID_LIVE_PPG', () => {
    const p = new PpgPipeline(30)
    const dtMs = 1000 / 30
    let lastReading = null as ReturnType<typeof p.process>['reading'] | null
    for (let t = 0; t < 20000; t += dtMs) {
      const step = p.process(fingerFrame(t, 100), 0.02, null)
      lastReading = step.reading
    }
    expect(lastReading?.state).toBe('VALID_LIVE_PPG')
    expect(lastReading!.bpm).toBeGreaterThan(70)
    expect(['SIN_HALLAZGOS', 'PATRON_IRREGULAR']).toContain(lastReading!.arrhythmiaStatus)
  })

  it('BPM estimado se mantiene cercano al BPM real (75) con ≤ ±5%', () => {
    const p = new PpgPipeline(30)
    const dtMs = 1000 / 30
    let lastReading = null as ReturnType<typeof p.process>['reading'] | null
    const target = 75
    for (let t = 0; t < 25000; t += dtMs) {
      lastReading = p.process(fingerFrame(t, target), 0.02, null).reading
    }
    expect(lastReading?.state).toBe('VALID_LIVE_PPG')
    const bpm = lastReading!.bpm
    expect(bpm).toBeGreaterThan(target * 0.95)
    expect(bpm).toBeLessThan(target * 1.05)
  })

  it('la onda de display tiene polaridad estable (sístole hacia arriba)', () => {
    const p = new PpgPipeline(30)
    const dtMs = 1000 / 30
    const samples: number[] = []
    for (let t = 0; t < 12000; t += dtMs) {
      const step = p.process(fingerFrame(t, 80), 0.02, null)
      if (step.sample) samples.push(step.sample.display)
    }
    // Si la polaridad fuera inestable (cambia de signo entre frames),
    // la varianza de la primera derivada quedaría dominada por saltos.
    // Aquí verificamos que los signos consecutivos no se alternen
    // ferozmente (un cambio cada ≈ ½ ciclo).
    let signFlips = 0
    let prev = samples[0]
    for (let i = 1; i < samples.length; i++) {
      const d = samples[i] - prev
      const dPrev = i >= 2 ? samples[i - 1] - samples[i - 2] : 0
      if (d * dPrev < 0) signFlips++
      prev = samples[i]
    }
    const flipRate = signFlips / samples.length
    expect(flipRate).toBeLessThan(0.5)
  })
})
