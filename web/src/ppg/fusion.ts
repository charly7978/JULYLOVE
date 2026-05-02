export interface FusedHeartRate {
  bpm: number | null
  confidence: number
  source: string
}

export class HeartRateFusion {
  constructor(
    private readonly tolerancePercent: number = 15,
    private readonly minBeatsForRr: number = 5
  ) {}

  fuse(
    rrBpm: number | null,
    rrBeats: number,
    specBpm: number | null,
    specCoherence: number,
    sqi: number
  ): FusedHeartRate {
    if (sqi < 0.35) return { bpm: null, confidence: 0, source: 'bajo_sqi' }
    const rrUsable = rrBpm !== null && rrBeats >= this.minBeatsForRr
    const specUsable = specBpm !== null && specCoherence >= 0.25

    if (rrUsable && specUsable) {
      const delta = (Math.abs((rrBpm as number) - (specBpm as number)) / (rrBpm as number)) * 100
      if (delta <= this.tolerancePercent) {
        const w = Math.min(0.85, Math.max(0.55, rrBeats / (rrBeats + 4)))
        const bpm = (rrBpm as number) * w + (specBpm as number) * (1 - w)
        const conf = Math.max(0, Math.min(1, (0.5 + 0.5 * (1 - delta / this.tolerancePercent)) * sqi))
        return { bpm, confidence: conf, source: 'fusion' }
      }
      return { bpm: rrBpm, confidence: Math.min(1, 0.35 * sqi), source: 'rr_disagree' }
    }
    if (rrUsable) {
      const conf = Math.min(0.9, 0.5 + 0.05 * rrBeats) * sqi
      return { bpm: rrBpm, confidence: conf, source: 'rr_only' }
    }
    if (specUsable) {
      const conf = Math.min(0.8, specCoherence * sqi)
      return { bpm: specBpm, confidence: conf, source: 'spec_only' }
    }
    return { bpm: null, confidence: 0, source: 'insuficiente' }
  }
}
