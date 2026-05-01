/** Capacidades reales MediaStreamTrack sin suposiciones de hardware */

export type VideoTrackCaps = Readonly<{
  torchSupported: boolean;
  maxWidth?: number;
  maxHeight?: number;
  frameRates?: readonly number[];
  deviceId?: string;
}>;

type ExtendedCaps = MediaTrackCapabilities & {
  torch?: boolean;
};

export function readVideoCaps(track: MediaStreamTrack): VideoTrackCaps {
  const c = track.getCapabilities() as ExtendedCaps;
  return {
    torchSupported: c.torch === true,
    maxWidth: c.width?.max,
    maxHeight: c.height?.max,
    frameRates:
      typeof c.frameRate?.max === "number"
        ? [c.frameRate.min ?? 15, c.frameRate.max ?? 30]
        : undefined,
    deviceId: typeof c.deviceId === "string" ? c.deviceId : undefined,
  };
}

/** Heurística sólo por etiqueta: evitar ultrawide/macro si hay alternativa mejor */

export function labelLooksProblematic(label: string): boolean {
  const l = label.toLowerCase();
  return (
    l.includes("ultra") ||
    l.includes("wide") ||
    l.includes("macro") ||
    l.includes("0.6") ||
    l.includes("0.5x")
  );
}

export function labelLooksLikeBack(label: string): boolean {
  const l = label.toLowerCase();
  if (labelLooksProblematic(label)) return false;
  return (
    l.includes("back") ||
    l.includes("rear") ||
    l.includes("environment") ||
    l.includes("trás") ||
    l.includes("posterior") ||
    l.includes("principal")
  );
}
