package com.iacamara.app.composition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Guías de composición que puede usar el coach. La IA elige una según la escena, o la fija el usuario. */
enum class GuideStyle(val label: String, val description: String) {
    TERCIOS("Tercios", "El sujeto en una de las líneas o cruces que dividen la foto en tres"),
    AUREA("Proporción áurea", "Como los tercios, pero en la proporción 1 : 1,618 (algo más cerca del centro)"),
    ESPIRAL("Espiral áurea", "La espiral lleva la mirada hacia el sujeto, con mucho espacio libre alrededor"),
    CENTRO("Centro", "Sujeto grande, primer plano o selfie: centrado y con fuerza"),
    SIMETRIA("Simetría", "Edificios, pasillos o reflejos: el eje de simetría en el centro"),
    LINEAS("Líneas guía", "Caminos, vías, vallas o barandillas que llevan la mirada"),
}

/** Tipo de trazo que dibuja el visor para la guía. */
enum class ShapeKind {
    /** Líneas de la rejilla (tercios o proporción áurea), o la línea central. */
    GRID,
    /** Espiral áurea. */
    SPIRAL,
    /** Eje de simetría o de un reflejo detectado en la escena. */
    AXIS,
    /** Línea guía detectada en la escena (camino, vía, valla...). */
    LEADING_LINE,
    /** Punto donde se juntan las líneas guía (un solo punto). */
    VANISHING_POINT,
    /** Cruz del centro (un solo punto). */
    CENTER,
}

/** Un trazo de la guía: una polilínea (o un punto) en coordenadas normalizadas. */
data class GuideShape(val kind: ShapeKind, val points: List<Pair<Float, Float>>)

/** Proporción áurea y espiral áurea (de Fibonacci), estiradas al encuadre. */
object GoldenRatio {
    const val PHI = 1.618034f

    /** 1/φ² y 1/φ: las dos líneas de la rejilla áurea. */
    const val MINOR = 0.381966f
    const val MAJOR = 0.618034f

    /** Polo ("ojo") de la espiral en un rectángulo áureo: 1/(1 + 1/φ²) del ancho y del alto. */
    const val EYE = 0.7236068f

    fun eye(eyeRight: Boolean, eyeBottom: Boolean): Pair<Float, Float> =
        (if (eyeRight) EYE else 1f - EYE) to (if (eyeBottom) EYE else 1f - EYE)

    /**
     * Puntos de la espiral áurea dentro del encuadre (0..1), con el ojo en la esquina indicada.
     * Se construye con cuadrados sucesivos de un rectángulo áureo (cada uno con su cuarto de
     * circunferencia) y se estira al encuadre; en vertical se gira el rectángulo.
     */
    fun spiral(portrait: Boolean, eyeRight: Boolean, eyeBottom: Boolean, turns: Int = 10): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        // Rectángulo áureo horizontal φ × 1: cuadrados a la izquierda, arriba, derecha y abajo.
        var x = 0f
        var y = 0f
        var w = PHI
        var h = 1f
        for (k in 0 until turns) {
            val cx: Float
            val cy: Float
            val s: Float
            val from: Float
            when (k % 4) {
                0 -> { s = h; cx = x + s; cy = y + s; from = 180f; x += s; w -= s }
                1 -> { s = w; cx = x; cy = y + s; from = 270f; y += s; h -= s }
                2 -> { s = h; cx = x + w - s; cy = y; from = 0f; w -= s }
                else -> { s = w; cx = x + s; cy = y + h - s; from = 90f; h -= s }
            }
            val steps = if (k < 4) 14 else 6
            for (i in 0..steps) {
                if (k > 0 && i == 0) continue
                val a = (from + 90f * i / steps) * PI.toFloat() / 180f
                points += (cx + s * cos(a)) / PHI to (cy + s * sin(a))
            }
        }
        // El ojo de la construcción queda abajo a la derecha (EYE, EYE).
        return points.map { (u, v) ->
            val (px, py) = if (portrait) v to u else u to v
            (if (eyeRight) px else 1f - px) to (if (eyeBottom) py else 1f - py)
        }
    }
}

/**
 * Evita que la guía automática salte de una a otra: solo cambia cuando la nueva se propone
 * varias veces seguidas.
 */
class GuideStyleStabilizer(private val needed: Int = 4) {
    private var current: GuideStyle? = null
    private var candidate: GuideStyle? = null
    private var count = 0

    fun update(proposed: GuideStyle): GuideStyle {
        val now = current
        if (now == null || proposed == now) {
            current = proposed
            candidate = null
            count = 0
            return proposed
        }
        if (proposed == candidate) count++ else {
            candidate = proposed
            count = 1
        }
        if (count >= needed) {
            current = proposed
            candidate = null
            count = 0
        }
        return current ?: proposed
    }

    fun reset() {
        current = null
        candidate = null
        count = 0
    }
}
