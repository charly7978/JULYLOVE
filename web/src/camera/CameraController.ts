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

type MediaStreamVideoTrack = MediaStreamTrack & {
  getCapabilities?: () => MediaTrackCapabilities
  applyConstraints: (c: MediaTrackConstraints) => Promise<void>
}

/**
 * Controlador de cámara web. Utiliza getUserMedia con `facingMode:environment`
 * y activa el torch con applyConstraints({advanced:[{torch:true}]}) cuando el
 * browser/dispositivo lo expone (Chrome Android).
 *
 * Cada frame se procesa con un <canvas> oculto: se pinta el videoElement y se
 * lee sólo el ROI central. Se calculan R/G/B medios, varianza espacial del
 * rojo y —crítico— la fracción real de píxeles del ROI que lucen como dedo
 * iluminado por flash (rojo dominante, no saturado, no oscuro). Ese es el
 * valor que el detector de contacto usa para decidir si hay dedo o no.
 */
export class CameraController {
  private stream: MediaStream | null = null
  private track: MediaStreamVideoTrack | null = null
  private video: HTMLVideoElement | null = null
  private canvas: HTMLCanvasElement | null = null
  private ctx: CanvasRenderingContext2D | null = null
  private running = false
  private rafId: number | null = null
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
        // @ts-expect-error torch no está tipado en lib.dom
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

    const w = settings.width ?? this.video.videoWidth ?? 640
    const h = settings.height ?? this.video.videoHeight ?? 480
    this.canvas = document.createElement('canvas')
    // Downscale agresivo: con 320x240 hay sobra para ROI + ahorra CPU/GPU.
    const DOWNSCALED_WIDTH = 320
    const scale = Math.min(1, DOWNSCALED_WIDTH / w)
    this.canvas.width = Math.max(64, Math.round(w * scale))
    this.canvas.height = Math.max(48, Math.round(h * scale))
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true })
    if (!this.ctx) throw new Error('No se pudo crear canvas 2D para captura de frames')

    this.caps = {
      deviceId: settings.deviceId ?? 'default',
      label: this.track.label || 'cámara trasera',
      width: this.canvas.width,
      height: this.canvas.height,
      frameRate: settings.frameRate ?? null,
      torchSupported,
      torchOn,
      exposureMode: (settings as MediaTrackSettings & { exposureMode?: string }).exposureMode ?? null,
      focusMode: (settings as MediaTrackSettings & { focusMode?: string }).focusMode ?? null
    }

    this.running = true
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
    const roiW = Math.max(16, Math.floor(w * 0.6))
    const roiH = Math.max(16, Math.floor(h * 0.6))
    const roiX = Math.floor((w - roiW) / 2)
    const roiY = Math.floor((h - roiH) / 2)
    this.ctx.drawImage(this.video, 0, 0, w, h)
    const imageData = this.ctx.getImageData(roiX, roiY, roiW, roiH)
    const data = imageData.data
    const total = roiW * roiH
    if (total === 0) return null

    let sumR = 0
    let sumG = 0
    let sumB = 0
    let sumR2 = 0
    let clipHigh = 0
    let clipLow = 0
    let fingerPixels = 0

    // Umbrales de píxel-dedo bajo flash blanco:
    //   R dominante sobre G y B, en rango razonable, no saturado ni oscuro.
    const FINGER_MIN_R = 80
    const FINGER_MAX_R = 250
    const FINGER_MIN_RG = 1.25
    const FINGER_MIN_RB = 1.35

    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]
      const g = data[i + 1]
      const b = data[i + 2]
      sumR += r
      sumG += g
      sumB += b
      sumR2 += r * r
      if (r >= 250) clipHigh++
      if (r <= 5) clipLow++
      const isFinger =
        r >= FINGER_MIN_R &&
        r <= FINGER_MAX_R &&
        (g <= 1 || r / Math.max(1, g) >= FINGER_MIN_RG) &&
        (b <= 1 || r / Math.max(1, b) >= FINGER_MIN_RB)
      if (isFinger) fingerPixels++
    }

    const invN = 1 / total
    const rMean = sumR * invN
    const gMean = sumG * invN
    const bMean = sumB * invN
    const rVar = sumR2 * invN - rMean * rMean
    const coverage = fingerPixels / total

    return {
      timestampMs: performance.now(),
      width: w,
      height: h,
      redMean: rMean,
      greenMean: gMean,
      blueMean: bMean,
      clipHighRatio: clipHigh * invN,
      clipLowRatio: clipLow * invN,
      roiCoverage: coverage,
      roiVariance: rVar
    }
  }
}
