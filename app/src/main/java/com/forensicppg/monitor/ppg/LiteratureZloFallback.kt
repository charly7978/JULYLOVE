package com.forensicppg.monitor.ppg

/**
 * Fallback de Zero-Light Offset (ZLO).
 *
 * El ZLO real depende del **sensor concreto** del teléfono y del tiempo de
 * exposición; sólo se obtiene válidamente con una "captura oscura"
 * (tapón opaco sobre cámara + flash, ver flujo en MonitorViewModel).
 * Antes este fallback restaba 14–22 niveles RGB a CADA canal según la
 * MARCA del teléfono, con valores inventados sin trazabilidad de sensor.
 * Eso falsificaba el DC y desplazaba todos los ratios AC/DC: era una
 * de las causas por las que la app casi no calculaba SpO₂.
 *
 * Política actual:
 *   - Por defecto NO aplicamos ningún offset (R=G=B=0). Cualquier
 *     componente DC residual del sensor es absorbido por el seguidor de
 *     DC EMA del DSP — esa es la solución matemáticamente correcta.
 *   - Si el usuario captura un ZLO real con tapón opaco (mediana de
 *     56–320 frames negros estables), [SensorZloStore] lo persiste y
 *     [PpgFrameAnalyzer.configureSensorZlo] lo aplica. Ahí sí
 *     trazabilidad sensor-específica.
 */
object LiteratureZloFallback {
    data class Bounds(val r: Double, val g: Double, val b: Double)

    fun forCurrentDevice(): Bounds = Bounds(0.0, 0.0, 0.0)
}
