import { describe, expect, it } from 'vitest'
import { BandpassFilter, Detrender } from '../filters'

function rms(arr: Float64Array | number[], skip = 0) {
  let s = 0
  let c = 0
  for (let i = skip; i < arr.length; i++) {
    const v = arr[i]
    s += v * v
    c++
  }
  return c > 0 ? Math.sqrt(s / c) : 0
}

describe('BandpassFilter', () => {
  it('deja pasar frecuencia dentro de banda (1.5 Hz)', () => {
    const fs = 60
    const filter = new BandpassFilter(fs, 0.5, 4)
    const n = 1200
    const input = new Array(n).fill(0).map((_, i) => Math.sin((2 * Math.PI * 1.5 * i) / fs))
    const output = input.map((v) => filter.process(v))
    expect(rms(output, 400)).toBeGreaterThan(0.55 * rms(input, 200))
  })

  it('atenúa deriva lenta (0.05 Hz)', () => {
    const fs = 60
    const filter = new BandpassFilter(fs, 0.5, 4)
    const n = 3000
    const arr: number[] = []
    for (let i = 0; i < n; i++) arr.push(filter.process(Math.sin((2 * Math.PI * 0.05 * i) / fs)))
    expect(rms(arr, 1000)).toBeLessThan(0.05)
  })

  it('atenúa frecuencias altas (12 Hz)', () => {
    const fs = 60
    const filter = new BandpassFilter(fs, 0.5, 4)
    const arr: number[] = []
    for (let i = 0; i < 1200; i++) arr.push(filter.process(Math.cos((2 * Math.PI * 12 * i) / fs)))
    expect(rms(arr, 300)).toBeLessThan(0.1)
  })
})

describe('Detrender', () => {
  it('remueve tendencia lineal', () => {
    const det = new Detrender(64)
    let maxAbs = 0
    for (let i = 0; i < 1024; i++) {
      const out = det.process(i)
      if (i >= 128) maxAbs = Math.max(maxAbs, Math.abs(out))
    }
    expect(maxAbs).toBeLessThan(40)
  })

  it('centra señal constante a cero', () => {
    const det = new Detrender(16)
    for (let i = 0; i < 64; i++) det.process(5)
    const out = det.process(5)
    expect(Math.abs(out)).toBeLessThan(0.001)
  })
})
