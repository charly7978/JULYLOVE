/**
 * Detector de picos sólo desde señal real (sin Math.random ni BPM inventado).
 */

function median(vals: readonly number[]): number {
  if (vals.length === 0) return Number.NaN;
  const s = [...vals].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid]! : 0.5 * (s[mid - 1]! + s[mid]!);
}

function madFromMedian(vals: readonly number[], med: number): number {
  if (vals.length === 0) return Number.NaN;
  const ds = [...vals].map((v) => Math.abs(v - med)).sort((a, b) => a - b);
  const mid = Math.floor(ds.length / 2);
  return ds.length % 2 ? ds[mid]! : 0.5 * ((ds[mid - 1] ?? 0) + (ds[mid] ?? 0));
}

export type BeatEstimate = Readonly<{
  /** índices de muestra del pico aceptados */
  peakIdx: readonly number[];
  rrMsList: Float64Array;
  rrMedianMs: number;
  bpmFromPeaksMedian: number | null;
  confidencePeaksOnly: number;
}>;

/** Encuentra latidos válidos usando umbral MAD + período refractario físico (~300 ms real) */

export function detectBeats(
  normalizedG3z: Float64Array,
  fsHzApprox: number,
): BeatEstimate {
  const n = normalizedG3z.length;
  if (n < 40) {
    return {
      peakIdx: [],
      rrMsList: new Float64Array(0),
      rrMedianMs: Number.NaN,
      bpmFromPeaksMedian: null,
      confidencePeaksOnly: 0,
    };
  }

  const fs = Math.max(10, fsHzApprox);
  const refractorySamples = Math.max(Math.round(0.3 * fs), 4);

  const med = median([...normalizedG3z]);
  const m = madFromMedian([...normalizedG3z], med);
  const noiseFloor = Number.isFinite(m) && m > 1e-9 ? m : 1e-6;
  const thresh = Math.max(0.5, noiseFloor * 6.8);

  const peaks: number[] = [];
  for (let i = refractorySamples; i < n - refractorySamples; i++) {
    const prev = normalizedG3z[i - 1]!;
    const here = normalizedG3z[i]!;
    const next = normalizedG3z[i + 1]!;
    if (here <= prev || here <= next) continue;
    if (here < thresh) continue;

    peaks.push(i);
    i += refractorySamples;
  }

  if (peaks.length < 2) {
    return {
      peakIdx: peaks,
      rrMsList: new Float64Array(0),
      rrMedianMs: Number.NaN,
      bpmFromPeaksMedian: null,
      confidencePeaksOnly: peaks.length >= 2 ? 0.08 : 0,
    };
  }

  const rrMs: number[] = [];
  for (let k = 1; k < peaks.length; k++) {
    rrMs.push(((peaks[k]! - peaks[k - 1]!) / fs) * 1000);
  }
  const rrArr = Float64Array.from(rrMs);

  /** filtrado RR físico 300–2100 ms (~28–200 lpm prácticos) */
  const phys = rrMs.filter((r) => r >= 285 && r <= 2160 && Number.isFinite(r));
  if (phys.length < 2) {
    return {
      peakIdx: peaks,
      rrMsList: rrArr,
      rrMedianMs: Number.NaN,
      bpmFromPeaksMedian: null,
      confidencePeaksOnly: 0.12,
    };
  }

  const rrMedian = median(phys.slice(-Math.min(64, phys.length)));
  /** Variabilidad relativa como factor de confianza */
  const spread = madFromMedian(phys, rrMedian);

  /** CV aprox usando MAD/normalizado RR */
  const cvRel = rrMedian !== 0 && Number.isFinite(spread / rrMedian)
    ? Math.min(8, spread / rrMedian)
    : 999;

  const conf = Number.isFinite(cvRel)
    ? Math.max(
        0,
        Math.min(1, 1 - Math.max(cvRel - 0.08, 0) * 8.5),
      )
    : 0;

  const bpmMedian =
    Number.isFinite(rrMedian) && rrMedian > 0
      ? 60000 / rrMedian
      : null;

  return {
    peakIdx: peaks,
    rrMsList: rrArr,
    rrMedianMs: rrMedian,
    bpmFromPeaksMedian:
      typeof bpmMedian === "number" && Number.isFinite(bpmMedian) ? bpmMedian : null,
    confidencePeaksOnly: Number.isFinite(conf) ? conf : 0,
  };
}
