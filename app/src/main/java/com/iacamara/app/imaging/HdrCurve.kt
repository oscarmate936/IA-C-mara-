package com.iacamara.app.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Curva de tono del HDR (de luz real a pantalla). Se ajusta a cada escena:
 * - `pre`: ganancia para que los tonos medios queden igual de claros que en la foto normal.
 * - `white`: la luz más fuerte de la escena; por encima se comprime suavemente (Reinhard extendido),
 *   así el cielo conserva color y detalle en vez de quemarse.
 * - `shadowGamma` (<1): cuánto se levantan las sombras; más cuanto más oscura es la foto normal.
 */
class HdrCurve(val pre: Float, val white: Float, val shadowGamma: Float) {

    // La tabla se indexa por la raíz cuadrada de la luz: así tiene mucha resolución en las sombras
    // (con un índice lineal, todo lo más oscuro que ~sRGB 20 caía en la primera casilla y salía negro).
    private val lut = FloatArray(LUT_SIZE + 2) { i ->
        val u = i.toFloat() / LUT_SIZE
        val x = u * u * LUT_RANGE
        val t = x * (1f + x / (white * white)) / (1f + x)
        t.coerceIn(0f, 1f).pow(shadowGamma)
    }

    /** Valor lineal de luz → brillo de pantalla (0–1). */
    fun tone(linear: Float): Float {
        val x = (linear * pre).coerceIn(0f, LUT_RANGE)
        val pos = kotlin.math.sqrt(x / LUT_RANGE) * LUT_SIZE
        val idx = pos.toInt().coerceAtMost(LUT_SIZE)
        val frac = pos - idx
        return lut[idx] + (lut[idx + 1] - lut[idx]) * frac
    }

    /** Píxel en luz lineal → ARGB, conservando el color (se escala por la luminancia). */
    fun apply(r: Float, g: Float, b: Float): Int {
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (l <= 1e-6f) return argb(0, 0, 0)
        val k = tone(l) / l
        return argb(toSrgb(r * k), toSrgb(g * k), toSrgb(b * k))
    }

    companion object {
        private const val LUT_SIZE = 4096
        private const val LUT_RANGE = 16f

        /** sRGB (0–255) → luz lineal. */
        val TO_LINEAR = FloatArray(256) { (it / 255f).pow(2.2f) }

        /** Confianza de un valor: 0 en negro puro o blanco quemado, 1 en zonas bien expuestas. */
        val VALID = FloatArray(256) {
            val x = it / 255f
            min((x - 0.03f) / 0.1f, (0.95f - x) / 0.1f).coerceIn(0f, 1f)
        }

        private val TO_SRGB = IntArray(LUT_SIZE + 1) { (255f * (it / LUT_SIZE.toFloat()).pow(1f / 2.2f) + 0.5f).toInt() }

        fun toSrgb(v: Float): Int = TO_SRGB[(v.coerceIn(0f, 1f) * LUT_SIZE).toInt()]

        /** Calcula la curva con una versión pequeña (1/8) de las fotos. */
        fun fit(frames: List<FrameSource>, flows: List<Flow>, reference: Int, evs: FloatArray): HdrCurve {
            val scale = 8
            val lumas = frames.map { it.luma(scale) }
            val lw = lumas[reference].width
            val lh = lumas[reference].height
            val merged = FloatArray(lw * lh)
            val refLin = FloatArray(lw * lh)
            var dark = 0
            val shift = FloatArray(2)
            for (y in 0 until lh) {
                for (x in 0 until lw) {
                    val idx = y * lw + x
                    var s = 0f
                    var n = 0f
                    for (k in frames.indices) {
                        flows[k].at(x * scale + scale / 2f, y * scale + scale / 2f, shift)
                        val l = lumas[k]
                        val sx = (x + shift[0] / scale).toInt().coerceIn(0, l.width - 1)
                        val sy = (y + shift[1] / scale).toInt().coerceIn(0, l.height - 1)
                        val v = l[sx, sy]
                        val wgt = VALID[v] * if (k == reference) 1.5f else 1f
                        s += wgt * TO_LINEAR[v] * 2f.pow(evs[reference] - evs[k])
                        n += wgt
                    }
                    val rv = lumas[reference][x, y]
                    refLin[idx] = TO_LINEAR[rv]
                    merged[idx] = if (n > 0f) s / n else refLin[idx]
                    if (rv < 30) dark++
                }
            }
            val white = max(1f, percentile(merged, 0.995f))
            val medMerged = max(1e-4f, percentile(merged, 0.5f))
            val medRef = max(1e-4f, percentile(refLin, 0.5f))
            val darkFraction = dark / (lw * lh).toFloat()
            val shadowGamma = 1f - min(0.2f, darkFraction * 0.6f)
            // Ganancia previa para que la mediana quede como en la foto normal (búsqueda binaria).
            var lo = 0.05f
            var hi = 50f
            repeat(40) {
                val mid = (lo + hi) / 2f
                val x = mid * medMerged
                val t = x * (1f + x / (white * white)) / (1f + x)
                if (t < medRef) lo = mid else hi = mid
            }
            return HdrCurve((lo + hi) / 2f, white, shadowGamma)
        }

        private fun percentile(values: FloatArray, q: Float): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.copyOf()
            sorted.sort()
            return sorted[((sorted.size - 1) * q).toInt()]
        }
    }
}
