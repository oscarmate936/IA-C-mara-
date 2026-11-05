package com.iacamara.app.imaging

import kotlin.math.max
import kotlin.math.min

/*
 * Núcleo de fotografía computacional (Fase 4). Este paquete no usa nada de Android: así el mismo código
 * se prueba en el ordenador con fotos reales y se ejecuta en el móvil.
 * Los píxeles son ARGB empaquetados en Int, como en android.graphics.Bitmap.
 */

/** Plano de luminancia de 8 bits (0–255). */
class LumaPlane(val width: Int, val height: Int, val data: ByteArray) {

    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    /** Mitad de tamaño, promediando bloques de 2×2. */
    fun downsample2(): LumaPlane {
        val w = max(1, width / 2)
        val h = max(1, height / 2)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = y * 2
            val y1 = min(y0 + 1, height - 1)
            for (x in 0 until w) {
                val x0 = x * 2
                val x1 = min(x0 + 1, width - 1)
                val s = this[x0, y0] + this[x1, y0] + this[x0, y1] + this[x1, y1]
                out[y * w + x] = ((s + 2) / 4).toByte()
            }
        }
        return LumaPlane(w, h, out)
    }

    fun median(): Int {
        val hist = IntArray(256)
        for (b in data) hist[b.toInt() and 0xFF]++
        var acc = 0
        val half = data.size / 2
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc > half) return v
        }
        return 255
    }

    fun mean(): Float {
        var s = 0L
        for (b in data) s += b.toInt() and 0xFF
        return s / max(1, data.size).toFloat()
    }

    companion object {
        fun luma(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return (77 * r + 150 * g + 29 * b) shr 8
        }

        /** Luminancia reducida `scale` veces (promedio de bloques scale×scale). */
        fun fromArgb(pixels: IntArray, width: Int, height: Int, scale: Int = 1): LumaPlane {
            val w = max(1, width / scale)
            val h = max(1, height / scale)
            val out = ByteArray(w * h)
            val n = scale * scale
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0
                    for (j in 0 until scale) {
                        val row = (y * scale + j) * width
                        for (i in 0 until scale) s += luma(pixels[row + x * scale + i])
                    }
                    out[y * w + x] = (s / n).toByte()
                }
            }
            return LumaPlane(w, h, out)
        }
    }
}

/**
 * Un fotograma a resolución completa que se lee por regiones. En el móvil se decodifica el JPEG por
 * franjas (BitmapRegionDecoder) para no tener varias fotos de 12 MP completas en memoria a la vez.
 */
interface FrameSource {
    val width: Int
    val height: Int

    /** Píxeles ARGB de la región [x, x+w) × [y, y+h), que debe estar dentro de la imagen. */
    fun readRegion(x: Int, y: Int, w: Int, h: Int): IntArray

    /** Luminancia reducida `scale` veces, para alinear y puntuar. */
    fun luma(scale: Int): LumaPlane
}

/** Fotograma completo en memoria (pruebas y fotos pequeñas). */
class ArrayFrameSource(
    override val width: Int,
    override val height: Int,
    val pixels: IntArray,
) : FrameSource {
    override fun readRegion(x: Int, y: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(pixels, (y + r) * width + x, out, r * w, w)
        return out
    }

    override fun luma(scale: Int): LumaPlane = LumaPlane.fromArgb(pixels, width, height, scale)
}

internal fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** Nitidez y ruido estimados sobre la luminancia. */
object ImageStats {

    /** Varianza del laplaciano: cuanto mayor, más nítida (sirve para elegir la mejor foto de una ráfaga). */
    fun sharpness(l: LumaPlane): Float {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until l.height - 1) {
            for (x in 1 until l.width - 1) {
                val v = 4 * l[x, y] - l[x - 1, y] - l[x + 1, y] - l[x, y - 1] - l[x, y + 1]
                sum += v
                sum2 += v.toDouble() * v
                n++
            }
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sum2 / n - mean * mean).toFloat()
    }

    /**
     * Desviación típica del ruido (niveles de 0–255) a partir de la mediana del detalle fino,
     * que en zonas lisas es casi todo ruido.
     */
    fun noiseSigma(l: LumaPlane): Float {
        val hist = IntArray(256)
        var n = 0
        for (y in 1 until l.height - 1 step 2) {
            for (x in 1 until l.width - 1 step 2) {
                val v = 4 * l[x, y] - l[x - 1, y] - l[x + 1, y] - l[x, y - 1] - l[x, y + 1]
                hist[min(255, kotlin.math.abs(v))]++
                n++
            }
        }
        if (n == 0) return 0f
        var acc = 0
        var med = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc > n / 2) {
                med = v
                break
            }
        }
        // El filtro 4·x − vecinos amplifica el ruido ≈ √20 veces; mediana/0,6745 ≈ desviación típica.
        return med / 0.6745f / 4.47f
    }
}
