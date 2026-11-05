package com.iacamara.app.imaging

import kotlin.math.max
import kotlin.math.min

/** Rectángulo en píxeles [left, right) × [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun clampTo(w: Int, h: Int) = PixelRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
}

/**
 * "Todos con los ojos abiertos": en una foto de grupo, la cara de quien parpadeó se sustituye por la
 * misma cara de otra foto de la ráfaga en la que tenía los ojos abiertos. La cara se alinea con la de
 * la foto base (el móvil y la persona se mueven un poco entre fotos) y se funde con bordes suaves.
 */
object FaceMerge {

    /**
     * Pega en `base` (w×h) la zona `region` de `donor`, alineada con la base.
     * @param maxShift desplazamiento máximo que se busca (píxeles).
     * @return el desplazamiento usado (dx, dy) de la base al donante.
     */
    fun paste(base: IntArray, w: Int, h: Int, donor: FrameSource, region: PixelRect, maxShift: Int = 32): Pair<Int, Int> {
        val r = region.clampTo(w, h)
        if (r.width < 8 || r.height < 8) return 0 to 0
        // Zona del donante con margen para buscar el desplazamiento.
        val dx0 = max(0, r.left - maxShift)
        val dy0 = max(0, r.top - maxShift)
        val dx1 = min(donor.width, r.right + maxShift)
        val dy1 = min(donor.height, r.bottom + maxShift)
        val dw = dx1 - dx0
        val dh = dy1 - dy0
        val donorPx = donor.readRegion(dx0, dy0, dw, dh)

        // Búsqueda del desplazamiento a media resolución (luminancia), y ajuste fino a resolución completa.
        val (sx, sy) = bestShift(base, w, r, donorPx, dw, dh, dx0, dy0, maxShift)

        // Fundido con una elipse de bordes suaves (sin costuras visibles).
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val rx = r.width / 2f
        val ry = r.height / 2f
        for (y in r.top until r.bottom) {
            for (x in r.left until r.right) {
                val nx = (x + 0.5f - cx) / rx
                val ny = (y + 0.5f - cy) / ry
                val d = nx * nx + ny * ny
                if (d >= 1f) continue
                // 1 en el centro, 0 en el borde de la elipse; transición en el 35 % exterior.
                val t = ((1f - d) / 0.35f).coerceIn(0f, 1f)
                val wgt = t * t * (3f - 2f * t)
                val ox = x + sx - dx0
                val oy = y + sy - dy0
                if (ox < 0 || oy < 0 || ox >= dw || oy >= dh) continue
                val a = base[y * w + x]
                val b = donorPx[oy * dw + ox]
                base[y * w + x] = blend(a, b, wgt)
            }
        }
        return sx to sy
    }

    private fun bestShift(
        base: IntArray, w: Int, r: PixelRect,
        donor: IntArray, dw: Int, dh: Int, dx0: Int, dy0: Int, maxShift: Int,
    ): Pair<Int, Int> {
        var best = Long.MAX_VALUE
        var bx = 0
        var by = 0
        // Muestreo de la zona cada `step` píxeles para que sea rápido incluso en caras grandes.
        val step = max(1, min(r.width, r.height) / 48)
        fun cost(sx: Int, sy: Int): Long {
            var c = 0L
            var y = r.top
            while (y < r.bottom) {
                val oy = y + sy - dy0
                if (oy in 0 until dh) {
                    var x = r.left
                    while (x < r.right) {
                        val ox = x + sx - dx0
                        if (ox in 0 until dw) {
                            val d = LumaPlane.luma(base[y * w + x]) - LumaPlane.luma(donor[oy * dw + ox])
                            c += d * d
                        } else {
                            c += 255L * 255
                        }
                        x += step
                    }
                }
                y += step
            }
            return c
        }
        // Búsqueda gruesa (cada 2 px) y luego fina alrededor del mejor.
        var sy = -maxShift
        while (sy <= maxShift) {
            var sx = -maxShift
            while (sx <= maxShift) {
                val c = cost(sx, sy)
                if (c < best) {
                    best = c
                    bx = sx
                    by = sy
                }
                sx += 2
            }
            sy += 2
        }
        val cx = bx
        val cy = by
        for (fy in cy - 1..cy + 1) for (fx in cx - 1..cx + 1) {
            if (fx < -maxShift || fx > maxShift || fy < -maxShift || fy > maxShift) continue
            val c = cost(fx, fy)
            if (c < best) {
                best = c
                bx = fx
                by = fy
            }
        }
        return bx to by
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t + 0.5f).toInt()
        return argb(ch(16), ch(8), ch(0))
    }
}
