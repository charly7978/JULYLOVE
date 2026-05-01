import { SignalQualityTier } from "./types";

export type SqiInput = Readonly<{
  acDc: number;
  meanClip: number;
  motion01: number;
  contact01: number;
  validCellFrac: number;
  rgCorr: number;
  beatConf: number;
  fftSnrBand: number;
  spectraPeaksAgreement01: number;
}>;

function clamp01(x: number): number {
  return Math.min(1, Math.max(0, x));
}

/** Puntuación 0–100 sin aleatoriedad; pesos fijos documentados en código */

export function computeSqiScore(inp: SqiInput): number {
  const acShape = clamp01((Math.log10(Math.max(inp.acDc, 1e-6)) + 3) / 1.15);
  const clipPenalty = clamp01(inp.meanClip * 4.2);
  const motionPenalty = clamp01(inp.motion01 * 1.05);
  const contact = clamp01(inp.contact01);
  const valid = clamp01((inp.validCellFrac - 0.15) / 0.75);
  const corr = clamp01((inp.rgCorr + 0.15) / 1.15);
  const beat = clamp01(inp.beatConf);
  const fft = clamp01(Math.log(1 + Math.max(0, inp.fftSnrBand)) / Math.log(1 + 36));
  const spec = clamp01(inp.spectraPeaksAgreement01);

  let raw =
    19 * acShape +
    15 * fft +
    18 * beat +
    12 * corr +
    12 * contact +
    10 * valid +
    14 * spec -
    14 * motionPenalty -
    22 * clipPenalty;

  if (inp.acDc < 0.00095) raw *= 0.55;
  if (inp.validCellFrac < 0.12) raw *= 0.45;

  return Math.min(100, Math.max(0, Math.round(raw)));
}

export function classifyTier(score: number): SignalQualityTier {
  if (score <= 20) return SignalQualityTier.INVALID;
  if (score <= 40) return SignalQualityTier.POOR;
  if (score <= 60) return SignalQualityTier.FAIR;
  if (score <= 80) return SignalQualityTier.GOOD;
  return SignalQualityTier.EXCELLENT;
}
