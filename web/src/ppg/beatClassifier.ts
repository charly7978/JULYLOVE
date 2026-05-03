import type { BeatEvent, BeatType } from './types'

export class BeatClassifier {
  private readonly history: number[] = []

  constructor(
    private readonly rrHistoryLimit = 16,
    private readonly prematureFactor = 0.8,
    private readonly pauseFactor = 1.3,
    private readonly irregularityFactor = 0.2
  ) {}

  reset(): void {
    this.history.length = 0
  }

  classify(candidate: BeatEvent, sqi: number): BeatEvent {
    if (sqi < 0.35) {
      return { ...candidate, type: 'INVALID_SIGNAL', reason: 'señal_insuficiente', quality: sqi }
    }
    const rr = candidate.rrMs
    if (rr === null) {
      return { ...candidate, type: 'NORMAL', reason: 'primer_rr', quality: sqi }
    }
    if (rr < 250 || rr > 2000) {
      return { ...candidate, type: 'INVALID_SIGNAL', reason: 'rr_fuera_rango', quality: sqi }
    }
    const median = this.medianRr()
    if (median === null) {
      this.history.push(rr)
      this.trim()
      return { ...candidate, type: 'NORMAL', reason: 'sin_historico', quality: sqi }
    }
    const deviation = Math.abs(rr - median) / median
    let type: BeatType = 'NORMAL'
    if (rr < median * this.prematureFactor) type = 'SUSPECT_PREMATURE'
    else if (rr > median * this.pauseFactor) type = 'SUSPECT_PAUSE'
    else if (deviation > this.irregularityFactor) type = 'IRREGULAR'

    this.history.push(rr)
    this.trim()

    let reason = ''
    if (type === 'SUSPECT_PREMATURE') reason = `rr=${rr.toFixed(0)}ms < 0.80 × mediana(${median.toFixed(0)}ms)`
    else if (type === 'SUSPECT_PAUSE') reason = `rr=${rr.toFixed(0)}ms > 1.30 × mediana(${median.toFixed(0)}ms)`
    else if (type === 'IRREGULAR') reason = `|rr-mediana|/mediana=${deviation.toFixed(2)}`
    else reason = `rr=${rr.toFixed(0)}ms`

    return { ...candidate, type, reason, quality: sqi }
  }

  private trim(): void {
    while (this.history.length > this.rrHistoryLimit) this.history.shift()
  }

  medianRr(): number | null {
    if (this.history.length < 3) return null
    const sorted = this.history.slice().sort((a, b) => a - b)
    const n = sorted.length
    return n % 2 === 0 ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2 : sorted[(n - 1) / 2]
  }
}
