import {
  labelLooksLikeBack,
  labelLooksProblematic,
  readVideoCaps,
} from "./CameraCapabilities";
import type { CameraTelemetry, TorchStatus } from "../ppg/types";

export type CameraSessionCallbacks = Readonly<{
  onTelemetry: (t: CameraTelemetry) => void;
  onTorchError?: (msg: string) => void;
}>;

export class CameraSessionController {
  private stream: MediaStream | null = null;
  private videoTrack: MediaStreamTrack | null = null;
  private lastTorchError: string | null = null;

  constructor(private readonly callbacks: CameraSessionCallbacks) {}

  async startSession(videoEl: HTMLVideoElement): Promise<void> {
    this.lastTorchError = null;
    await this.stopSession();
    const stream = await this.acquireBackCameraStream();
    this.stream = stream;
    const vt = stream.getVideoTracks()[0];
    if (!vt) throw new Error("No hay pista de video.");
    this.videoTrack = vt;
    videoEl.srcObject = stream;
    videoEl.muted = true;
    videoEl.setAttribute("playsinline", "true");
    videoEl.setAttribute("webkit-playsinline", "true");
    videoEl.autoplay = true;
    await videoEl.play().catch(() => undefined);
    await CameraSessionController.applyAdaptiveResolution(vt);
    await this.tryEnableTorch(vt);
    this.emitTelemetry(vt);
  }

  async reapplyTorch(): Promise<void> {
    const vt = this.videoTrack;
    if (!vt) return;
    await this.tryEnableTorch(vt);
    this.emitTelemetry(vt);
  }

  getStream(): MediaStream | null {
    return this.stream;
  }

  getVideoTrack(): MediaStreamTrack | null {
    return this.videoTrack;
  }

  async stopSession(): Promise<void> {
    for (const t of this.stream?.getTracks() ?? []) {
      t.stop();
    }
    this.stream = null;
    this.videoTrack = null;
  }

  private async acquireBackCameraStream(): Promise<MediaStream> {
    const strict: MediaStreamConstraints = {
      video: {
        facingMode: { exact: "environment" },
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: 30 },
      },
      audio: false,
    };
    try {
      return await navigator.mediaDevices.getUserMedia(strict);
    } catch {
      const loose: MediaStreamConstraints = {
        video: {
          facingMode: "environment",
          width: { ideal: 1280 },
          height: { ideal: 720 },
          frameRate: { ideal: 30 },
        },
        audio: false,
      };
      try {
        return await navigator.mediaDevices.getUserMedia(loose);
      } catch {
        return await this.enumerateFallbackStream();
      }
    }
  }

  private async enumerateFallbackStream(): Promise<MediaStream> {
    const devices = await navigator.mediaDevices.enumerateDevices();
    const videos = devices.filter((d) => d.kind === "videoinput");
    const ranked = [...videos].sort((a, b) => {
      const aOk = labelLooksLikeBack(a.label ?? "");
      const bOk = labelLooksLikeBack(b.label ?? "");
      if (aOk && !bOk) return -1;
      if (!aOk && bOk) return 1;
      const aBad = labelLooksProblematic(a.label ?? "");
      const bBad = labelLooksProblematic(b.label ?? "");
      if (aBad === bBad) return 0;
      return aBad ? 1 : -1;
    });
    const deviceId = ranked[0]?.deviceId;
    if (!deviceId) {
      throw new Error(
        "No se pudo obtener cámara trasera. Permisos o dispositivos no disponibles.",
      );
    }
    return await navigator.mediaDevices.getUserMedia({
      video: {
        deviceId: { ideal: deviceId },
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: 30 },
      },
      audio: false,
    });
  }

  private static async applyAdaptiveResolution(
    track: MediaStreamTrack,
  ): Promise<void> {
    const caps = track.getCapabilities();
    const w = caps.width;
    const h = caps.height;
    const maxW = typeof w?.max === "number" ? w.max : 1920;
    const maxH = typeof h?.max === "number" ? h.max : 1080;
    const targetW = Math.min(1280, maxW);
    const targetH = Math.min(720, maxH);
    try {
      await track.applyConstraints({
        width: targetW,
        height: targetH,
        frameRate: { ideal: 30 },
      });
    } catch {
      /** El SO puede ignorar constraints; sin simular éxito */
    }
  }

  private async tryEnableTorch(track: MediaStreamTrack): Promise<void> {
    const caps = readVideoCaps(track);
    if (!caps.torchSupported) return;
    try {
      await track.applyConstraints({
        advanced: [{ torch: true } as unknown as Record<string, boolean>],
      });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.lastTorchError = msg;
      this.callbacks.onTorchError?.(msg);
    }
  }

  private readTorchApplied(track: MediaStreamTrack): boolean {
    try {
      const st = track.getSettings() as { torch?: boolean };
      return st.torch === true;
    } catch {
      return false;
    }
  }

  private emitTelemetry(track: MediaStreamTrack): void {
    const s = track.getSettings();
    const caps = readVideoCaps(track);
    const torch: TorchStatus = {
      requested: true,
      supported: caps.torchSupported,
      applied: caps.torchSupported && this.readTorchApplied(track),
      lastError: this.lastTorchError,
    };
    const fr =
      typeof s.frameRate === "number" ? s.frameRate : null;

    this.callbacks.onTelemetry({
      deviceId: typeof s.deviceId === "string" ? s.deviceId : "(unknown)",
      facingMode: typeof s.facingMode === "string" ? s.facingMode : null,
      width: s.width ?? 0,
      height: s.height ?? 0,
      frameRate: fr,
      torch,
    });
  }
}
