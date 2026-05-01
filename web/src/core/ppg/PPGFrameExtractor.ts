/**
 * Extracción real ROI centrado → rejilla SubROI.
 */

import type { FrameRoiPacket, SubRoiMetric } from "./types";

function trimmedMean(samples: readonly number[], trimRatio: number): number {
  if (samples.length === 0) return 0;
  const sorted = [...samples].sort((a, b) => a - b);
  const cut = Math.floor(sorted.length * trimRatio);
  const lo = Math.max(0, cut);
  const hi = sorted.length - 1 - Math.max(0, cut);
  if (hi < lo) {
    let s = 0;
    for (const v of sorted) s += v;
    return s / sorted.length;
  }
  let sum = 0;
  let n = 0;
  for (let i = lo; i <= hi; i++) {
    sum += sorted[i]!;
    n++;
  }
  return n ? sum / n : 0;
}

function cellMetricsFromSamples(
  rs: readonly number[],
  gs: readonly number[],
  bs: readonly number[],
  clipHi: number,
  clipLo: number,
): SubRoiMetric {
  const rMean = trimmedMean(rs, 0.12);
  const gMean = trimmedMean(gs, 0.12);
  const bMean = trimmedMean(bs, 0.12);
  const luma = 0.299 * rMean + 0.587 * gMean + 0.114 * bMean;

  let mn = 255;
  let mx = 0;
  const nPix = gs.length || 1;
  for (let i = 0; i < gs.length; i++) {
    const r = rs[i]!;
    const g = gs[i]!;
    const b = bs[i]!;
    const u = r > g ? (r > b ? r : b) : g > b ? g : b;
    const low = r < g ? (r < b ? r : b) : g < b ? g : b;
    mx = Math.max(mx, u);
    mn = Math.min(mn, low);
  }

  let varAcc = 0;
  for (const g of gs) {
    const d = g - gMean;
    varAcc += d * d;
  }
  const spatialVar = gs.length > 1 ? varAcc / gs.length : 0;

  return {
    rMean,
    gMean,
    bMean,
    luma,
    satProxy: Math.max(0, Math.min(1, (mx - mn) / 255)),
    clipHigh: clipHi / nPix,
    clipLow: clipLo / nPix,
    spatialVar,
  };
}

export class PPGFrameExtractor {
  private readonly canvas: HTMLCanvasElement;
  private readonly ctx: CanvasRenderingContext2D;

  constructor(
    private readonly cols: number,
    private readonly rows: number,
    private readonly roiFraction = 0.45,
    internalResolution = 80,
  ) {
    const c = document.createElement("canvas");
    c.width = internalResolution;
    c.height = internalResolution;
    const ctx = c.getContext("2d", {
      willReadFrequently: true,
      alpha: false,
    });
    if (!ctx) throw new Error("Canvas 2D no disponible.");
    this.canvas = c;
    this.ctx = ctx;
  }

  dispose(): void {
    this.canvas.width = 1;
    this.canvas.height = 1;
  }

  extract(video: HTMLVideoElement, perfNow: DOMHighResTimeStamp): FrameRoiPacket | null {
    const vw = video.videoWidth;
    const vh = video.videoHeight;
    if (vw <= 0 || vh <= 0) return null;
    const side = Math.min(vw, vh);
    const rw = Math.max(32, Math.floor(side * this.roiFraction));
    const sx = Math.floor((vw - rw) / 2);
    const sy = Math.floor((vh - rw) / 2);

    const { canvas } = this;
    this.ctx.drawImage(video, sx, sy, rw, rw, 0, 0, canvas.width, canvas.height);
    const img = this.ctx.getImageData(0, 0, canvas.width, canvas.height);
    const w = canvas.width;
    const h = canvas.height;
    const cw = Math.floor(w / this.cols);
    const ch = Math.floor(h / this.rows);
    const grid: SubRoiMetric[] = [];

    for (let gy = 0; gy < this.rows; gy++) {
      const y0 = gy * ch;
      for (let gx = 0; gx < this.cols; gx++) {
        const x0 = gx * cw;
        const rs: number[] = [];
        const gs: number[] = [];
        const bs: number[] = [];
        let clipHi = 0;
        let clipLo = 0;
        const step = cw * ch >= 900 ? 3 : 2;
        for (let y = y0; y < y0 + ch; y += step) {
          const rowBase = (y * w + x0) * 4;
          for (let x = 0; x < cw; x += step) {
            const i = rowBase + x * 4;
            const r = img.data[i]!;
            const g = img.data[i + 1]!;
            const b = img.data[i + 2]!;
            rs.push(r);
            gs.push(g);
            bs.push(b);
            if (g >= 252) clipHi++;
            if (g <= 4) clipLo++;
          }
        }
        grid.push(cellMetricsFromSamples(rs, gs, bs, clipHi, clipLo));
      }
    }

    return {
      tPerf: perfNow,
      videoWidth: vw,
      videoHeight: vh,
      grid,
      cols: this.cols,
      rows: this.rows,
    };
  }
}

export function fusedFromGrid(
  grid: readonly SubRoiMetric[],
): Readonly<{
  g1: number;
  rRaw: number;
  bRaw: number;
  luma: number;
  fusionWeightsUsed: number;
  validCells: number;
}> {
  const idxs: number[] = [];
  const weights: number[] = [];

  for (let i = 0; i < grid.length; i++) {
    const c = grid[i]!;
    const clipped = Math.max(c.clipHigh, c.clipLow);
    if (c.luma <= 14 || clipped > 0.35) continue;
    if (c.satProxy > 0.96 && c.clipHigh > 0.08) continue;
    const w = Math.sqrt(1e-9 + c.spatialVar) * (1 - clipped);
    weights.push(Math.max(1e-9, w));
    idxs.push(i);
  }

  const vCount = idxs.length;
  if (vCount === 0) {
    const n = grid.length || 1;
    const sum = grid.reduce(
      (acc, c) => ({
        g: acc.g + c.gMean,
        r: acc.r + c.rMean,
        b: acc.b + c.bMean,
        l: acc.l + c.luma,
      }),
      { g: 0, r: 0, b: 0, l: 0 },
    );
    return {
      g1: sum.g / n,
      rRaw: sum.r / n,
      bRaw: sum.b / n,
      luma: sum.l / n,
      fusionWeightsUsed: 0,
      validCells: 0,
    };
  }

  let sw = 0;
  let gAcc = 0;
  let rAcc = 0;
  let bAcc = 0;
  let lAcc = 0;

  for (let j = 0; j < vCount; j++) {
    const k = idxs[j]!;
    const w = weights[j]!;
    const c = grid[k]!;
    sw += w;
    gAcc += w * c.gMean;
    rAcc += w * c.rMean;
    bAcc += w * c.bMean;
    lAcc += w * c.luma;
  }

  return {
    g1: gAcc / sw,
    rRaw: rAcc / sw,
    bRaw: bAcc / sw,
    luma: lAcc / sw,
    fusionWeightsUsed: sw,
    validCells: vCount,
  };
}
