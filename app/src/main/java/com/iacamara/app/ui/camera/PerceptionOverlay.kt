package com.iacamara.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.director.Tip
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.LightInfo
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.ui.theme.Amber
import kotlin.math.min

/** Marca el sujeto principal (esquinas ámbar) y las demás caras. */
@Composable
fun SubjectOverlay(perception: Perception?, front: Boolean, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val p = perception ?: return@Canvas
        val subject = p.subject
        for (face in p.faces) {
            if (subject != null && face == subject.box) continue
            val r = toView(face, front)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = r.topLeft,
                size = r.size,
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(1.5.dp.toPx()),
            )
        }
        if (subject != null) {
            val r = toView(subject.box, front)
            drawBrackets(r)
            val label = textMeasurer.measure(
                subject.label,
                TextStyle(color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            val y = if (r.bottom + label.size.height + 4f < size.height) r.bottom + 4f else r.top - label.size.height - 4f
            drawText(label, topLeft = Offset(r.left, y.coerceAtLeast(0f)))
        }
    }
}

private fun DrawScope.toView(box: NRect, front: Boolean): Rect {
    val left = if (front) 1f - box.right else box.left
    val right = if (front) 1f - box.left else box.right
    return Rect(left * size.width, box.top * size.height, right * size.width, box.bottom * size.height)
}

private fun DrawScope.drawBrackets(r: Rect) {
    val len = min(r.width, r.height) * 0.25f
    val stroke = 3.dp.toPx()
    val c = Amber
    // Arriba izquierda, arriba derecha, abajo izquierda, abajo derecha.
    drawLine(c, r.topLeft, r.topLeft + Offset(len, 0f), stroke, StrokeCap.Round)
    drawLine(c, r.topLeft, r.topLeft + Offset(0f, len), stroke, StrokeCap.Round)
    drawLine(c, r.topRight, r.topRight - Offset(len, 0f), stroke, StrokeCap.Round)
    drawLine(c, r.topRight, r.topRight + Offset(0f, len), stroke, StrokeCap.Round)
    drawLine(c, r.bottomLeft, r.bottomLeft + Offset(len, 0f), stroke, StrokeCap.Round)
    drawLine(c, r.bottomLeft, r.bottomLeft - Offset(0f, len), stroke, StrokeCap.Round)
    drawLine(c, r.bottomRight, r.bottomRight - Offset(len, 0f), stroke, StrokeCap.Round)
    drawLine(c, r.bottomRight, r.bottomRight - Offset(0f, len), stroke, StrokeCap.Round)
}

/** Escena detectada, condiciones y ajuste automático aplicado. */
@Composable
fun SceneBadge(perception: Perception, reason: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "${perception.scene.emoji} ${perception.scene.label}",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
        val conditions = perception.conditions
            .filter { it != Condition.LUCES_QUEMADAS || reason == null }
            .joinToString(" · ") { it.label }
        val detail = listOfNotNull(reason, conditions.ifEmpty { null }).distinct().joinToString(" · ")
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                color = Amber,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Consejo del Director IA. Si tiene acción, se puede tocar. */
@Composable
fun TipChip(tip: Tip, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val actionable = tip.action != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, if (actionable) Amber else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = actionable, onClick = onAction)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text("✨", fontSize = 13.sp)
        Text(tip.text, color = Color.White, fontSize = 13.sp)
    }
}

/** Histograma de luminancia: muestra si hay sombras empastadas o luces quemadas. */
@Composable
fun HistogramPanel(light: LightInfo, frameMs: Long, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(
        modifier
            .size(width = 120.dp, height = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f)),
    ) {
        val bins = light.histogram
        val pad = 4.dp.toPx()
        val barWidth = (size.width - pad * 2) / bins.size
        val maxHeight = size.height - pad * 2 - 10.dp.toPx()
        bins.forEachIndexed { i, v ->
            val h = v * maxHeight
            val color = when {
                i == bins.lastIndex && light.highlightClip > 0.01f -> Color(0xFFFF5252)
                i == 0 && light.shadowClip > 0.05f -> Color(0xFF64B5F6)
                else -> Color.White.copy(alpha = 0.85f)
            }
            drawRect(
                color = color,
                topLeft = Offset(pad + i * barWidth, size.height - pad - h),
                size = Size(barWidth * 0.85f, h),
            )
        }
        val info = textMeasurer.measure("IA ${frameMs} ms", TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp))
        drawText(info, topLeft = Offset(pad, pad / 2))
    }
}

@Composable
fun SmallNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
