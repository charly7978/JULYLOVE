import { useRef } from "react";
import { CameraView } from "../components/CameraView";
import { PPGSignalMeter } from "../components/PPGSignalMeter";
import { useSignalProcessor } from "../hooks/useSignalProcessor";

export default function Index() {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const { ui, error, running, start, stop, telemetry, clearError } =
    useSignalProcessor(videoRef);

  return (
    <div style={{ width: "100%", height: "100%", background: "#000" }}>
      <CameraView ref={videoRef} />
      <PPGSignalMeter ui={ui} />

      <div
        style={{
          position: "fixed",
          top: 8,
          right: 8,
          zIndex: 4,
          display: "flex",
          flexDirection: "column",
          gap: 8,
          alignItems: "flex-end",
        }}
      >
        <button
          type="button"
          onClick={() => (running ? void stop() : void start())}
          style={{
            padding: "10px 14px",
            borderRadius: 10,
            border: "none",
            background: running ? "#883333" : "#226644",
            color: "#fff",
          }}
        >
          {running ? "Detener" : "Medir"}
        </button>
        {error && (
          <div style={{ maxWidth: 220, fontSize: 11, color: "#ff9999", textAlign: "right" }}>
            <div>{error}</div>
            <button type="button" onClick={clearError} style={{ marginTop: 4 }}>
              OK
            </button>
          </div>
        )}
      </div>

      {telemetry && (
        <div
          style={{
            position: "fixed",
            bottom: 4,
            left: 8,
            zIndex: 3,
            fontSize: 9,
            color: "#444",
            pointerEvents: "none",
          }}
        >
          {telemetry.width}×{telemetry.height}
          {" · "}torch {telemetry.torch.applied ? "on" : "off"}
          {telemetry.torch.supported ? "" : " (no soporte)"}
        </div>
      )}
    </div>
  );
}
