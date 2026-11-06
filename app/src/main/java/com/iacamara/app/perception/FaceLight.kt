package com.iacamara.app.perception

import kotlin.math.max

/**
 * Cómo le da la luz a la cara principal (luminancia media 0–255 de cada zona, con la cara derecha).
 * Sirve para los consejos de luz de un fotógrafo: sol cenital con los ojos en sombra, media cara oscura,
 * cara quemada.
 */
data class FaceLight(
    /** Frente y cejas (cuarto superior de la caja de la cara). */
    val forehead: Float,
    /** Banda de los ojos. */
    val eyes: Float,
    /** Mejillas y boca. */
    val cheeks: Float,
    /** Mitad izquierda y derecha, vistas en el encuadre. */
    val left: Float,
    val right: Float,
    val mean: Float,
    /** Fracción de la cara casi blanca (quemada). */
    val clipped: Float,
) {
    /** Diferencia entre las dos mitades de la cara, 0 (igual) … 1 (una mitad negra). */
    val sideContrast: Float get() = kotlin.math.abs(left - right) / max(1f, max(left, right))

    /** Los ojos quedan en sombra respecto a la frente y las mejillas (luz desde arriba). */
    val eyeShadow: Float get() = 1f - eyes / max(1f, max(forehead, cheeks))

    companion object {
        private const val GRID = 24

        /**
         * @param pixels fotograma ARGB que ve el modelo (w×h).
         * @param face caja de la cara en el encuadre vertical (P).
         * @param rotation giro del fotograma del modelo respecto al encuadre (el de [FrameMath]).
         */
        fun measure(pixels: IntArray, w: Int, h: Int, face: NRect, rotation: Int): FaceLight? {
            if (face.width <= 0f || face.height <= 0f) return null
            var fs = 0f; var fn = 0
            var es = 0f; var en = 0
            var cs = 0f; var cn = 0
            var ls = 0f; var ln = 0
            var rs = 0f; var rn = 0
            var total = 0f
            var n = 0
            var clipped = 0
            for (j in 0 until GRID) {
                val v = (j + 0.5f) / GRID
                for (i in 0 until GRID) {
                    val u = (i + 0.5f) / GRID
                    // Solo el óvalo de la cara (las esquinas de la caja son pelo o fondo).
                    val du = (u - 0.5f) * 2f
                    val dv = (v - 0.5f) * 2f
                    if (du * du + dv * dv > 1f) continue
                    val (mx, my) = FrameMath.rotate(face.left + u * face.width, face.top + v * face.height, rotation)
                    val x = (mx * w).toInt().coerceIn(0, w - 1)
                    val y = (my * h).toInt().coerceIn(0, h - 1)
                    val p = pixels[y * w + x]
                    val l = ((77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8).toFloat()
                    total += l
                    n++
                    if (l >= 245f) clipped++
                    when {
                        v < 0.25f -> { fs += l; fn++ }
                        v in 0.3f..0.5f -> { es += l; en++ }
                        v in 0.55f..0.85f -> { cs += l; cn++ }
                    }
                    if (u < 0.45f) { ls += l; ln++ } else if (u > 0.55f) { rs += l; rn++ }
                }
            }
            if (n == 0 || fn == 0 || en == 0 || cn == 0 || ln == 0 || rn == 0) return null
            return FaceLight(fs / fn, es / en, cs / cn, ls / ln, rs / rn, total / n, clipped / n.toFloat())
        }
    }
}
