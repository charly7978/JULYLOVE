/**
 * requestVideoFrameCallback con fallback RAF.
 * Sin setInterval como reloj principal.
 */

export type FrameSamplerStats = Readonly<{
  fps: number;
  droppedFramesApprox: number;
  lastDtMs: number;
}>;

export type FrameSamplerHandle = Readonly<{ stop: () => void; getStats: () => FrameSamplerStats }>;

const DROP_MS = 55;

export function createFrameSampler(
  video: HTMLVideoElement,
  onFrame: (tHighResMs: DOMHighResTimeStamp) => void,
): FrameSamplerHandle {
  let running = true;
  let rafId = 0;
  let vfcHandle = 0;
  let prev = performance.now();
  let lastDt = 0;
  /** Exponential moving average FPS */
  let fpsEma = 0;
  let drops = 0;
  let frames = 0;

  const tick = (now: DOMHighResTimeStamp) => {
    if (!running) return;
    const dt = now - prev;
    prev = now;
    lastDt = dt;
    if (frames > 0 && dt > DROP_MS) drops += 1;
    frames += 1;
    const instantFps = dt > 1e-6 ? 1000 / dt : 0;
    fpsEma = fpsEma === 0 ? instantFps : fpsEma * 0.92 + instantFps * 0.08;
    /** No procesar si el video no está listo — evita señales falsas */
    if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
        video.videoWidth > 0 &&
        video.videoHeight > 0) {
      onFrame(now);
    }
    loop();
  };

  const vid = video as unknown as VideoFrameCbVideo & HTMLVideoElement;
  type VideoFrameCbVideo = {
    readonly requestVideoFrameCallback?: (
      cb: (_now: number, meta: VideoFrameMeta) => void,
    ) => number;
    cancelVideoFrameCallback?: (h: number) => void;
  };
  /** meta útil sólo como referencia temporal adicional opcional */

  interface VideoFrameMeta {
    readonly expectedDisplayTime?: number;
    readonly presentationTime?: number;
  }

  function loop(): void {
    if (!running) return;
    const rVFC = vid.requestVideoFrameCallback;
    if (typeof rVFC === "function") {
      vfcHandle = rVFC.call(vid, (now) => tick(now as DOMHighResTimeStamp));
    } else {
      rafId = requestAnimationFrame((t) => tick(t));
    }
  }

  loop();

  return {
    stop: () => {
      running = false;
      cancelAnimationFrame(rafId);
      const cancelVfc = vid.cancelVideoFrameCallback;
      if (typeof cancelVfc === "function") cancelVfc.call(vid, vfcHandle);
    },
    getStats: (): FrameSamplerStats => ({
      fps: fpsEma,
      droppedFramesApprox: drops,
      lastDtMs: lastDt,
    }),
  };
}
