/**
 * G1 (verde fusión bruto), G2 OD baseline lento verificable (sin constantes físicas falsas).
 * G3: paso‑banda práctico Web mediante HP+LP orden‑1 tras OD (aprox banda pulsátil).
 */

const OD_EPS = 1e-3;
const OD_TAU_SEC = 2.2;

export type IirHpState = { xPrev: number; yPrev: number };
export type IirLpState = { y: number };

function hpOrder1(fcHz: number, fsHz: number, x: number, s: IirHpState): number {
  const fs = Math.max(12, fsHz);
  const fcClamped = Math.min(Math.max(fcHz, 0.08), fs * 0.2);
  const a = Math.exp((-Math.PI * 2 * fcClamped) / fs);
  const y = a * (s.yPrev + x - s.xPrev);
  s.xPrev = x;
  s.yPrev = y;
  return y;
}

function lpOrder1(fcHz: number, fsHz: number, x: number, st: IirLpState): number {
  const fs = Math.max(12, fsHz);
  const fcClamped = Math.min(fcHz, fs * 0.42);
  const alpha = 1 - Math.exp((-Math.PI * 2 * fcClamped) / fs);
  st.y += alpha * (x - st.y);
  return st.y;
}

function medianNaive(values: readonly number[]): number {
  if (values.length === 0) return Number.NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2
    ? sorted[mid]!
    : 0.5 * (sorted[mid - 1]! + sorted[mid]!);
}

export class RollingMedianMADNormalizer {
  private readonly maxlen: number;
  private readonly buf: number[] = [];

  constructor(maxSamples: number) {
    this.maxlen = Math.max(96, Math.floor(maxSamples));
  }

  normalize(x: number, minNeed = 120): { med: number; scale: number; z: number } {
    this.buf.push(x);
    while (this.buf.length > this.maxlen) this.buf.shift();
    const sorted = [...this.buf].sort((a, b) => a - b);
    const med = RollingMedianMADNormalizer.pickMedianSorted(sorted);
    const devSorted = [...this.buf]
      .map((v) => Math.abs(v - med))
      .sort((a, b) => a - b);
    let madScaled = RollingMedianMADNormalizer.pickMedianSorted(devSorted) * 1.4826;
    if (!(madScaled > 1e-7 && Number.isFinite(madScaled))) madScaled = 1e-7;
    if (this.buf.length < minNeed) return { med, scale: madScaled, z: 0 };
    return { med, scale: madScaled, z: (x - med) / madScaled };
  }

  static pickMedianSorted(sorted: readonly number[]): number {
    if (sorted.length === 0) return 0;
    const mid = Math.floor(sorted.length / 2);
    return sorted.length % 2
      ? sorted[mid]!
      : 0.5 * (sorted[mid - 1]! + sorted[mid]!);
  }
}

export class OpticalDensityTracker {
  private baseline = -1;

  push(gMean: number, fsHz: number): number {
    const fs = Math.max(12, fsHz);
    const a = Math.exp(-1 / Math.max(fs * OD_TAU_SEC, 1));
    this.baseline = this.baseline < 0 ? Math.max(gMean, 14) : a * this.baseline + (1 - a) * gMean;
    const gSafe = Math.max(gMean + OD_EPS, OD_EPS * 80);
    const bSafe = Math.max(this.baseline + OD_EPS, OD_EPS * 80);
    return -Math.log(gSafe / bSafe);
  }
}

export type PpgSampleFrame = Readonly<{
  tMs: number;
  fsHz: number;
  g1: number;
  g2Od: number;
  g3bp: number;
  g3z: number;
  acApprox: number;
  dcEwma: number;
}>;

export type WaveformTail = Readonly<{
  t: Float64Array;
  g3z: Float64Array;
  len: number;
}>;

/** Historial lineal acotado: evita bugs de índices circulares en Fase 1 */

export class PPGSignalPipeline {
  private readonly hpOd: IirHpState = { xPrev: 0, yPrev: 0 };
  private readonly lpOd: IirLpState = { y: 0 };
  private readonly dcSlow: IirLpState = { y: 0 };
  private readonly hpQuickG: IirHpState = { xPrev: 0, yPrev: 0 };

  private readonly od = new OpticalDensityTracker();
  private readonly norm = new RollingMedianMADNormalizer(520);

  private readonly tHist: number[] = [];
  private readonly zHist: number[] = [];
  private readonly dtHist: number[] = [];
  private readonly maxHistory: number;
  private readonly maxDtHist: number;

  private lastTimestamp: number | null = null;
  private acEwmaQuick = Number.NaN;

  constructor(historyCap = 900, dtCap = 384) {
    this.maxHistory = Math.max(200, Math.floor(historyCap));
    this.maxDtHist = Math.max(96, Math.floor(dtCap));
  }

  private pushDt(deltaMs: number): void {
    if (!Number.isFinite(deltaMs) || deltaMs <= 0 || deltaMs > 800) return;
    this.dtHist.push(deltaMs);
    while (this.dtHist.length > this.maxDtHist) this.dtHist.shift();
  }

  private estimateFsFromDtHist(): number {
    if (this.dtHist.length < 12) return 29.5;
    const ok = this.dtHist.filter((d) => d > 2 && d < 340 && Number.isFinite(d));
    const medDt = medianNaive(ok.slice(-256));
    if (!Number.isFinite(medDt) || medDt <= 0.5) return 29.5;
    return Math.min(160, Math.max(10, 1000 / medDt));
  }

  /** Alias explícito monitor UI */
  getWaveformTail(maxPoints?: number): WaveformTail {
    return this.trimTail(maxPoints);
  }

  private trimTail(maxPoints?: number): WaveformTail {
    const lim = Math.min(typeof maxPoints === "number" ? maxPoints : this.zHist.length, this.zHist.length);
    const start = this.zHist.length - lim;
    const tArr = new Float64Array(Math.max(lim, 0));
    const zArr = new Float64Array(Math.max(lim, 0));
    for (let i = 0; i < lim; i++) {
      tArr[i] = this.tHist[start + i]!;
      zArr[i] = this.zHist[start + i]!;
    }
    return { t: tArr, g3z: zArr, len: lim };
  }

  peekAcDc(sample: Pick<PpgSampleFrame, "acApprox" | "dcEwma">): number {
    const dc = Math.max(sample.dcEwma, 1e-9);
    return sample.acApprox / dc;
  }

  push(tPerfMs: number, g1: number): PpgSampleFrame {
    let deltaMs = Number.NaN;
    if (this.lastTimestamp !== null)
      deltaMs = tPerfMs - this.lastTimestamp;
    this.lastTimestamp = tPerfMs;
    if (Number.isFinite(deltaMs)) this.pushDt(deltaMs);

    const fsHz = this.estimateFsFromDtHist();

    const dcEwma = lpOrder1(0.42, fsHz, g1, this.dcSlow);
    const g2Od = this.od.push(g1, fsHz);
    const hp = hpOrder1(0.73, fsHz, g2Od, this.hpOd);
    const g3bp = lpOrder1(4.2, fsHz, hp, this.lpOd);

    const { z: g3z } = this.norm.normalize(g3bp);

    const hq = hpOrder1(8.8, fsHz, g1, this.hpQuickG);
    const instAc = Math.abs(hq);

    const acPrev = Number.isFinite(this.acEwmaQuick)
      ? this.acEwmaQuick
      : instAc;
    this.acEwmaQuick = acPrev * (1 - 0.086) + instAc * 0.086;

    this.tHist.push(tPerfMs);
    this.zHist.push(g3z);
    while (this.tHist.length > this.maxHistory) this.tHist.shift();
    while (this.zHist.length > this.maxHistory) this.zHist.shift();

    return Object.freeze({
      tMs: tPerfMs,
      fsHz,
      g1,
      g2Od,
      g3bp,
      g3z,
      acApprox: this.acEwmaQuick,
      dcEwma,
    });
  }
}
