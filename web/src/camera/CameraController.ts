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
  whiteBalanceMode: string | null
  exposureTimeNs: number | null
  iso: number | null
  zoom: number | null
}

type VideoTrack = MediaStreamTrack & {
  getCapabilities?: () => MediaTrackCapabilities
  applyConstraints: (c: MediaTrackConstraints) => Promise<void>
}

type RvfcMetadata = {
  presentedFrames?: number
  mediaTime?: number
  expectedDisplayTime?: number
}

type RvfcVideo = HTMLVideoElement & {
  requestVideoFrameCallback?: (cb: (now: number, meta: RvfcMetadata) => void) => number
  cancelVideoFrameCallback?: (id: number) => void
}

/**
 * CameraController — fotograma a fotograma, fotométrico, fail-closed.
 *
 * Diseño:
 *   1. getUserMedia con `facingMode: environment` + resolución modesta
 *      (640×480 ideal). Calidad de PPG no mejora con mayor resolución;
 *      menos píxeles = menos ruido temporal y CPU estable.
 *   2. Una vez abierta la cámara se intenta:
 *        - encender torch
 *        - bloquear AE (`exposureMode = manual` o `continuous` lock)
 *        - bloquear AWB (`whiteBalanceMode = manual`)
 *        - bloquear focus (`focusMode = manual`)
 *      Cuando el dispositivo no expone alguna opción se cae al modo nativo
 *      sin error (fail-soft sólo en lo que el navegador no expone).
 *   3. La extracción de muestras usa `requestVideoFrameCallback` cuando
 *      existe — esto da UNA muestra por fotograma realmente entregado por
 *      el sensor (no por refresco del display) y elimina muestras
 *      duplicadas que destruyen la coherencia temporal del PPG.
 *   4. El ROI es un círculo central de 60% del área. Se computan en una
 *      sola pasada: media R/G/B, suma de cuadrados (Welford),
 *      conteo de píxeles dedo, clip alto / clip bajo.
 *   5. La cobertura "fingerPixels/total" es la única métrica de contacto
 *      que el resto del pipeline cree (no se inventan números).
 */
export class CameraController {
  private stream: MediaStream | null = null
  private track: VideoTrack | null = null
  private video: RvfcVideo | null = null
  private canvas: HTMLCanvasElement | null = null
  private ctx: CanvasRenderingContext2D | null = null
  private running = false
  private rafId: number | null = null
  private rvfcId: number | null = null
  private caps: CameraCapabilitiesSnapshot | null = null
  private lastMediaTime = -1
  private lastPresentedFrames = -1

  async start(options: StartOptions): Promise<CameraCapabilitiesSnapshot> {
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== 'function') {
      throw new Error('La API getUserMedia no está disponible en este navegador.')
    }
    const constraints: MediaStreamConstraints = {
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 640 },
        height: { ideal: 480 },
        frameRate: { ideal: options.targetFps, max: 60 }
      }
    }
    this.stream = await navigator.mediaDevices.getUserMedia(constraints)
    const [track] = this.stream.getVideoTracks()
    this.track = track as VideoTrack
    this.lastMediaTime = -1
    this.lastPresentedFrames = -1

    // Locks ópticos: torch + exposición/foco/balance fijos. Cualquier
    // flag no soportado es ignorado en silencio.
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
    const advanced: MediaTrackConstraintSet[] = []
    const cap = capAny as {
      exposureMode?: string[]
      whiteBalanceMode?: string[]
      focusMode?: string[]
      exposureCompensation?: { min?: number; max?: number }
    }
    if (cap.exposureMode?.includes('manual')) advanced.push({ exposureMode: 'manual' } as MediaTrackConstraintSet)
    else if (cap.exposureMode?.includes('continuous')) advanced.push({ exposureMode: 'continuous' } as MediaTrackConstraintSet)
    if (cap.whiteBalanceMode?.includes('manual')) advanced.push({ whiteBalanceMode: 'manual' } as MediaTrackConstraintSet)
    else if (cap.whiteBalanceMode?.includes('continuous')) advanced.push({ whiteBalanceMode: 'continuous' } as MediaTrackConstraintSet)
    if (cap.focusMode?.includes('manual')) advanced.push({ focusMode: 'manual' } as MediaTrackConstraintSet)
    else if (cap.focusMode?.includes('continuous')) advanced.push({ focusMode: 'continuous' } as MediaTrackConstraintSet)
    if (advanced.length) {
      try {
        await this.track.applyConstraints({ advanced } as MediaTrackConstraints)
      } catch {
        /* el navegador rechazó alguno: dejamos los modos en su default */
      }
    }

    const settings = this.track.getSettings()
    this.video = document.createElement('video') as RvfcVideo
    this.video.setAttribute('playsinline', 'true')
    this.video.muted = true
    this.video.srcObject = this.stream
    await this.video.play()

    const w = settings.width ?? this.video.videoWidth ?? 640
    const h = settings.height ?? this.video.videoHeight ?? 480
    this.canvas = document.createElement('canvas')
    // Downscale agresivo. El PPG pulsátil es DC + ~1 Hz: es invariante a
    // la resolución espacial. 240×180 reduce ruido temporal por
    // promediado de píxeles.
    const DOWNSCALED_WIDTH = 240
    const scale = Math.min(1, DOWNSCALED_WIDTH / w)
    this.canvas.width = Math.max(64, Math.round(w * scale))
    this.canvas.height = Math.max(48, Math.round(h * scale))
    this.ctx = this.canvas.getContext('2d', { willReadFrequently: true, alpha: false })
    if (!this.ctx) throw new Error('No se pudo crear canvas 2D para captura de frames')

    const settled = this.track.getSettings()
    this.caps = {
      deviceId: settings.deviceId ?? 'default',
      label: this.track.label || 'cámara trasera',
      width: this.canvas.width,
      height: this.canvas.height,
      frameRate: settings.frameRate ?? null,
      torchSupported,
      torchOn,
      exposureMode: (settled as MediaTrackSettings & { exposureMode?: string }).exposureMode ?? null,
      focusMode: (settled as MediaTrackSettings & { focusMode?: string }).focusMode ?? null,
      whiteBalanceMode:
        (settled as MediaTrackSettings & { whiteBalanceMode?: string }).whiteBalanceMode ?? null,
      exposureTimeNs:
        (settled as MediaTrackSettings & { exposureTime?: number }).exposureTime != null
          ? Math.round(((settled as MediaTrackSettings & { exposureTime?: number }).exposureTime ?? 0) * 100)
          : null,
      iso: (settled as MediaTrackSettings & { iso?: number }).iso ?? null,
      zoom: (settled as MediaTrackSettings & { zoom?: number }).zoom ?? null
    }

    this.running = true

    // Ruta preferida: requestVideoFrameCallback. Garantiza una muestra
    // por fotograma "presentado" por el decoder (no del refresh display)
    // y permite detectar duplicados con el contador `presentedFrames`.
    const useRvfc = typeof this.video.requestVideoFrameCallback === 'function'
    if (useRvfc) {
      const onFrame = (_now: number, meta: RvfcMetadata) => {
        if (!this.running || !this.video) return
        const presented = meta.presentedFrames ?? -1
        const mediaTime = meta.mediaTime ?? -1
        const isFresh =
          presented < 0 || presented !== this.lastPresentedFrames
        const newMedia = mediaTime < 0 || mediaTime !== this.lastMediaTime
        if (isFresh && newMedia) {
          this.lastPresentedFrames = presented
          this.lastMediaTime = mediaTime
          try {
            const frame = this.extractFrame()
            if (frame) options.onFrame(frame)
          } catch (e) {
            console.warn('frame extract error', e)
          }
        }
        this.rvfcId = this.video.requestVideoFrameCallback?.(onFrame) ?? null
      }
      this.rvfcId = this.video.requestVideoFrameCallback!(onFrame)
    } else {
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
    }
    return this.caps
  }

  currentCapabilities(): CameraCapabilitiesSnapshot | null {
    return this.caps
  }

  async stop(): Promise<void> {
    this.running = false
    if (this.rafId !== null) cancelAnimationFrame(this.rafId)
    this.rafId = null
    if (this.rvfcId !== null && this.video?.cancelVideoFrameCallback) {
      try {
        this.video.cancelVideoFrameCallback(this.rvfcId)
      } catch {
        /* ignore */
      }
    }
    this.rvfcId = null
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
    this.lastMediaTime = -1
    this.lastPresentedFrames = -1
  }

  private extractFrame(): CameraFrameStats | null {
    if (!this.video || !this.ctx || !this.canvas) return null
    if (this.video.readyState < 2) return null
    const w = this.canvas.width
    const h = this.canvas.height
    this.ctx.drawImage(this.video, 0, 0, w, h)

    // ROI = elipse central que abarca el 60% del lado menor. Contamos
    // sólo píxeles dentro de la elipse (cubre el círculo natural que
    // forma la yema cuando se apoya). Trabajamos sobre ImageData
    // completo y filtramos por la ecuación de la elipse: una sola
    // pasada O(n) sin asignar buffers temporales.
    const imageData = this.ctx.getImageData(0, 0, w, h)
    const data = imageData.data
    const cx = w / 2
    const cy = h / 2
    const rx = (w * 0.6) / 2
    const ry = (h * 0.6) / 2
    const rx2 = rx * rx
    const ry2 = ry * ry

    let sumR = 0
    let sumG = 0
    let sumB = 0
    let sumR2 = 0
    let clipHigh = 0
    let clipLow = 0
    let fingerPixels = 0
    let total = 0

    // Umbrales píxel-dedo bajo flash blanco (R dominante, no saturado,
    // no oscuro). Los rangos están deliberadamente amplios; la decisión
    // final de contacto la toma el detector con histéresis temporal.
    const FINGER_MIN_R = 60
    const FINGER_MAX_R = 252
    const FINGER_MIN_RG = 1.15
    const FINGER_MIN_RB = 1.20

    for (let y = 0; y < h; y++) {
      const dy = y - cy
      const dy2 = (dy * dy) / ry2
      if (dy2 > 1) continue
      const dxLimit2 = 1 - dy2
      const dxMax = Math.sqrt(dxLimit2 * rx2)
      const xStart = Math.max(0, Math.floor(cx - dxMax))
      const xEnd = Math.min(w, Math.ceil(cx + dxMax))
      const rowBase = y * w * 4
      for (let x = xStart; x < xEnd; x++) {
        const i = rowBase + x * 4
        const r = data[i]
        const g = data[i + 1]
        const b = data[i + 2]
        sumR += r
        sumG += g
        sumB += b
        sumR2 += r * r
        if (r >= 250) clipHigh++
        if (r <= 5) clipLow++
        if (
          r >= FINGER_MIN_R &&
          r <= FINGER_MAX_R &&
          (g <= 1 || r / Math.max(1, g) >= FINGER_MIN_RG) &&
          (b <= 1 || r / Math.max(1, b) >= FINGER_MIN_RB)
        ) {
          fingerPixels++
        }
        total++
      }
    }

    if (total === 0) return null
    const invN = 1 / total
    const rMean = sumR * invN
    const gMean = sumG * invN
    const bMean = sumB * invN
    const rVar = Math.max(0, sumR2 * invN - rMean * rMean)

    return {
      timestampMs: performance.now(),
      width: w,
      height: h,
      redMean: rMean,
      greenMean: gMean,
      blueMean: bMean,
      clipHighRatio: clipHigh * invN,
      clipLowRatio: clipLow * invN,
      roiCoverage: fingerPixels * invN,
      roiVariance: rVar
    }
  }
}
