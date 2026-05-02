/**
 * Remuestreador de pasos no uniformes a frecuencia fija.
 *
 * `requestAnimationFrame` entrega frames con intervalos variables (16-33 ms
 * típicos, con jitter). Los filtros IIR y detectores Elgendi asumen fs
 * constante. Este remuestreador usa interpolación lineal entre la muestra
 * actual y la previa para emitir muestras exactas a `fs` objetivo.
 *
 * Si entre dos frames de cámara pasan > 2/fs segundos se emiten las muestras
 * intermedias necesarias (mantiene continuidad); si llegan demasiados frames
 * rápidos se consumen sin emitir nada.
 */
export class UniformResampler {
  private readonly dtMs: number
  private nextEmitMs: number | null = null
  private prevMs: number | null = null
  private prevValueRed = 0
  private prevValueGreen = 0
  private prevValueBlue = 0

  constructor(private readonly fs: number) {
    this.dtMs = 1000 / fs
  }

  reset(): void {
    this.nextEmitMs = null
    this.prevMs = null
    this.prevValueRed = 0
    this.prevValueGreen = 0
    this.prevValueBlue = 0
  }

  /** Devuelve 0+ muestras interpoladas para (red, green, blue). */
  push(
    tsMs: number,
    red: number,
    green: number,
    blue: number
  ): Array<{ ts: number; red: number; green: number; blue: number }> {
    const out: Array<{ ts: number; red: number; green: number; blue: number }> = []
    if (this.prevMs === null) {
      this.prevMs = tsMs
      this.nextEmitMs = tsMs
      this.prevValueRed = red
      this.prevValueGreen = green
      this.prevValueBlue = blue
      out.push({ ts: tsMs, red, green, blue })
      this.nextEmitMs = tsMs + this.dtMs
      return out
    }
    const t0 = this.prevMs
    const t1 = tsMs
    if (t1 <= t0) return out
    while (this.nextEmitMs !== null && this.nextEmitMs <= t1) {
      const alpha = (this.nextEmitMs - t0) / (t1 - t0)
      out.push({
        ts: this.nextEmitMs,
        red: this.prevValueRed + alpha * (red - this.prevValueRed),
        green: this.prevValueGreen + alpha * (green - this.prevValueGreen),
        blue: this.prevValueBlue + alpha * (blue - this.prevValueBlue)
      })
      this.nextEmitMs += this.dtMs
    }
    this.prevMs = t1
    this.prevValueRed = red
    this.prevValueGreen = green
    this.prevValueBlue = blue
    return out
  }

  targetFps(): number {
    return this.fs
  }
}
