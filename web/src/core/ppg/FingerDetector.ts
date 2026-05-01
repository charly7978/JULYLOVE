import {
  FingerPresenceState,
  type FrameRoiPacket,
  type SubRoiMetric,
} from "./types";

export function rgPearsonGrid(grid: readonly SubRoiMetric[]): number {
  const n = grid.length;
  if (n < 5) return 0;
  let sumR = 0;
  let sumG = 0;
  for (let i = 0; i < n; i++) {
    sumR += grid[i]!.rMean;
    sumG += grid[i]!.gMean;
  }
  const meanR = sumR / n;
  const meanG = sumG / n;
  let num = 0;
  let dr = 0;
  let dg = 0;
  for (let i = 0; i < n; i++) {
    const xr = grid[i]!.rMean - meanR;
    const xg = grid[i]!.gMean - meanG;
    num += xr * xg;
    dr += xr * xr;
    dg += xg * xg;
  }
  const den = Math.sqrt(Math.max(1e-12, dr * dg));
  return Math.max(-1, Math.min(1, num / den));
}

export type FingerContext = Readonly<{
  torchPhysicallySupported: boolean;
  torchApplied: boolean;
}>;

export type FingerAccumulator = Readonly<{
  /** EWMA sobre |ΔG1| fusionado */
  ewMotionAbs: number;
  /** EWMA de fracción de celdas válidas */
  ewValidFrac: number;
  /** EWMA correlación RG */
  ewRgCorr: number;
  prevGreen: number | null;
}>;

export function initFingerAccumulator(): FingerAccumulator {
  return {
    ewMotionAbs: 0,
    ewValidFrac: 0,
    ewRgCorr: 0,
    prevGreen: null,
  };
}

/** Actualiza acumulador y produce medidas lisas deterministas */

export type FingerMeasures = Readonly<{
  motionScore: number;
  contactScore: number;
  meanClipFrac: number;
  luma: number;
  validFracInstant: number;
  rgCorr: number;
  g1: number;
}>;

export function updateFingerAccumulatorAndMeasures(
  acc: FingerAccumulator,
  pkt: FrameRoiPacket,
  fused: Readonly<{ g1: number; luma: number; validCells: number }>,
  alphaSlow = 0.08,
): { acc: FingerAccumulator; m: FingerMeasures } {
  const total = Math.max(pkt.grid.length, 1);
  const validFrac =
    fused.validCells > 0 ? fused.validCells / total : 0;

  let clipSum = 0;
  for (const c of pkt.grid) {
    clipSum += Math.max(c.clipHigh, c.clipLow);
  }
  const meanClipFrac = clipSum / total;

  const rgCorr = rgPearsonGrid(pkt.grid);

  const prevG = acc.prevGreen;
  const dg =
    prevG === null ? 0 : Math.abs(fused.g1 - prevG) / Math.max(fused.g1, prevG, 60);

  let ewMot = acc.ewMotionAbs;
  ewMot = ewMot === 0 ? dg : ewMot * (1 - alphaSlow) + dg * alphaSlow;

  let ewVf = acc.ewValidFrac;
  ewVf =
    ewVf === 0 ? validFrac : ewVf * (1 - alphaSlow) + validFrac * alphaSlow;

  let ewRg = acc.ewRgCorr;
  ewRg = ewRg === 0 ? rgCorr : ewRg * (1 - alphaSlow) + rgCorr * alphaSlow;

  const nextAcc: FingerAccumulator = {
    ewMotionAbs: ewMot,
    ewValidFrac: ewVf,
    ewRgCorr: ewRg,
    prevGreen: fused.g1,
  };

  /** contactScore: combinación estable de correlación RG y uso de rejilla válida */
  const contactScore = Math.min(
    1,
    ewVf * 0.55 + Math.max(0, ewRg) * 0.45 + (ewMot < 0.012 ? 0.08 : -0.12),
  );

  const m: FingerMeasures = {
    motionScore: Math.min(1, ewMot * 8),
    contactScore: Math.max(0, Math.min(1, contactScore)),
    meanClipFrac,
    luma: fused.luma,
    validFracInstant: validFrac,
    rgCorr,
    g1: fused.g1,
  };

  return { acc: nextAcc, m };
}

export function classifyFingerPresence(
  ctx: FingerContext,
  m: FingerMeasures,
): FingerPresenceState {
  if (!ctx.torchPhysicallySupported) return FingerPresenceState.TORCH_UNAVAILABLE;

  /** Sin torch aplicado: muchos Android igual dan señal; marcamos infraexpuesta si muy oscura */
  if (
    ctx.torchPhysicallySupported &&
    !ctx.torchApplied &&
    m.luma < 42 &&
    m.meanClipFrac < 0.12
  ) {
    return FingerPresenceState.UNDEREXPOSED;
  }

  if (m.luma < 22 && m.validFracInstant < 0.2) return FingerPresenceState.UNDEREXPOSED;

  if (m.meanClipFrac > 0.48 || (m.meanClipFrac > 0.22 && m.luma > 232)) {
    return FingerPresenceState.OVEREXPOSED;
  }

  if (
    m.validFracInstant < 0.16 &&
    m.rgCorr < 0.32 &&
    m.motionScore > 0.12
  ) {
    return FingerPresenceState.NO_FINGER;
  }

  if (m.motionScore > 0.82) return FingerPresenceState.MOTION_TOO_HIGH;

  /** Contacto medio-bajo pero con cobertura: inestabilidad práctica */

  if (m.motionScore > 0.35 || (m.motionScore > 0.22 && contactLow(m))) {
    return FingerPresenceState.FINGER_DETECTED_UNSTABLE;
  }

  if (m.motionScore <= 0.28 && decentContact(m)) {
    return FingerPresenceState.FINGER_DETECTED_STABLE;
  }

  return FingerPresenceState.FINGER_DETECTED_UNSTABLE;
}

function decentContact(m: FingerMeasures): boolean {
  return m.validFracInstant > 0.22 && (m.contactScore > 0.42 || m.rgCorr > 0.32);
}

function contactLow(m: FingerMeasures): boolean {
  return m.contactScore < 0.42 && m.validFracInstant < 0.35;
}
