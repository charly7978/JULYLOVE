import {
  FingerPresenceState,
  type FrameRoiPacket,
  type PpgUiFrame,
} from "../core/ppg/types";
import { detectBeats } from "../core/ppg/BeatDetector";
import {
  classifyFingerPresence,
  initFingerAccumulator,
  updateFingerAccumulatorAndMeasures,
  type FingerContext,
} from "../core/ppg/FingerDetector";
import { fusedFromGrid } from "../core/ppg/PPGFrameExtractor";
import { PPGSignalPipeline } from "../core/ppg/PPGSignalPipeline";
import { classifyTier, computeSqiScore } from "../core/ppg/SignalQualityIndex";
import { estimateSpectralHr } from "../core/dsp/simpleFft";

type FromMain =
  | {
      kind: "frame";
      pkt: FrameRoiPacket;
      torchApplied: boolean;
      torchPhysicallySupported: boolean;
    }
  | { kind: "reset" };

let pipeline = new PPGSignalPipeline(900, 420);
let fingerAcc = initFingerAccumulator();
let lastSpectralAt = 0;
let lastSpectral = estimateSpectralHr(new Float64Array(0), 30);
let lastBpmOk: number | null = null;
let lastBpmExpirePerf = 0;

function meanClipFromGrid(pkt: FrameRoiPacket): number {
  const n = pkt.grid.length || 1;
  let s = 0;
  for (const c of pkt.grid) s += Math.max(c.clipHigh, c.clipLow);
  return s / n;
}

function buildUi(
  fingerOut: FingerPresenceState,
  sqiScore: number,
  tier: ReturnType<typeof classifyTier>,
  bpmField: number | null,
  bpmStable: boolean,
  lastValid: number | null,
  lastExp: number | null,
  wf: Float32Array,
  wmin: number,
  wmax: number,
  dbg: PpgUiFrame["debug"],
): PpgUiFrame {
  return {
    fingerState: fingerOut,
    sqiScore,
    tier,
    bpm: bpmField,
    bpmStable,
    lastValidBpm: lastValid,
    lastValidBpmExpiresAt: lastExp,
    waveform: wf,
    waveformMin: wmin,
    waveformMax: wmax,
    debug: dbg,
  };
}

function computeWaveFloat(z: Float64Array): {
  floats: Float32Array;
  wmin: number;
  wmax: number;
} {
  const floats = Float32Array.from(z);
  let wmin = Number.POSITIVE_INFINITY;
  let wmax = Number.NEGATIVE_INFINITY;
  for (let i = 0; i < floats.length; i++) {
    const v = floats[i]!;
    if (v < wmin) wmin = v;
    if (v > wmax) wmax = v;
  }
  if (!(wmax > wmin && Number.isFinite(wmin))) {
    wmin = -1;
    wmax = 1;
  }
  return { floats, wmin, wmax };
}

self.onmessage = (ev: MessageEvent<FromMain>) => {
  const msg = ev.data;
  if (msg.kind === "reset") {
    pipeline = new PPGSignalPipeline(900, 420);
    fingerAcc = initFingerAccumulator();
    lastBpmOk = null;
    lastBpmExpirePerf = 0;
    lastSpectralAt = 0;
    lastSpectral = estimateSpectralHr(new Float64Array(0), 30);
    return;
  }
  if (msg.kind !== "frame") return;

  const pkt = msg.pkt;

  const ctx: FingerContext = {
    torchPhysicallySupported: msg.torchPhysicallySupported,
    torchApplied: msg.torchApplied,
  };

  const fused = fusedFromGrid(pkt.grid);
  const { acc, m } = updateFingerAccumulatorAndMeasures(fingerAcc, pkt, {
    g1: fused.g1,
    luma: fused.luma,
    validCells: fused.validCells,
  });
  fingerAcc = acc;

  let fingerUi = classifyFingerPresence(ctx, m);

  const sample = pipeline.push(pkt.tPerf, fused.g1);
  const acDc = pipeline.peekAcDc(sample);

  if (
    Number.isFinite(acDc) &&
    acDc < 0.00106 &&
    fused.validCells >= 6 &&
    fingerUi !== FingerPresenceState.TORCH_UNAVAILABLE &&
    fingerUi !== FingerPresenceState.UNDEREXPOSED &&
    fingerUi !== FingerPresenceState.OVEREXPOSED &&
    fingerUi !== FingerPresenceState.NO_FINGER &&
    fingerUi !== FingerPresenceState.MOTION_TOO_HIGH
  ) {
    fingerUi = FingerPresenceState.LOW_PERFUSION;
  }

  const tail = pipeline.getWaveformTail(640);
  const z = tail.g3z;
  const beats = detectBeats(z, sample.fsHz);
  const meanClipGrid = meanClipFromGrid(pkt);
  const validFracAll = fused.validCells / Math.max(pkt.grid.length, 1);

  if (pkt.tPerf - lastSpectralAt > 280 && z.length >= 220) {
    lastSpectral = estimateSpectralHr(new Float64Array(z), sample.fsHz);
    lastSpectralAt = pkt.tPerf;
  }

  let spectraAgree01 = 0.45;
  if (
    beats.bpmFromPeaksMedian !== null &&
    lastSpectral.bpm !== null &&
    Number.isFinite(beats.bpmFromPeaksMedian)
  ) {
    const meanB =
      (beats.bpmFromPeaksMedian + lastSpectral.bpm) / 2;
    spectraAgree01 =
      Math.min(
        1,
        Math.max(
          0,
          1 -
            Math.abs(beats.bpmFromPeaksMedian - lastSpectral.bpm) /
              Math.max(meanB * 0.09, 4),
        ),
      );
  }

  const combinedConf =
    Math.min(
      1,
      beats.confidencePeaksOnly * (0.55 + 0.45 * spectraAgree01),
    );

  const sqiScore = computeSqiScore({
    acDc,
    meanClip: meanClipGrid,
    motion01: m.motionScore,
    contact01: m.contactScore,
    validCellFrac: validFracAll,
    rgCorr: m.rgCorr,
    beatConf: combinedConf,
    fftSnrBand: lastSpectral.snrBand,
    spectraPeaksAgreement01: spectraAgree01,
  });

  let fingerOut = fingerUi;
  if (
    fingerUi === FingerPresenceState.FINGER_DETECTED_STABLE &&
    sqiScore >= 55 &&
    beats.peakIdx.length >= 6 &&
    lastSpectral.snrBand >= 4.8
  ) {
    const okPeak =
      (lastSpectral.bpm !== null &&
        beats.bpmFromPeaksMedian !== null &&
        spectraAgree01 >= 0.48) ||
      sqiScore >= 62;
    if (okPeak) fingerOut = FingerPresenceState.VALID_PPG_SIGNAL;
  }

  const tier = classifyTier(sqiScore);

  let bpmField: number | null = null;
  if (
    fingerOut === FingerPresenceState.VALID_PPG_SIGNAL &&
    sqiScore >= 60 &&
    beats.peakIdx.length >= 5 &&
    beats.bpmFromPeaksMedian !== null &&
    Number.isFinite(beats.bpmFromPeaksMedian)
  ) {
    bpmField = Math.round(beats.bpmFromPeaksMedian);
  }

  const bpmStable =
    bpmField !== null &&
    sqiScore >= 74 &&
    combinedConf > 0.6 &&
    spectraAgree01 > 0.58;

  if (bpmField !== null) {
    lastBpmOk = bpmField;
    lastBpmExpirePerf = pkt.tPerf + 3400;
  }

  let lastValidShow: number | null = null;
  let lastValidExpShow: number | null = null;
  if (
    bpmField === null &&
    lastBpmOk !== null &&
    pkt.tPerf <= lastBpmExpirePerf
  ) {
    lastValidShow = lastBpmOk;
    lastValidExpShow = lastBpmExpirePerf;
  }

  const { floats, wmax, wmin } = computeWaveFloat(z);

  const dbg = {
    fsHz: sample.fsHz,
    fusionWeightsUsed: fused.fusionWeightsUsed,
    validSubCells: fused.validCells,
    acDcRatio: acDc,
    spectralBpm: lastSpectral.bpm,
    peaksBpm: beats.bpmFromPeaksMedian,
    confidence: combinedConf,
    motionScore: m.motionScore,
    contactScore: m.contactScore,
    perfusionIndex: acDc,
  } satisfies PpgUiFrame["debug"];

  const frame = buildUi(
    fingerOut,
    sqiScore,
    tier,
    bpmField,
    bpmStable,
    lastValidShow,
    lastValidExpShow,
    floats,
    wmin,
    wmax,
    dbg,
  );

  /** Transfer sólo cuando hay buffer tangible (evita fallos silenciosos del puerto de mensajes) */

  const buf = frame.waveform.buffer.byteLength ? frame.waveform.buffer : null;
  if (buf)
    self.postMessage({ kind: "ui", frame }, [buf]);
  else self.postMessage({ kind: "ui", frame });
};
