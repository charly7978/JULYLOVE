package com.forensicppg.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.forensicppg.monitor.domain.BeatEvent
import com.forensicppg.monitor.domain.BeatType
import com.forensicppg.monitor.domain.PpgSample
import kotlinx.coroutines.flow.Flow

/**
 * Renderizado eficiente de la forma de onda PPG en un Canvas de Compose.
 *
 *  - Mantiene un ring buffer de hasta [windowSeconds] de datos REALES (nunca
 *    inventa puntos; si no llegan muestras, la señal deja de dibujarse).
 *  - Escalado adaptativo por min/max recientes con protección contra
 *    clipping visual.
 *  - Dibuja una grilla tipo osciloscopio médico y los marcadores de latidos
 *    (normal vs anómalo).
 */
@Composable
fun PpgWaveformCanvas(
    sampleFlow: Flow<PpgSample>,
    beatFlow: Flow<BeatEvent>,
    modifier: Modifier = Modifier,
    windowSeconds: Double = 10.0
) {
    val capacity = 1200
    val ring = remember { WaveRing(capacity) }
    val beatBuf = remember { BeatRing(64) }
    var dirty by remember { mutableStateOf(0L) }

    LaunchedEffect(sampleFlow) {
        sampleFlow.collect { s ->
            ring.push(s)
            dirty = s.timestampNs
        }
    }
    LaunchedEffect(beatFlow) {
        beatFlow.collect { b ->
            beatBuf.push(b)
            dirty = b.timestampNs
        }
    }
    LaunchedEffect(Unit) {
        while (true) { withFrameNanos { dirty = it } }
    }

    Canvas(
        modifier = modifier
            .background(Color(0xFF05080A))
    ) {
        @Suppress("UNUSED_VARIABLE") val keepDirty = dirty
        drawGrid()
        drawWave(ring, windowSeconds)
        drawBeats(beatBuf, ring, windowSeconds)
        if (ring.size() == 0) {
            drawEmptyState()
        }
    }
}

private fun DrawScope.drawGrid() {
    val col = Color(0x223FFF80)
    val majorCol = Color(0x4422FFAA)
    val gridXCount = 12
    val gridYCount = 6
    for (i in 1 until gridXCount) {
        val x = size.width * i / gridXCount
        val strokeW = if (i % 3 == 0) 1.2f else 0.6f
        drawLine(
            color = if (i % 3 == 0) majorCol else col,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height),
            strokeWidth = strokeW
        )
    }
    for (j in 1 until gridYCount) {
        val y = size.height * j / gridYCount
        val strokeW = if (j % 2 == 0) 1.2f else 0.6f
        drawLine(
            color = if (j % 2 == 0) majorCol else col,
            start = androidx.compose.ui.geometry.Offset(0f, y),
            end = androidx.compose.ui.geometry.Offset(size.width, y),
            strokeWidth = strokeW
        )
    }
}

private fun DrawScope.drawWave(ring: WaveRing, windowSeconds: Double) {
    val n = ring.size()
    if (n < 2) return
    val first = ring.at(0) ?: return
    val last = ring.at(n - 1) ?: return
    val tStart = (last.timestampNs - (windowSeconds * 1e9).toLong()).coerceAtLeast(first.timestampNs)
    val tEnd = last.timestampNs
    val span = (tEnd - tStart).coerceAtLeast(1L).toDouble()

    var mn = Double.POSITIVE_INFINITY
    var mx = Double.NEGATIVE_INFINITY
    for (i in 0 until n) {
        val s = ring.at(i) ?: continue
        if (s.timestampNs < tStart) continue
        if (s.displayValue < mn) mn = s.displayValue
        if (s.displayValue > mx) mx = s.displayValue
    }
    if (!mn.isFinite() || !mx.isFinite() || mx - mn < 1e-6) { mn -= 1.0; mx += 1.0 }
    val padding = (mx - mn) * 0.15
    mn -= padding; mx += padding

    val path = Path()
    var started = false
    for (i in 0 until n) {
        val s = ring.at(i) ?: continue
        if (s.timestampNs < tStart) continue
        val x = (((s.timestampNs - tStart).toDouble()) / span * size.width).toFloat()
        val y = (size.height - ((s.displayValue - mn) / (mx - mn)) * size.height).toFloat()
        if (!started) { path.moveTo(x, y); started = true }
        else path.lineTo(x, y)
    }
    drawPath(path = path, color = Color(0xFF2DFFAA), style = Stroke(width = 2.6f))
    drawPath(path = path, color = Color(0x3322FFAA), style = Stroke(width = 7.0f))
}

private fun DrawScope.drawBeats(beatBuf: BeatRing, ring: WaveRing, windowSeconds: Double) {
    val n = ring.size()
    if (n < 2) return
    val last = ring.at(n - 1) ?: return
    val first = ring.at(0) ?: return
    val tStart = (last.timestampNs - (windowSeconds * 1e9).toLong()).coerceAtLeast(first.timestampNs)
    val tEnd = last.timestampNs
    val span = (tEnd - tStart).coerceAtLeast(1L).toDouble()
    val beatN = beatBuf.size()
    for (i in 0 until beatN) {
        val b = beatBuf.at(i) ?: continue
        if (b.timestampNs < tStart) continue
        val x = (((b.timestampNs - tStart).toDouble()) / span * size.width).toFloat()
        val color = when (b.type) {
            BeatType.NORMAL -> Color(0xCC22FFAA)
            BeatType.SUSPECT_PREMATURE -> Color(0xFFFF3333)
            BeatType.SUSPECT_PAUSE -> Color(0xFFFFAA22)
            BeatType.SUSPECT_MISSED -> Color(0xFFFFAA22)
            BeatType.IRREGULAR -> Color(0xFFFF8844)
            BeatType.INVALID_SIGNAL -> Color(0xFF888888)
        }
        val strokeW = if (b.type == BeatType.NORMAL) 1.6f else 3.0f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(x, 0f),
            end = androidx.compose.ui.geometry.Offset(x, size.height),
            strokeWidth = strokeW
        )
    }
}

private fun DrawScope.drawEmptyState() {
    val col = Color(0x55FFFFFF)
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawLine(
        color = col,
        start = androidx.compose.ui.geometry.Offset(0f, cy),
        end = androidx.compose.ui.geometry.Offset(size.width, cy),
        strokeWidth = 1.0f
    )
}

/** Ring buffer de muestras, sin allocations por push. */
private class WaveRing(capacity: Int) {
    private val arr = arrayOfNulls<PpgSample>(capacity)
    private var head = 0
    private var filled = 0
    fun push(s: PpgSample) { arr[head] = s; head = (head + 1) % arr.size; if (filled < arr.size) filled++ }
    fun size(): Int = filled
    fun at(i: Int): PpgSample? {
        if (i >= filled) return null
        val start = (head - filled + arr.size) % arr.size
        return arr[(start + i) % arr.size]
    }
}

private class BeatRing(capacity: Int) {
    private val arr = arrayOfNulls<BeatEvent>(capacity)
    private var head = 0
    private var filled = 0
    fun push(b: BeatEvent) { arr[head] = b; head = (head + 1) % arr.size; if (filled < arr.size) filled++ }
    fun size(): Int = filled
    fun at(i: Int): BeatEvent? {
        if (i >= filled) return null
        val start = (head - filled + arr.size) % arr.size
        return arr[(start + i) % arr.size]
    }
}

@Suppress("unused")
private val tileHeightDp = 80.dp
