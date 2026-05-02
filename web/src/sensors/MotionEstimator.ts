/**
 * Estima un "motion score" 0..1 a partir del acelerómetro del dispositivo vía
 * DeviceMotionEvent. Si el navegador no expone el evento, el score es 0 (no se
 * inventa movimiento).
 */
export class MotionEstimator {
  private readonly win: Float64Array
  private idx = 0
  private filled = 0
  private sum = 0
  private sumSq = 0
  private lastMag = 9.81
  private lastSpike = 0
  private latest = 0
  private listening = false
  private handler: ((e: DeviceMotionEvent) => void) | null = null

  constructor(windowSize = 50) {
    this.win = new Float64Array(windowSize)
  }

  async requestPermissionIfNeeded(): Promise<void> {
    const anyEvt = DeviceMotionEvent as unknown as { requestPermission?: () => Promise<string> }
    if (typeof anyEvt.requestPermission === 'function') {
      try {
        await anyEvt.requestPermission()
      } catch {
        /* ignore */
      }
    }
  }

  start(): void {
    if (this.listening) return
    this.listening = true
    this.handler = (e: DeviceMotionEvent) => {
      const a = e.accelerationIncludingGravity
      if (!a) return
      const ax = a.x ?? 0
      const ay = a.y ?? 0
      const az = a.z ?? 0
      const mag = Math.sqrt(ax * ax + ay * ay + az * az)
      const delta = mag - this.lastMag
      if (Math.abs(delta) > 3.5) this.lastSpike = Math.min(1, Math.abs(delta) / 12)
      this.lastSpike *= 0.92
      this.lastMag = mag
      const old = this.win[this.idx]
      this.win[this.idx] = mag
      this.sum += mag - old
      this.sumSq += mag * mag - old * old
      this.idx = (this.idx + 1) % this.win.length
      if (this.filled < this.win.length) this.filled++
      const mean = this.sum / this.filled
      const variance = this.sumSq / this.filled - mean * mean
      const std = Math.sqrt(Math.max(0, variance))
      const stdScore = Math.min(1, std / 2.5)
      this.latest = Math.min(1, 0.75 * stdScore + 0.35 * this.lastSpike)
    }
    window.addEventListener('devicemotion', this.handler, { passive: true })
  }

  stop(): void {
    if (this.handler) window.removeEventListener('devicemotion', this.handler)
    this.listening = false
    this.handler = null
  }

  score(): number {
    return this.latest
  }

  reset(): void {
    this.win.fill(0)
    this.idx = 0
    this.filled = 0
    this.sum = 0
    this.sumSq = 0
    this.lastMag = 9.81
    this.lastSpike = 0
    this.latest = 0
  }
}
