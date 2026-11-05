package com.iacamara.app.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dónde estaba el sujeto y dónde debería estar según la guía del coach (0..1, foto derecha). */
data class CropHint(val subjectX: Float, val subjectY: Float, val targetX: Float, val targetY: Float)

/**
 * Correcciones geométricas que solo puede hacer la cámara, porque sabe cómo estaba el móvil al disparar:
 *
 * - **Enderezar:** el giro medido por el sensor de gravedad (no adivinado mirando la imagen).
 * - **Perspectiva:** al fotografiar un edificio mirando hacia arriba, las verticales convergen; con la
 *   inclinación medida y la focal se corrigen como haría un objetivo descentrable (no del todo: queda
 *   más natural).
 * - **Recorte por composición:** acerca el sujeto al punto que marcaba la guía (sin recortar mucho).
 *
 * Coordenadas en píxeles de la foto derecha (x a la derecha, y hacia abajo).
 */
object PhotoGeometry {

    /** Plan de corrección: transformación de salida a origen y recorte final. */
    class Plan(
        val width: Int,
        val height: Int,
        private val srcW: Int,
        private val srcH: Int,
        private val rollRad: Double,
        private val pitchRad: Double,
        private val focalPx: Double,
        /** Esquina superior izquierda del recorte en coordenadas de salida centradas. */
        private val cropX: Double,
        private val cropY: Double,
        private val scale: Double,
        val description: String,
    ) {
        /** Píxel de salida (x, y) → posición en la foto original. */
        fun sourceOf(x: Double, y: Double, out: DoubleArray) {
            // Coordenadas centradas de la imagen corregida.
            val qx = cropX + x * scale
            val qy = cropY + y * scale
            // Deshacer la perspectiva (girar la cámara virtual de vuelta).
            var u = qx
            var v = qy
            if (pitchRad != 0.0) {
                val c = cos(-pitchRad)
                val s = sin(-pitchRad)
                val dy = v * c - focalPx * s
                val dz = v * s + focalPx * c
                u = focalPx * u / dz
                v = focalPx * dy / dz
            }
            // Deshacer el enderezado.
            if (rollRad != 0.0) {
                val c = cos(rollRad)
                val s = sin(rollRad)
                val ru = c * u - s * v
                val rv = s * u + c * v
                u = ru
                v = rv
            }
            out[0] = u + srcW / 2.0
            out[1] = v + srcH / 2.0
        }
    }

    /**
     * @param rollDeg giro del móvil (positivo = girado a la izquierda, la foto se ve inclinada a la
     *   derecha). Se endereza girando la imagen en sentido contrario.
     * @param pitchDeg inclinación de la cámara (positivo = mirando hacia arriba).
     * @param keystone fracción de la perspectiva que se corrige (0 = nada, 1 = verticales paralelas).
     * @param focalPx focal en píxeles (necesaria para la perspectiva).
     * @return null si no hay nada que corregir.
     */
    fun plan(
        width: Int,
        height: Int,
        rollDeg: Float,
        pitchDeg: Float = 0f,
        keystone: Float = 0f,
        focalPx: Float? = null,
        crop: CropHint? = null,
    ): Plan? {
        val roll = if (abs(rollDeg) >= 0.4f) Math.toRadians(rollDeg.toDouble()) else 0.0
        val pitch = if (focalPx != null && keystone > 0f && abs(pitchDeg) >= 3f) {
            Math.toRadians(pitchDeg * keystone.toDouble())
        } else {
            0.0
        }
        val f = (focalPx ?: 1f).toDouble()
        val notes = mutableListOf<String>()
        if (roll != 0.0) notes += "enderezada %.1f°".format(rollDeg)
        if (pitch != 0.0) notes += "perspectiva"

        // Esquinas de la foto original en coordenadas corregidas (centradas).
        val corners = listOf(0.0 to 0.0, width.toDouble() to 0.0, width.toDouble() to height.toDouble(), 0.0 to height.toDouble())
            .map { (x, y) -> forward(x - width / 2.0, y - height / 2.0, roll, pitch, f) }
        // Mayor rectángulo con la misma proporción dentro de la zona válida.
        val aspect = width.toDouble() / height
        val cx = corners.sumOf { it.first } / 4
        val cy = corners.sumOf { it.second } / 4
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            val hw = width * mid / 2
            val hh = hw / aspect
            val inside = listOf(cx - hw to cy - hh, cx + hw to cy - hh, cx + hw to cy + hh, cx - hw to cy + hh)
                .all { (x, y) -> insideConvex(corners, x, y) }
            if (inside) lo = mid else hi = mid
        }
        val validW = width * lo
        val validH = validW / aspect
        var left = cx - validW / 2
        var top = cy - validH / 2
        var cropW = validW
        var cropH = validH

        // Recorte por composición dentro de la zona válida.
        if (crop != null) {
            val (sx, sy) = forward(crop.subjectX * width - width / 2.0, crop.subjectY * height - height / 2.0, roll, pitch, f)
            val now = hypot((sx - left) / cropW - crop.targetX, (sy - top) / cropH - crop.targetY)
            var best = Triple(cropW, left, top)
            var bestCost = now
            for (k in doubleArrayOf(0.95, 0.9, 0.85)) {
                val w = validW * k
                val h = validH * k
                val l = (sx - crop.targetX * w).coerceIn(left, left + validW - w)
                val t = (sy - crop.targetY * h).coerceIn(top, top + validH - h)
                val d = hypot((sx - l) / w - crop.targetX, (sy - t) / h - crop.targetY)
                // Cada recorte extra tiene que compensar: se exige una mejora clara.
                val cost = d + 0.25 * (1 - k)
                if (cost < bestCost - 0.02) {
                    bestCost = cost
                    best = Triple(w, l, t)
                }
            }
            if (best.first < cropW) {
                cropW = best.first
                cropH = cropW / aspect
                left = best.second
                top = best.third
                notes += "recortada según la guía"
            }
        }
        if (notes.isEmpty()) return null
        // La salida no se amplía: como mucho tiene los píxeles que había en esa zona.
        val outW = cropW.roundToInt().coerceAtLeast(16)
        val outH = cropH.roundToInt().coerceAtLeast(16)
        return Plan(outW, outH, width, height, roll, pitch, f, left, top, cropW / outW, notes.joinToString(", "))
    }

    /** Aplica el plan: devuelve los píxeles corregidos (plan.width × plan.height). */
    fun apply(src: IntArray, width: Int, height: Int, plan: Plan): IntArray {
        val out = IntArray(plan.width * plan.height)
        val stripe = 64
        Parallel.forRange((plan.height + stripe - 1) / stripe) { s ->
            val p = DoubleArray(2)
            val y0 = s * stripe
            for (y in y0 until min(plan.height, y0 + stripe)) {
                for (x in 0 until plan.width) {
                    plan.sourceOf(x + 0.5, y + 0.5, p)
                    val sx = (p[0] - 0.5).toFloat().coerceIn(0f, width - 1f)
                    val sy = (p[1] - 0.5).toFloat().coerceIn(0f, height - 1f)
                    out[y * plan.width + x] = Fusion.bilinear(src, width, height, sx, sy)
                }
            }
        }
        return out
    }

    /** Punto de la foto original (centrado) → foto corregida (centrado): primero enderezar, luego perspectiva. */
    internal fun forward(x: Double, y: Double, roll: Double, pitch: Double, f: Double): Pair<Double, Double> {
        var u = x
        var v = y
        if (roll != 0.0) {
            // Giro visual en sentido antihorario (con la y hacia abajo).
            val c = cos(roll)
            val s = sin(roll)
            val ru = c * u + s * v
            val rv = -s * u + c * v
            u = ru
            v = rv
        }
        if (pitch != 0.0) {
            // La cámara virtual baja `pitch`: el rayo (u, v, f) gira alrededor del eje x.
            val c = cos(pitch)
            val s = sin(pitch)
            val dy = v * c - f * s
            val dz = v * s + f * c
            u = f * u / dz
            v = f * dy / dz
        }
        return u to v
    }

    private fun insideConvex(poly: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val (x1, y1) = poly[i]
            val (x2, y2) = poly[(i + 1) % poly.size]
            val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
            val s = if (cross > 1e-9) 1 else if (cross < -1e-9) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    /** Focal en píxeles a partir de la focal equivalente en 35 mm (EXIF) y el tamaño de la foto. */
    fun focalPixels(focal35mm: Float, width: Int, height: Int): Float =
        (focal35mm / 43.27f * hypot(width.toFloat(), height.toFloat()))
}
