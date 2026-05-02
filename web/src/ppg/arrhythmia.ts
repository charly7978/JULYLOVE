import type { BeatEvent } from './types'

export interface ArrhythmiaSummary {
  rrCount: number
  meanRr: number | null
  sdnnMs: number | null
  rmssdMs: number | null
  pnn50: number | null
  coefficientOfVariation: number | null
  flagIrregular: boolean
  flagHighHrv: boolean
}

export class ArrhythmiaScreening {
  private readonly rrWindow: number[] = []

  constructor(
    private readonly windowLimit = 32,
    private readonly cvThreshold = 0.12,
    private readonly pnn50Threshold = 0.3
  ) {}

  reset(): void {
    this.rrWindow.length = 0
  }

  ingest(beat: BeatEvent): void {
    if (beat.rrMs === null || beat.type === 'INVALID_SIGNAL') return
    this.rrWindow.push(beat.rrMs)
    while (this.rrWindow.length > this.windowLimit) this.rrWindow.shift()
  }

  compute(sqi: number): ArrhythmiaSummary {
    const n = this.rrWindow.length
    if (n < 5 || sqi < 0.35) {
      return {
        rrCount: n,
        meanRr: null,
        sdnnMs: null,
        rmssdMs: null,
        pnn50: null,
        coefficientOfVariation: null,
        flagIrregular: false,
        flagHighHrv: false
      }
    }
    const mean = this.rrWindow.reduce((a, b) => a + b, 0) / n
    let s2 = 0
    for (const v of this.rrWindow) {
      const d = v - mean
      s2 += d * d
    }
    const sdnn = Math.sqrt(s2 / n)
    const cv = sdnn / mean
    let diffSum = 0
    let nn50 = 0
    let comparisons = 0
    for (let i = 1; i < n; i++) {
      const d = this.rrWindow[i] - this.rrWindow[i - 1]
      diffSum += d * d
      if (Math.abs(d) > 50) nn50++
      comparisons++
    }
    const rmssd = comparisons > 0 ? Math.sqrt(diffSum / comparisons) : null
    const pnn50 = comparisons > 0 ? nn50 / comparisons : null
    return {
      rrCount: n,
      meanRr: mean,
      sdnnMs: sdnn,
      rmssdMs: rmssd,
      pnn50,
      coefficientOfVariation: cv,
      flagIrregular: cv > this.cvThreshold,
      flagHighHrv: (pnn50 ?? 0) > this.pnn50Threshold
    }
  }
}
