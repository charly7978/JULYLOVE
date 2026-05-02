import { useEffect, useRef } from 'react'
import type { BeatEvent, PpgSample } from '../ppg/types'

/**
 * Canvas de alto detalle para la onda PPG.
 *
 * - Lee los buffers por REF: React no re-renderiza el canvas nunca.
 * - Respeta devicePixelRatio para trazo nítido en pantallas retina.
 * - Grilla médica: 1 división mayor por segundo + sub-grilla 5× más fina.
 * - Onda con glow exterior + trazo principal + línea interior luminosa.
 * - Marcadores de latido con altura proporcional a su amplitud.
 */
export function PpgWaveform({
  samplesRef,
  beatsRef,
  windowSeconds = 8
}: {
  samplesRef: React.MutableRefObject<PpgSample[]>
  beatsRef: React.MutableRefObject<BeatEvent[]>
  windowSeconds?: number
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let raf = 0
    const render = () => {
      draw(canvas, samplesRef.current, beatsRef.current, windowSeconds)
      raf = requestAnimationFrame(render)
    }
    raf = requestAnimationFrame(render)
    return () => cancelAnimationFrame(raf)
  }, [samplesRef, beatsRef, windowSeconds])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const resize = () => {
      const dpr = Math.min(3, window.devicePixelRatio || 1)
      const rect = canvas.getBoundingClientRect()
      canvas.width = Math.max(1, Math.round(rect.width * dpr))
      canvas.height = Math.max(1, Math.round(rect.height * dpr))
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(canvas)
    window.addEventListener('orientationchange', resize)
    return () => {
      ro.disconnect()
      window.removeEventListener('orientationchange', resize)
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      style={{
        width: '100%',
        height: '100%',
        display: 'block',
        background: '#020608',
        borderRadius: 4
      }}
    />
  )
}

function draw(canvas: HTMLCanvasElement, samples: PpgSample[], beats: BeatEvent[], windowSeconds: number) {
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const w = canvas.width
  const h = canvas.height
  ctx.clearRect(0, 0, w, h)
  drawGrid(ctx, w, h, windowSeconds)

  if (samples.length < 2) return

  const last = samples[samples.length - 1]
  const tEnd = last.timestampMs
  const tStart = tEnd - windowSeconds * 1000
  let mn = Number.POSITIVE_INFINITY
  let mx = Number.NEGATIVE_INFINITY
  for (const s of samples) {
    if (s.timestampMs < tStart) continue
    if (s.display < mn) mn = s.display
    if (s.display > mx) mx = s.display
  }
  if (!Number.isFinite(mn) || !Number.isFinite(mx) || mx - mn < 1e-6) {
    mn -= 1
    mx += 1
  }
  const pad = (mx - mn) * 0.2
  mn -= pad
  mx += pad
  const span = Math.max(1, tEnd - tStart)

  // Marcadores de latido: línea vertical + anillo en el pico.
  for (const b of beats) {
    if (b.timestampMs < tStart) continue
    const x = ((b.timestampMs - tStart) / span) * w
    const isNormal = b.type === 'NORMAL'
    const color = isNormal
      ? 'rgba(45, 255, 170, 0.8)'
      : b.type === 'SUSPECT_PREMATURE'
      ? 'rgba(255, 60, 70, 0.9)'
      : 'rgba(255, 170, 40, 0.9)'
    ctx.strokeStyle = color
    ctx.lineWidth = isNormal ? 1.4 : 2.6
    ctx.setLineDash(isNormal ? [] : [6, 4])
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, h)
    ctx.stroke()
    ctx.setLineDash([])
    // Anillo en la parte alta (pico).
    ctx.beginPath()
    ctx.arc(x, h * 0.15, 5, 0, Math.PI * 2)
    ctx.fillStyle = color
    ctx.fill()
  }

  // Glow externo
  ctx.save()
  ctx.strokeStyle = 'rgba(45, 255, 170, 0.22)'
  ctx.lineWidth = 14
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'
  strokePath(ctx, samples, tStart, span, mn, mx, w, h)

  // Trazo principal
  ctx.strokeStyle = 'rgba(75, 255, 195, 1)'
  ctx.lineWidth = 3.2
  strokePath(ctx, samples, tStart, span, mn, mx, w, h)

  // Línea interior luminosa
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.9)'
  ctx.lineWidth = 1.2
  strokePath(ctx, samples, tStart, span, mn, mx, w, h)
  ctx.restore()

  // Cabezote del barrido (punto brillante en el extremo)
  const lastX = ((last.timestampMs - tStart) / span) * w
  const lastY = h - ((last.display - mn) / (mx - mn)) * h
  ctx.fillStyle = 'rgba(200, 255, 220, 1)'
  ctx.beginPath()
  ctx.arc(lastX, lastY, 4, 0, Math.PI * 2)
  ctx.fill()
  ctx.fillStyle = 'rgba(34, 255, 170, 0.35)'
  ctx.beginPath()
  ctx.arc(lastX, lastY, 10, 0, Math.PI * 2)
  ctx.fill()
}

function strokePath(
  ctx: CanvasRenderingContext2D,
  samples: PpgSample[],
  tStart: number,
  span: number,
  mn: number,
  mx: number,
  w: number,
  h: number
) {
  ctx.beginPath()
  let started = false
  for (const s of samples) {
    if (s.timestampMs < tStart) continue
    const x = ((s.timestampMs - tStart) / span) * w
    const y = h - ((s.display - mn) / (mx - mn)) * h
    if (!started) {
      ctx.moveTo(x, y)
      started = true
    } else {
      ctx.lineTo(x, y)
    }
  }
  ctx.stroke()
}

function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number, windowSeconds: number) {
  const majorX = windowSeconds // 1 columna mayor por segundo
  const majorY = 6
  const minorPerMajor = 5

  ctx.save()
  // Sub-grilla fina
  ctx.strokeStyle = 'rgba(34, 255, 170, 0.07)'
  ctx.lineWidth = 1
  for (let i = 0; i < majorX * minorPerMajor; i++) {
    const x = (w * i) / (majorX * minorPerMajor)
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, h)
    ctx.stroke()
  }
  for (let j = 0; j < majorY * minorPerMajor; j++) {
    const y = (h * j) / (majorY * minorPerMajor)
    ctx.beginPath()
    ctx.moveTo(0, y)
    ctx.lineTo(w, y)
    ctx.stroke()
  }
  // Grilla mayor
  ctx.strokeStyle = 'rgba(34, 255, 170, 0.22)'
  ctx.lineWidth = 1.4
  for (let i = 0; i <= majorX; i++) {
    const x = (w * i) / majorX
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, h)
    ctx.stroke()
  }
  for (let j = 0; j <= majorY; j++) {
    const y = (h * j) / majorY
    ctx.beginPath()
    ctx.moveTo(0, y)
    ctx.lineTo(w, y)
    ctx.stroke()
  }

  // Ticks de segundos al pie
  ctx.fillStyle = 'rgba(34, 255, 170, 0.5)'
  ctx.font = `${Math.max(10, Math.round(h * 0.025))}px ui-monospace, monospace`
  for (let i = 1; i < majorX; i++) {
    const x = (w * i) / majorX
    ctx.fillText(`-${majorX - i}s`, x + 3, h - 4)
  }
  ctx.restore()
}
