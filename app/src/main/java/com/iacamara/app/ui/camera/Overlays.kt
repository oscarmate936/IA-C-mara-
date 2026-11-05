package com.iacamara.app.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.iacamara.app.sensors.LevelReading
import com.iacamara.app.ui.theme.Amber
import com.iacamara.app.ui.theme.LevelGreen
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

/** Rejilla de tercios. */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

/**
 * Nivel: con el móvil de pie dibuja la línea del horizonte real (verde cuando está recto);
 * con el móvil plano (foto cenital) dibuja un nivel de burbuja.
 * La lectura se hace solo en la fase de dibujo para no recomponer la pantalla en cada lectura.
 */
@Composable
fun LevelOverlay(reading: State<LevelReading?>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val r = reading.value ?: return@Canvas
        val c = center
        val stroke = 3.dp.toPx()
        val color = if (r.isLevel) LevelGreen else Color.White.copy(alpha = 0.9f)

        if (r.isFlat) {
            val radius = size.minDimension * 0.08f
            drawCircle(Color.White.copy(alpha = 0.7f), radius = radius, center = c, style = Stroke(2.dp.toPx()))
            drawLine(Color.White.copy(alpha = 0.5f), c - Offset(radius * 1.6f, 0f), c - Offset(radius * 1.15f, 0f), 2.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), c + Offset(radius * 1.15f, 0f), c + Offset(radius * 1.6f, 0f), 2.dp.toPx())
            // La burbuja se desplaza hacia el lado más alto, como un nivel real.
            var offset = Offset(r.nx, -r.ny) * (radius * 20f)
            val maxDistance = radius * 2.5f
            val distance = hypot(offset.x, offset.y)
            if (distance > maxDistance) offset *= maxDistance / distance
            drawCircle(if (r.isLevel) LevelGreen else Amber, radius = radius * 0.35f, center = c + offset)
        } else {
            val half = size.minDimension * 0.17f
            val gap = size.minDimension * 0.03f
            // Marcas fijas: el horizonte del encuadre actual.
            rotate(r.snappedDegrees, c) {
                drawLine(color.copy(alpha = 0.6f), c - Offset(half + gap * 3, 0f), c - Offset(half + gap, 0f), stroke, StrokeCap.Round)
                drawLine(color.copy(alpha = 0.6f), c + Offset(half + gap, 0f), c + Offset(half + gap * 3, 0f), stroke, StrokeCap.Round)
            }
            // Línea viva: el horizonte real según la gravedad.
            if (abs(r.deviationDegrees) < 30f) {
                rotate(r.rollDegrees, c) {
                    drawLine(color, c - Offset(half, 0f), c + Offset(half, 0f), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/** Anillo de enfoque que aparece donde se toca la pantalla. */
@Composable
fun FocusRing(point: Offset?, key: Int, modifier: Modifier = Modifier) {
    if (point == null) return
    val scale = remember(key) { Animatable(1.4f) }
    var visible by remember(key) { mutableStateOf(true) }
    LaunchedEffect(key) {
        scale.animateTo(1f, tween(220))
        delay(1200)
        visible = false
    }
    if (!visible) return
    Canvas(modifier.fillMaxSize()) {
        drawCircle(
            color = Amber,
            radius = 34.dp.toPx() * scale.value,
            center = point,
            style = Stroke(2.dp.toPx()),
        )
    }
}
