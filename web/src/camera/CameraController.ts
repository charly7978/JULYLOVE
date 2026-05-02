import type { CameraFrameStats } from '../ppg/types'

export interface StartOptions {
  targetFps: number
  onFrame: (frame: CameraFrameStats) => void
}

export interface CameraCapabilitiesSnapshot {
  deviceId: string
  label: string
  width: number
  height: number
  frameRate: number | null
  torchSupported: boolean
  torchOn: boolean
  exposureMode: string | null
  focusMode: string | null
}

/**
 * Controlador de cámara para la web. Utiliza `getUserMedia` con
 * `facingMode: environment` y activa el torch vía
 * `MediaStreamTrack.applyConstraints({ advanced: [{ torch: true }] })`
 * cuando el dispositivo lo expone (Chrome Android).
 *
 * Los frames se procesan con un canvas oculto: se pinta el `videoElement` en
 * él, se lee el píxel del ROI central y se calculan medias R/G/B y estadísticos
 * necesarios por el pipeline. No se almacenan frames ni video.
 */
export class CameraController {
  private stream: MediaStream | null = null
  private track: MediaStreamVideoTrack | null = null
  private video: HTMLVideoElement | null = null
  private canvas: HTMLCanvasElement | null = null
  private ctx: CanvasRenderingContext2D | null = null
  private running = false
  private rafId: number | null = null
  private capturedFrames = 0
  private caps: CameraCapabilitiesSnapshot | null = null

  async start(options: StartOptions): Promise<CameraCapabilitiesSnapshot> {
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function') {
      throw new Error('La API getUserMedia no está disponible en este navegador.')
    }
    const constraints: MediaStreamConstraints = {
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 1280 },
        height: { ideal: 720 },
        frameRate: { ideal: options.targetFps, max: 60 }
      }
    }
    this.stream = await navigator.mediaDevices.getUserMedia(constraints)
    const [track] = this.stream.getVideoTracks()
    this.track = track as MediaStreamVideoTrack

    const settings = this.track.getSettings()
    const capAny = (this.track.getCapabilities?.() ?? {}) as Record<string, unknown>
    const torchSupported = 'torch' in capAny
    let torchOn = false
    if (torchSupported) {
      try {
        // @ts-expect-error torch no está tipado en lib.dom todavía
        await this.track.applyConstraints({ advanced: [{ torch: true }] })
        torchOn = true
      } catch {
        torchOn = false
      }
    }

    this.video = document.createElement('video')
    this.video.setAttribute('playsinline', 'true')
    this.video.muted = true
    this.video.srcObject = this.stream
    await this.video.play()

    const w = settings.width ?? this.video.videoWidth
    const h = settings.height ?? this.video.videoHeight
    this.canvas = document.createElement('canvas')
    this.canvas.width = w
    this.canvas.height = h
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true })
    if (!this.ctx) throw new Error('No se pudo crear canvas 2D para captura de frames')

    this.caps = {
      deviceId: settings.deviceId ?? 'default',
      label: this.track.label || 'cámara trasera',
      width: w,
      height: h,
      frameRate: settings.frameRate ?? null,
      torchSupported,
      torchOn,
      exposureMode: (settings as MediaTrackSettings & { exposureMode?: string }).exposureMode ?? null,
      focusMode: (settings as MediaTrackSettings & { focusMode?: string }).focusMode ?? null
    }

    this.running = true
    this.capturedFrames = 0
    const loop = () => {
      if (!this.running) return
      try {
        const frame = this.extractFrame()
        if (frame) options.onFrame(frame)
      } catch (e) {
        console.warn('frame extract error', e)
      }
      this.rafId = requestAnimationFrame(loop)
    }
    this.rafId = requestAnimationFrame(loop)
    return this.caps
  }

  currentCapabilities(): CameraCapabilitiesSnapshot | null {
    return this.caps
  }

  async stop(): Promise<void> {
    this.running = false
    if (this.rafId !== null) cancelAnimationFrame(this.rafId)
    this.rafId = null
    if (this.track) {
      try {
        // @ts-expect-error torch constraint
        await this.track.applyConstraints({ advanced: [{ torch: false }] })
      } catch {
        /* ignore */
      }
      this.track.stop()
    }
    this.stream?.getTracks().forEach((t) => t.stop())
    this.stream = null
    this.track = null
    this.video = null
    this.canvas = null
    this.ctx = null
    this.caps = null
  }

  private extractFrame(): CameraFrameStats | null {
    if (!this.video || !this.ctx || !this.canvas) return null
    if (this.video.readyState < 2) return null
    const w = this.canvas.width
    const h = this.canvas.height
    // ROI = 60% central. Se dibuja el video completo y sólo se lee el ROI.
    const roiW = Math.max(16, Math.floor(w * 0.6))
    const roiH = Math.max(16, Math.floor(h * 0.6))
    const roiX = Math.floor((w - roiW) / 2)
    const roiY = Math.floor((h - roiH) / 2)
    this.ctx.drawImage(this.video, 0, 0, w, h)
    const imageData = this.ctx.getImageData(roiX, roiY, roiW, roiH)
    const data = imageData.data
    const step = roiW > 320 ? 2 : 1

    let sumR = 0
    let sumG = 0
    let sumB = 0
    let sumY = 0
    let sumY2 = 0
    let clipHigh = 0
    let clipLow = 0
    let count = 0
    for (let y = 0; y < roiH; y += step) {
      for (let x = 0; x < roiW; x += step) {
        const idx = (y * roiW + x) * 4
        const r = data[idx]
        const g = data[idx + 1]
        const b = data[idx + 2]
        const yv = (0.299 * r + 0.587 * g + 0.114 * b) | 0
        sumR += r
        sumG += g
        sumB += b
        sumY += yv
        sumY2 += yv * yv
        if (yv >= 250) clipHigh++
        if (yv <= 5) clipLow++
        count++
      }
    }
    if (count === 0) return null
    const invN = 1 / count
    const rMean = sumR * invN
    const gMean = sumG * invN
    const bMean = sumB * invN
    const yMean = sumY * invN
    const yVar = sumY2 * invN - yMean * yMean
    this.capturedFrames++
    return {
      timestampMs: performance.now(),
      width: w,
      height: h,
      redMean: rMean,
      greenMean: gMean,
      blueMean: bMean,
      clipHighRatio: clipHigh * invN,
      clipLowRatio: clipLow * invN,
      roiCoverage: (count * step * step) / (w * h),
      roiVariance: yVar
    }
  }
}

type MediaStreamVideoTrack = MediaStreamTrack & {
  getCapabilities?: () => MediaTrackCapabilities
  applyConstraints: (c: MediaTrackConstraints) => Promise<void>
}
