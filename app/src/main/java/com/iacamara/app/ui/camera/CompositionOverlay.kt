package com.iacamara.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.composition.GuideStyle
import com.iacamara.app.composition.Guidance
import com.iacamara.app.composition.ShapeKind
import com.iacamara.app.ui.theme.Amber
import com.iacamara.app.ui.theme.LevelGreen
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Color de lo que la IA ve en la escena (ejes, líneas guía), distinto del de la guía. */
private val SceneCyan = Color(0xFF7FE7F2)

/**
 * Líneas guía dinámicas del coach: la guía elegida (tercios, proporción áurea, espiral, centro,
 * simetría o líneas de la escena), dónde está el sujeto, dónde debería estar (anillo fantasma),
 * la flecha entre ambos y la línea objetivo del horizonte.
 * Las coordenadas de `Guidance` están en el encuadre vertical sin espejo; con la cámara frontal
 * el visor está en espejo, así que se invierte la x.
 *
 * @param showGuide dibujar los trazos de la guía (botón de rejilla); el anillo y la flecha se ven siempre.
 */
@Composable
fun CompositionOverlay(guidance: Guidance, front: Boolean, showGuide: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        fun point(p: Pair<Float, Float>) = Offset((if (front) 1f - p.first else p.first) * size.width, p.second * size.height)
        val good = guidance.isGood
        val color = if (good) LevelGreen else Amber

        if (showGuide) {
            for (shape in guidance.shapes) {
                val pts = shape.points.map(::point)
                when (shape.kind) {
                    ShapeKind.GRID -> drawPolyline(pts, Color.White.copy(alpha = 0.45f), 1.dp.toPx())
                    ShapeKind.SPIRAL -> drawPolyline(pts, color.copy(alpha = 0.8f), 2.dp.toPx())
                    ShapeKind.AXIS -> drawPolyline(
                        pts, SceneCyan.copy(alpha = 0.9f), 2.dp.toPx(),
                        PathEffect.dashPathEffect(floatArrayOf(16f, 10f)),
                    )
                    ShapeKind.LEADING_LINE -> {
                        drawPolyline(pts, SceneCyan.copy(alpha = 0.35f), 7.dp.toPx())
                        drawPolyline(pts, SceneCyan.copy(alpha = 0.9f), 2.dp.toPx())
                    }
                    ShapeKind.VANISHING_POINT -> pts.firstOrNull()?.let {
                        drawCircle(SceneCyan, radius = 7.dp.toPx(), center = it, style = Stroke(2.dp.toPx()))
                        drawCircle(SceneCyan, radius = 2.5.dp.toPx(), center = it)
                    }
                    ShapeKind.CENTER -> pts.firstOrNull()?.let { drawCenterCross(it, color.copy(alpha = 0.6f)) }
                }
            }
        }

        guidance.horizonTarget?.let { (a, b) ->
            drawLine(
                color = color.copy(alpha = 0.8f),
                start = point(a),
                end = point(b),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
            )
        }

        val target = guidance.target?.let(::point) ?: return@Canvas
        val radius = 22.dp.toPx()
        drawCircle(color.copy(alpha = 0.85f), radius = radius, center = target, style = Stroke(2.5.dp.toPx()))
        drawCircle(color.copy(alpha = 0.25f), radius = radius, center = target)

        val subject = guidance.subjectCenter?.let(::point) ?: return@Canvas
        drawCircle(Color.White, radius = 5.dp.toPx(), center = subject)
        val distance = hypot(target.x - subject.x, target.y - subject.y)
        if (!good && distance > radius * 1.2f) drawArrow(subject, target, radius, color)
    }
}

private fun DrawScope.drawPolyline(points: List<Offset>, color: Color, width: Float, effect: PathEffect? = null) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

private fun DrawScope.drawCenterCross(c: Offset, color: Color) {
    val len = size.minDimension * 0.05f
    drawLine(color, c - Offset(len, 0f), c + Offset(len, 0f), 2.dp.toPx())
    drawLine(color, c - Offset(0f, len), c + Offset(0f, len), 2.dp.toPx())
}

/** Flecha del sujeto hacia donde debería estar. */
private fun DrawScope.drawArrow(from: Offset, to: Offset, stopBefore: Float, color: Color) {
    val angle = atan2(to.y - from.y, to.x - from.x)
    val end = Offset(to.x - cos(angle) * stopBefore, to.y - sin(angle) * stopBefore)
    val stroke = 3.dp.toPx()
    drawLine(
        color = color,
        start = from,
        end = end,
        strokeWidth = stroke,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
    )
    val head = 12.dp.toPx()
    val spread = 0.5f
    drawLine(color, end, Offset(end.x - cos(angle - spread) * head, end.y - sin(angle - spread) * head), stroke, StrokeCap.Round)
    drawLine(color, end, Offset(end.x - cos(angle + spread) * head, end.y - sin(angle + spread) * head), stroke, StrokeCap.Round)
}

/**
 * Nombre de la guía en uso ("IA · Espiral áurea" o "📌 Tercios"). Al tocarlo se elige otra guía
 * o se vuelve a dejar que la IA la elija.
 */
@Composable
fun GuideChip(
    style: GuideStyle,
    forced: GuideStyle?,
    onSelect: (GuideStyle?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text = if (forced == null) "IA · ${style.label}" else "📌 ${style.label}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { GuideOption("Automática", "La IA elige la guía según la escena", forced == null) },
                onClick = {
                    open = false
                    onSelect(null)
                },
            )
            GuideStyle.entries.forEach { option ->
                DropdownMenuItem(
                    text = { GuideOption(option.label, option.description, forced == option) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun GuideOption(title: String, description: String, selected: Boolean) {
    Column(Modifier.widthIn(max = 260.dp)) {
        Text(
            text = if (selected) "✓ $title" else title,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        Text(text = description, fontSize = 11.sp, color = Color.Gray)
    }
}

/** Puntuación del encuadre (0–100) en un anillo: rojo, ámbar o verde. */
@Composable
fun ScoreBadge(score: Int, modifier: Modifier = Modifier) {
    val color = when {
        score >= Guidance.GOOD_SCORE -> LevelGreen
        score >= 50 -> Amber
        else -> Color(0xFFFF5252)
    }
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.Black.copy(alpha = 0.5f))
            val stroke = 4.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.2f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * score / 100f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("$score", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
