import { useEffect, useRef } from 'react'
import type { BeatEvent, PpgSample } from '../ppg/types'

export function PpgWaveform({
  samples,
  beats,
  windowSeconds = 10
}: {
  samples: PpgSample[]
  beats: BeatEvent[]
  windowSeconds?: number
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let raf = 0
    const render = () => {
      draw(canvas, samples, beats, windowSeconds)
      raf = requestAnimationFrame(render)
    }
    raf = requestAnimationFrame(render)
    return () => cancelAnimationFrame(raf)
  }, [samples, beats, windowSeconds])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const resize = () => {
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      const rect = canvas.getBoundingClientRect()
      canvas.width = Math.max(1, rect.width * dpr)
      canvas.height = Math.max(1, rect.height * dpr)
    }
    resize()
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])

  return (
    <canvas
      ref={canvasRef}
      style={{
        width: '100%',
        height: '100%',
        display: 'block',
        background: '#05080A',
        borderRadius: 6
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
  drawGrid(ctx, w, h)
  if (samples.length < 2) {
    ctx.strokeStyle = 'rgba(255,255,255,0.25)'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(0, h / 2)
    ctx.lineTo(w, h / 2)
    ctx.stroke()
    return
  }
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
  const pad = (mx - mn) * 0.15
  mn -= pad
  mx += pad
  const span = Math.max(1, tEnd - tStart)

  // Marcadores de latido (fondo).
  for (const b of beats) {
    if (b.timestampMs < tStart) continue
    const x = ((b.timestampMs - tStart) / span) * w
    const isNormal = b.type === 'NORMAL'
    ctx.strokeStyle = isNormal
      ? 'rgba(34, 255, 170, 0.7)'
      : b.type === 'SUSPECT_PREMATURE'
      ? 'rgba(255, 60, 70, 0.85)'
      : 'rgba(255, 170, 40, 0.85)'
    ctx.lineWidth = isNormal ? 1.5 : 2.5
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, h)
    ctx.stroke()
  }

  // Onda.
  ctx.strokeStyle = 'rgba(45,255,170,0.9)'
  ctx.lineWidth = 2.6
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

  // Halo
  ctx.globalAlpha = 0.4
  ctx.lineWidth = 7
  ctx.strokeStyle = 'rgba(34,255,170,0.35)'
  ctx.stroke()
  ctx.globalAlpha = 1
}

function drawGrid(ctx: CanvasRenderingContext2D, w: number, h: number) {
  const gridX = 12
  const gridY = 6
  for (let i = 1; i < gridX; i++) {
    const x = (w * i) / gridX
    ctx.strokeStyle = i % 3 === 0 ? 'rgba(34, 255, 170, 0.28)' : 'rgba(34, 255, 170, 0.12)'
    ctx.lineWidth = i % 3 === 0 ? 1.1 : 0.6
    ctx.beginPath()
    ctx.moveTo(x, 0)
    ctx.lineTo(x, h)
    ctx.stroke()
  }
  for (let j = 1; j < gridY; j++) {
    const y = (h * j) / gridY
    ctx.strokeStyle = j % 2 === 0 ? 'rgba(34, 255, 170, 0.28)' : 'rgba(34, 255, 170, 0.12)'
    ctx.lineWidth = j % 2 === 0 ? 1.1 : 0.6
    ctx.beginPath()
    ctx.moveTo(0, y)
    ctx.lineTo(w, y)
    ctx.stroke()
  }
}
