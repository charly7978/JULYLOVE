/**
 * Transformada discreta sólo‑real O(N²) determinista sobre ventanas acotadas (≤512 muestras).
 * Evita dependencias externas y errores sutiles en FFT radix‑2.
 */

export function nextPow2(n: number): number {
  let p = 1;
  while (p < n) p <<= 1;
  return p;
}

/** Hann explícito; sin RNG */

export function applyHannInPlace(samples: Float64Array): void {
  const len = samples.length;
  const factor = Math.PI / Math.max(len - 1, 1);
  for (let i = 0; i < len; i++)
    samples[i]! *= 0.5 * (1 - Math.cos(i * factor * 2));
}

/** Bin k → Hz con N puntos Fs */

export function binToHz(k: number, n: number, fs: number): number {
  const safeFs = Math.max(fs, 1);
  const safeN = Math.max(n, 1);
  return (k * safeFs) / safeN;
}

/**
 * Magnitud |X[k]| de DFT sólo-real (imagen entrada cero).
 * longitud resultado floor(N/2)+1 bins.
 */

export function dftMagnitudesReal(x: Float64Array): Float64Array {
  const n = x.length;
  const outLen = Math.floor(n / 2) + 1;
  const mags = new Float64Array(outLen);
  const twoPiOverN = (Math.PI * 2) / Math.max(n, 1);

  for (let k = 0; k < outLen; k++) {
    let re = 0;
    let im = 0;
    for (let t = 0; t < n; t++) {
      const angle = -(twoPiOverN * k * t);
      const c = Math.cos(angle);
      const s = Math.sin(angle);
      const xt = x[t]!;
      re += xt * c;
      im += xt * s;
    }
    mags[k] = Math.hypot(re, im);
  }
  return mags;
}

export type SpectralHr = Readonly<{
  bpm: number | null;
  peakHz: number | null;
  /** pico dentro de banda / mediana dentro de esa banda (>1 mejor) */
  snrBand: number;
}>;

/** Busca máximo dentro de banda fisiológica típica */

export function estimateSpectralHr(
  bandpassedApprox: Float64Array,
  fsHz: number,
): SpectralHr {
  const fs = Math.max(12, fsHz);
  if (bandpassedApprox.length < 64) {
    return { bpm: null, peakHz: null, snrBand: 0 };
  }
  const padded = padToPow2Mean(bandpassedApprox);
  applyHannInPlace(padded);
  const mag = dftMagnitudesReal(padded);
  const N = padded.length;

  let bestK = -1;
  let bestPow = -1;

  /** Hz = k fs / N  → banda pulsátil aprox ~0.7–4 Hz adulto típico */
  const kLow = Math.max(2, Math.ceil((0.7 * N) / fs));
  const kHigh = Math.min(mag.length - 1, Math.floor((4.0 * N) / fs));

  if (kHigh <= kLow + 2) return { bpm: null, peakHz: null, snrBand: 0 };

  const bandVals: number[] = [];
  for (let k = kLow; k <= kHigh; k++) {
    bandVals.push(mag[k]!);
    if (mag[k]! > bestPow) {
      bestPow = mag[k]!;
      bestK = k;
    }
  }
  let medBand = RollingMedianRough(bandVals);
  if (!(medBand > 1e-18)) medBand = 1e-18;

  const snrBand = Math.min(320, Math.max(bestPow <= 0 ? 0 : bestPow / medBand));

  if (bestK < 0 || bestPow <= 1e-12) {
    return { bpm: null, peakHz: null, snrBand };
  }

  const hzPeak = binToHz(bestK, N, fs);
  if (!(hzPeak > 0.65 && hzPeak < 4.2)) return { bpm: null, peakHz: hzPeak, snrBand };
  const bpm = hzPeak * 60;
  if (!(Number.isFinite(bpm) && bpm > 40 && bpm < 210))
    return { bpm: null, peakHz: hzPeak, snrBand };
  return {
    bpm,
    peakHz: hzPeak,
    snrBand,
  };
}

function padToPow2Mean(x: Float64Array): Float64Array {
  let mean = 0;
  for (let i = 0; i < x.length; i++) mean += x[i]!;
  mean /= Math.max(x.length, 1);
  const L = Math.min(512, nextPow2(Math.max(64, x.length)));
  const y = new Float64Array(L);
  for (let i = 0; i < L; i++) y[i] = i < x.length ? x[i]! : mean;
  return y;
}

function RollingMedianRough(vals: readonly number[]): number {
  const s = [...vals].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  if (s.length === 0) return 1e-9;
  return s.length % 2 ? s[m]! : 0.5 * (s[m - 1]! + s[m]!);
}
