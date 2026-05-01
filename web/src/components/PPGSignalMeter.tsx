import { useEffect, useRef } from "react";
import type { PpgUiFrame } from "../core/ppg/types";

type Props = Readonly<{ ui: PpgUiFrame | null }>;

/** Monitor fullscreen: waveform G3 desde worker sin Math.random */

export function PPGSignalMeter({ ui }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const uiRef = useRef(ui);
  uiRef.current = ui;

  useEffect(() => {
    const c = canvasRef.current;
    if (!c) return;
    const ctx = c.getContext("2d");
    if (!ctx) return;

    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const resize = () => {
      c.width = Math.floor(window.innerWidth * dpr);
      c.height = Math.floor(window.innerHeight * dpr);
      c.style.width = `${window.innerWidth}px`;
      c.style.height = `${window.innerHeight}px`;
    };
    resize();
    window.addEventListener("resize", resize);

    let raf = 0;
    const loop = () => {
      raf = requestAnimationFrame(loop);
      const u = uiRef.current;
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = "#000";
      ctx.fillRect(0, 0, c.width, c.height);
      const wf = u?.waveform;
      if (wf && wf.length > 2) {
        const min = u!.waveformMin;
        const max = u!.waveformMax;
        const span = Math.max(max - min, 1e-6);
        ctx.strokeStyle = "#2ee6a0";
        ctx.lineWidth = dpr * 2;
        ctx.beginPath();
        for (let i = 0; i < wf.length; i++) {
          const x = (i / Math.max(wf.length - 1, 1)) * c.width;
          const yNorm = (wf[i]! - min) / span;
          const y = (1 - yNorm) * (c.height - 40 * dpr) + 20 * dpr;
          if (i === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
      } else {
        ctx.fillStyle = "#444";
        ctx.font = `${14 * dpr}px system-ui`;
        ctx.fillText("Sesión iniciada · cubre la cámara trasera…", 16 * dpr, 40 * dpr);
      }
      ctx.restore();
    };
    raf = requestAnimationFrame(loop);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", resize);
    };
  }, []);

  const dpr = typeof window !== "undefined" ? Math.min(2, window.devicePixelRatio || 1) : 1;
  const u = ui;

  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 1,
        background: "#000",
      }}
    >
      <canvas
        ref={canvasRef}
        style={{ display: "block", width: "100%", height: "100%" }}
      />
      <div
        style={{
          position: "absolute",
          left: 8,
          top: 8,
          right: 100,
          fontSize: 12,
          color: "#ccc",
          pointerEvents: "none",
          textShadow: "0 0 4px #000",
          lineHeight: 1.35,
        }}
      >
        {u ? (
          <>
            <div>Estado: {u.fingerState}</div>
            <div>
              Calidad: {u.sqiScore}/100 ({u.tier}){u.bpmStable ? " · estable" : ""}
            </div>
            <div>BPM visible: {u.bpm !== null ? u.bpm : "—"}</div>
            {u.lastValidBpm !== null && u.bpm === null && Number.isFinite(u.lastValidBpmExpiresAt!) && (
              <div style={{ color: "#ffcc66", fontSize: 11 }}>
                Último válido ~{u.lastValidBpm} (
                {Math.max(0, Math.round(u.lastValidBpmExpiresAt! - performance.now()))}
                ms)
              </div>
            )}
            <div style={{ fontSize: 10, opacity: 0.74, marginTop: 4 }}>
              Fs {u.debug.fsHz.toFixed(1)} Hz · válidas {u.debug.validSubCells} · peaks{" "}
              {u.debug.peaksBpm !== null ? Math.round(u.debug.peaksBpm) : "—"}
              {" · "}FFT{" "}
              {u.debug.spectralBpm !== null ? Math.round(u.debug.spectralBpm) : "—"}
            </div>
          </>
        ) : (
          <div>Pulse “Medir” para abrir la cámara trasera.</div>
        )}
      </div>
      <span style={{ display: "none" }} aria-hidden>{dpr}</span>
    </div>
  );
}
