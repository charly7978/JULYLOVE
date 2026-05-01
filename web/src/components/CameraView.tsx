import { forwardRef } from "react";

/**
 * Video real de cámara; permanece en el DOM pero no tapa el monitor (opacidad casi nula).
 */

export const CameraView = forwardRef<HTMLVideoElement>(function CameraView(
  _props,
  ref,
) {
  return (
    <video
      ref={ref}
      muted
      playsInline
      autoPlay
      style={{
        position: "fixed",
        width: 2,
        height: 2,
        opacity: 0.02,
        pointerEvents: "none",
        top: 0,
        left: 0,
        zIndex: 0,
        objectFit: "cover",
      }}
    />
  );
});
