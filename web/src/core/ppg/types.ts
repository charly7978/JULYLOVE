/** Estado de contacto dedo / luz — sin valores fisiológicos inventados */

export enum FingerPresenceState {
  NO_FINGER = "NO_FINGER",
  TORCH_UNAVAILABLE = "TORCH_UNAVAILABLE",
  UNDEREXPOSED = "UNDEREXPOSED",
  OVEREXPOSED = "OVEREXPOSED",
  MOTION_TOO_HIGH = "MOTION_TOO_HIGH",
  LOW_PERFUSION = "LOW_PERFUSION",
  FINGER_DETECTED_UNSTABLE = "FINGER_DETECTED_UNSTABLE",
  FINGER_DETECTED_STABLE = "FINGER_DETECTED_STABLE",
  VALID_PPG_SIGNAL = "VALID_PPG_SIGNAL",
}

/** Calidad textual derivada solo de métricas reales */

export enum SignalQualityTier {
  INVALID = "INVALID",
  POOR = "POOR",
  FAIR = "FAIR",
  GOOD = "GOOD",
  EXCELLENT = "EXCELLENT",
}

export type SubRoiMetric = Readonly<{
  rMean: number;
  gMean: number;
  bMean: number;
  luma: number;
  /** (max(rgb)-min)/255 aprox saturación canal */
  satProxy: number;
  /** proporción pixeles cercanos a 255 o 0 en sub-ROI */
  clipHigh: number;
  clipLow: number;
  /** Varianza espacial dentro de la celda */
  spatialVar: number;
}>;

export type FrameRoiPacket = Readonly<{
  tPerf: number;
  /** Ancho/alto naturales del video en el momento de captura */
  videoWidth: number;
  videoHeight: number;
  grid: readonly SubRoiMetric[];
  cols: number;
  rows: number;
}>;

/** Resultado DSP publicado al hilo principal (sin inventar BPM) */

export type PpgUiFrame = Readonly<{
  fingerState: FingerPresenceState;
  sqiScore: number;
  tier: SignalQualityTier;
  /** BPM sólo cuando reglas cumplen; null = no mostrar número */
  bpm: number | null;
  bpmStable: boolean;
  lastValidBpm: number | null;
  /** ms desde performance.timeOrigin hasta caducidad de “último válido” */
  lastValidBpmExpiresAt: number | null;
  waveform: Float32Array;
  waveformMin: number;
  waveformMax: number;
  /** Diagnóstico técnico (no médico): picos FFT vs tiempo */
  debug: Readonly<{
    fsHz: number;
    fusionWeightsUsed: number;
    validSubCells: number;
    acDcRatio: number;
    spectralBpm: number | null;
    peaksBpm: number | null;
    confidence: number;
    motionScore: number;
    contactScore: number;
    perfusionIndex: number;
  }>;
}>;

export type TorchStatus = Readonly<{
  requested: boolean;
  supported: boolean;
  applied: boolean;
  lastError: string | null;
}>;

export type CameraTelemetry = Readonly<{
  deviceId: string;
  facingMode: string | null;
  width: number;
  height: number;
  frameRate: number | null;
  torch: TorchStatus;
}>;
