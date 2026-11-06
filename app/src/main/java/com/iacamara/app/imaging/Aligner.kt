package com.iacamara.app.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Cómo comparar dos fotogramas al alinearlos. */
enum class AlignMode {
    /** Diferencia de brillo: para fotos con la misma exposición (ráfaga, noche). */
    SSD,

    /** Mapas de bits por la mediana (Ward): funciona aunque las exposiciones sean distintas (HDR). */
    MTB,
}

/**
 * Desplazamiento de cada zona del fotograma respecto a la referencia, en píxeles a resolución completa.
 * Al usar una rejilla de teselas también corrige pequeños giros de la mano, no solo desplazamientos.
 */
class Flow(
    val cols: Int,
    val rows: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    val dx: FloatArray,
    val dy: FloatArray,
) {
    /** Desplazamiento en (x, y) interpolado entre los centros de las teselas. Escribe en out[0], out[1]. */
    fun at(x: Float, y: Float, out: FloatArray) {
        val tx = (x / fullWidth * cols - 0.5f).coerceIn(0f, (cols - 1).toFloat())
        val ty = (y / fullHeight * rows - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val x0 = tx.toInt()
        val y0 = ty.toInt()
        val x1 = min(x0 + 1, cols - 1)
        val y1 = min(y0 + 1, rows - 1)
        val fx = tx - x0
        val fy = ty - y0
        out[0] = bilinear(dx, x0, y0, x1, y1, fx, fy)
        out[1] = bilinear(dy, x0, y0, x1, y1, fx, fy)
    }

    private fun bilinear(a: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, fx: Float, fy: Float): Float {
        val top = a[y0 * cols + x0] + (a[y0 * cols + x1] - a[y0 * cols + x0]) * fx
        val bottom = a[y1 * cols + x0] + (a[y1 * cols + x1] - a[y1 * cols + x0]) * fx
        return top + (bottom - top) * fy
    }

    /** Mayor desplazamiento en valor absoluto (para saber cuánto margen leer). */
    val maxAbs: Float
        get() = max(dx.maxOf { abs(it) }, dy.maxOf { abs(it) })

    companion object {
        fun zero(width: Int, height: Int) = Flow(1, 1, width, height, floatArrayOf(0f), floatArrayOf(0f))
    }
}

object Aligner {

    /**
     * Alinea `image` con `reference` (ambas luminancias reducidas `scale` veces respecto a la foto completa).
     * Primero un desplazamiento global con pirámide y luego un ajuste fino por teselas.
     */
    fun align(
        reference: LumaPlane,
        image: LumaPlane,
        scale: Int,
        fullWidth: Int,
        fullHeight: Int,
        mode: AlignMode,
        maxShiftFull: Int = 160,
        tiles: Int = 8,
        search: Int = 3,
    ): Flow {
        val (gx, gy) = globalShift(reference, image, maxShift = max(4, maxShiftFull / scale), mode)
        val cols = tiles
        val rows = tiles
        val dx = FloatArray(cols * rows)
        val dy = FloatArray(cols * rows)
        val refMed = reference.median()
        val imgMed = image.median()
        val tw = reference.width / cols
        val th = reference.height / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = c * tw
                val y0 = r * th
                val x1 = if (c == cols - 1) reference.width else x0 + tw
                val y1 = if (r == rows - 1) reference.height else y0 + th
                var best = Float.MAX_VALUE
                var bx = gx
                var by = gy
                var fx = gx.toFloat()
                var fy = gy.toFloat()
                if (hasTexture(reference, x0, y0, x1, y1)) {
                    val size = 2 * search + 1
                    val costs = FloatArray(size * size)
                    for (sy in gy - search..gy + search) {
                        for (sx in gx - search..gx + search) {
                            val cost = cost(reference, image, x0, y0, x1, y1, sx, sy, 2, mode, refMed, imgMed)
                            costs[(sy - gy + search) * size + (sx - gx + search)] = cost
                            // A igualdad, el más cercano al desplazamiento global.
                            val c = penalized(cost, abs(sx - gx) + abs(sy - gy), 2 * search)
                            if (c < best) {
                                best = c
                                bx = sx
                                by = sy
                            }
                        }
                    }
                    // Ajuste por debajo del píxel: parábola por el coste de los vecinos.
                    val ix = bx - gx + search
                    val iy = by - gy + search
                    fx = bx + subpixel(costs, size, ix, iy, horizontal = true)
                    fy = by + subpixel(costs, size, ix, iy, horizontal = false)
                }
                dx[r * cols + c] = fx
                dy[r * cols + c] = fy
            }
        }
        val mdx = median3x3(dx, cols, rows)
        val mdy = median3x3(dy, cols, rows)
        for (i in mdx.indices) {
            mdx[i] *= scale
            mdy[i] *= scale
        }
        return Flow(cols, rows, fullWidth, fullHeight, mdx, mdy)
    }

    /** Desplazamiento global (en píxeles del plano dado) con búsqueda en pirámide. */
    fun globalShift(reference: LumaPlane, image: LumaPlane, maxShift: Int, mode: AlignMode): Pair<Int, Int> {
        val refPyramid = pyramid(reference)
        val imgPyramid = pyramid(image)
        val levels = min(refPyramid.size, imgPyramid.size)
        var dx = 0
        var dy = 0
        for (level in levels - 1 downTo 0) {
            val ref = refPyramid[level]
            val img = imgPyramid[level]
            val refMed = ref.median()
            val imgMed = img.median()
            val radius = if (level == levels - 1) max(2, (maxShift shr level) + 1) else 1
            val reach = 2 * max(1, maxShift shr level)
            var best = Float.MAX_VALUE
            var bx = dx
            var by = dy
            val step = if (ref.width * ref.height > 200_000) 2 else 1
            for (sy in dy - radius..dy + radius) {
                for (sx in dx - radius..dx + radius) {
                    // Si varios desplazamientos encajan igual (un horizonte: moverlo a los lados no cambia
                    // nada), se prefiere el más pequeño, no el primero que se probó (el más extremo).
                    val c = penalized(cost(ref, img, 0, 0, ref.width, ref.height, sx, sy, step, mode, refMed, imgMed), abs(sx) + abs(sy), reach)
                    if (c < best) {
                        best = c
                        bx = sx
                        by = sy
                    }
                }
            }
            dx = bx
            dy = by
            if (level > 0) {
                dx *= 2
                dy *= 2
            }
        }
        return dx to dy
    }

    /** Coste algo mayor cuanto mayor es el desplazamiento (hasta un 10 % en el extremo de la búsqueda). */
    private fun penalized(cost: Float, distance: Int, reach: Int): Float {
        if (cost == Float.MAX_VALUE) return cost
        return (cost + 1e-4f) * (1f + 0.1f * distance / max(1, reach))
    }

    private fun pyramid(base: LumaPlane): List<LumaPlane> {
        val levels = mutableListOf(base)
        while (min(levels.last().width, levels.last().height) >= 96 && levels.size < 6) {
            levels += levels.last().downsample2()
        }
        return levels
    }

    private fun hasTexture(l: LumaPlane, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        var s = 0L
        var s2 = 0L
        var n = 0
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val v = l[x, y]
                s += v
                s2 += v * v
                n++
            }
        }
        if (n == 0) return false
        val mean = s.toDouble() / n
        return s2.toDouble() / n - mean * mean > 20.0
    }

    /** Coste medio de comparar la región de la referencia con la imagen desplazada (dx, dy). */
    private fun cost(
        ref: LumaPlane,
        img: LumaPlane,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        dx: Int,
        dy: Int,
        step: Int,
        mode: AlignMode,
        refMed: Int,
        imgMed: Int,
    ): Float {
        var sum = 0L
        var n = 0
        val ys = max(y0, -dy)
        val ye = min(y1, img.height - dy)
        val xs = max(x0, -dx)
        val xe = min(x1, img.width - dx)
        var y = ys
        while (y < ye) {
            var x = xs
            while (x < xe) {
                val r = ref[x, y]
                val i = img[x + dx, y + dy]
                when (mode) {
                    AlignMode.SSD -> {
                        val d = r - i
                        sum += d * d
                        n++
                    }
                    AlignMode.MTB -> {
                        // Se ignoran los píxeles muy cerca de la mediana (ruido).
                        if (abs(r - refMed) > 4 && abs(i - imgMed) > 4) {
                            if ((r > refMed) != (i > imgMed)) sum++
                            n++
                        }
                    }
                }
                x += step
            }
            y += step
        }
        val area = ((x1 - x0) / step) * ((y1 - y0) / step)
        if (n < area / 4 || n < 16) return Float.MAX_VALUE
        return sum.toFloat() / n
    }

    /** Vértice de la parábola por (−1, 0, +1) alrededor del mínimo; entre −0,5 y 0,5. */
    private fun subpixel(costs: FloatArray, size: Int, ix: Int, iy: Int, horizontal: Boolean): Float {
        val (a, b) = if (horizontal) (ix - 1 to iy) to (ix + 1 to iy) else (ix to iy - 1) to (ix to iy + 1)
        if (a.first !in 0 until size || a.second !in 0 until size || b.first !in 0 until size || b.second !in 0 until size) return 0f
        val left = costs[a.second * size + a.first]
        val center = costs[iy * size + ix]
        val right = costs[b.second * size + b.first]
        if (left == Float.MAX_VALUE || right == Float.MAX_VALUE) return 0f
        val denominator = left - 2 * center + right
        if (denominator <= 1e-6f) return 0f
        return (0.5f * (left - right) / denominator).coerceIn(-0.5f, 0.5f)
    }

    private fun median3x3(a: FloatArray, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(a.size)
        val window = FloatArray(9)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var n = 0
                for (j in -1..1) for (i in -1..1) {
                    val rr = r + j
                    val cc = c + i
                    if (rr in 0 until rows && cc in 0 until cols) window[n++] = a[rr * cols + cc]
                }
                window.sort(0, n)
                out[r * cols + c] = window[n / 2]
            }
        }
        return out
    }
}
