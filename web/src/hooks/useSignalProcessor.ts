import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import type { RefObject } from "react";
import { CameraSessionController } from "../core/camera/CameraSessionController";
import { createFrameSampler } from "../core/camera/FrameSampler";
import { PPGFrameExtractor } from "../core/ppg/PPGFrameExtractor";
import type { CameraTelemetry, FrameRoiPacket, PpgUiFrame } from "../core/ppg/types";

type ToWorker =
  | {
      kind: "frame";
      pkt: FrameRoiPacket;
      torchApplied: boolean;
      torchPhysicallySupported: boolean;
    }
  | { kind: "reset" };

type FromWorker = { kind: "ui"; frame: PpgUiFrame };

function readTorchStatus(track: MediaStreamTrack | null): {
  physicallySupported: boolean;
  applied: boolean;
} {
  if (!track) return { physicallySupported: false, applied: false };
  const caps = track.getCapabilities() as { torch?: boolean };
  const st = track.getSettings() as { torch?: boolean };
  const physicallySupported = caps.torch === true;
  const applied = st.torch === true;
  return { physicallySupported, applied };
}

export function useSignalProcessor(videoRef: RefObject<HTMLVideoElement | null>) {
  const [ui, setUi] = useState<PpgUiFrame | null>(null);
  const [telemetry, setTelemetry] = useState<CameraTelemetry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  const workerRef = useRef<Worker | null>(null);
  const controllerRef = useRef<CameraSessionController | null>(null);
  const extractorRef = useRef<PPGFrameExtractor | null>(null);
  const samplerStopRef = useRef<(() => void) | null>(null);
  const rafUiRef = useRef<number | null>(null);
  const pendingUiRef = useRef<PpgUiFrame | null>(null);

  const flushUi = useCallback(() => {
    rafUiRef.current = null;
    const p = pendingUiRef.current;
    if (p) setUi(p);
  }, []);

  const stop = useCallback(async () => {
    samplerStopRef.current?.();
    samplerStopRef.current = null;
    await controllerRef.current?.stopSession();
    controllerRef.current = null;
    extractorRef.current?.dispose();
    extractorRef.current = null;
    const w = workerRef.current;
    if (w) {
      w.postMessage({ kind: "reset" } as ToWorker);
      w.terminate();
    }
    workerRef.current = null;
    pendingUiRef.current = null;
    if (rafUiRef.current !== null) {
      cancelAnimationFrame(rafUiRef.current);
      rafUiRef.current = null;
    }
    setRunning(false);
    setUi(null);
  }, []);

  const start = useCallback(async () => {
    await stop();
    setError(null);
    const video = videoRef.current;
    if (!video) {
      setError("Referencia de video no disponible.");
      return;
    }

    const worker = new Worker(
      new URL("../workers/ppg.worker.ts", import.meta.url),
      { type: "module" },
    );
    workerRef.current = worker;

    worker.onmessage = (ev: MessageEvent<FromWorker>) => {
      const d = ev.data;
      if (d.kind !== "ui") return;
      pendingUiRef.current = d.frame;
      if (rafUiRef.current === null)
        rafUiRef.current = requestAnimationFrame(flushUi);
    };

    const controller = new CameraSessionController({
      onTelemetry: (t) => setTelemetry(t),
      onTorchError: (m) => {
        setError((prev) => prev ?? `Linterna: ${m}`);
      },
    });
    controllerRef.current = controller;

    try {
      await controller.startSession(video);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
      worker.terminate();
      workerRef.current = null;
      return;
    }

    const extractor = new PPGFrameExtractor(5, 5, 0.45, 88);
    extractorRef.current = extractor;

    const sampler = createFrameSampler(video, (tPerf) => {
      const vt = controller.getVideoTrack();
      const { physicallySupported, applied } = readTorchStatus(vt);
      const pkt = extractor.extract(video, tPerf);
      if (!pkt) return;
      worker.postMessage({
        kind: "frame",
        pkt,
        torchApplied: applied,
        torchPhysicallySupported: physicallySupported,
      } as ToWorker);
    });

    samplerStopRef.current = () => sampler.stop();
    setRunning(true);
  }, [flushUi, stop, videoRef]);

  useEffect(() => () => void stop(), [stop]);

  return {
    ui,
    telemetry,
    error,
    running,
    start,
    stop,
    clearError: () => setError(null),
  };
}
