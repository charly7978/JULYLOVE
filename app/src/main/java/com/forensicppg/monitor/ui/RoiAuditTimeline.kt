package com.forensicppg.monitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forensicppg.monitor.forensic.RoiAuditEvent

private val colorTrigger = Color(0xFFFF5533)
private val colorClear = Color(0xFF33CC88)
private val colorBg = Color(0xFF0C1218)
private val colorBorder = Color(0xFF223344)

/**
 * Línea temporal de las últimas entradas del registro ROI (TRIGGER / CLEAR),
 * con copia rápida al portapapeles de la entrada actual (selección por toque).
 */
@Composable
fun RoiAuditTimeline(
    entries: List<RoiAuditEvent>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 16
) {
    val visible = remember(entries, maxVisible) {
        entries.takeLast(maxVisible.coerceAtLeast(1))
    }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            selectedIndex = visible.lastIndex
        } else {
            selectedIndex = -1
        }
    }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorBg, RoundedCornerShape(6.dp))
            .border(1.dp, colorBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Auditoría ROI (últ. $maxVisible)",
                color = Color(0xFF88CCEE),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            val sel = visible.getOrNull(selectedIndex)
            TextButton(
                onClick = {
                    if (sel == null) return@TextButton
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("roi_audit", sel.clipboardLine()))
                    Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                },
                enabled = sel != null,
                modifier = Modifier.padding(0.dp)
            ) {
                Text(
                    "Copiar actual",
                    color = if (sel != null) Color(0xFF22FFAA) else Color(0xFF556677),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            }
        }
        if (visible.isEmpty()) {
            Text(
                "Sin eventos ROI en esta sesión.",
                color = Color(0xFF778899),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            return@Column
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (maxVisible.coerceAtMost(visible.size).coerceAtLeast(1) * 17 + 8).dp)
                .verticalScroll(rememberScrollState())
        ) {
            visible.forEachIndexed { idx, e ->
                val isSel = idx == selectedIndex
                val edgeColor = if (e.edge == RoiAuditEvent.Edge.TRIGGER) colorTrigger else colorClear
                Row(
                    modifier = Modifier
                        .widthIn(min = 280.dp)
                        .background(
                            if (isSel) Color(0xFF1A2838) else Color.Transparent,
                            RoundedCornerShape(3.dp)
                        )
                        .clickable { selectedIndex = idx }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        e.edge.name,
                        color = edgeColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 52.dp, max = 56.dp)
                    )
                    Text(
                        e.channel.name,
                        color = Color(0xFFAACCDD),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier.widthIn(min = 96.dp, max = 118.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        e.summary,
                        color = Color(0xFFE0E8EE),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 200.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
