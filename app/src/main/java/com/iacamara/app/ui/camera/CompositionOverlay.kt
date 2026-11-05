package com.iacamara.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iacamara.app.composition.GuideStyle
import com.iacamara.app.composition.Guidance
import com.iacamara.app.ui.theme.Amber
import com.iacamara.app.ui.theme.LevelGreen
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Líneas guía dinámicas del coach: dónde está el sujeto, dónde debería estar (anillo fantasma),
 * la flecha entre ambos y la línea objetivo del horizonte.
 * Las coordenadas de `Guidance` están en el encuadre vertical sin espejo; con la cámara frontal
 * el visor está en espejo, así que se invierte la x.
 */
@Composable
fun CompositionOverlay(guidance: Guidance, front: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        fun point(p: Pair<Float, Float>) = Offset((if (front) 1f - p.first else p.first) * size.width, p.second * size.height)
        val good = guidance.isGood
        val color = if (good) LevelGreen else Amber

        if (guidance.style == GuideStyle.CENTRO) drawCenterCross(color.copy(alpha = 0.6f))

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

private fun DrawScope.drawCenterCross(color: Color) {
    val c = center
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
