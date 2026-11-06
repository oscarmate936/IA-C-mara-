package com.iacamara.app.perception.onnx

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decodificación de YuNet (detector de caras de OpenCV), igual que `cv2.FaceDetectorYN` (comprobado con
 * las fotos del usuario: mismas caras y mismas cajas).
 *
 * La salida del modelo es [1][N][16]: para cada posición de las rejillas de paso 8, 16 y 32 (en ese
 * orden), la probabilidad de cara (cls), la de que haya algo (obj), la caja (dx, dy, log ancho, log alto,
 * en celdas) y 5 puntos (ojos, nariz y comisuras).
 */
object YuNetPost {

    val STRIDES = intArrayOf(8, 16, 32)

    /** Confianza mínima para que una cara sea candidata (luego [com.iacamara.app.perception.PeopleCheck] decide). */
    const val MIN_SCORE = 0.5f
    const val NMS_IOU = 0.3f

    /** Cara en píxeles de la entrada del modelo: x1, y1, x2, y2, confianza. */
    class Face(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

    fun decode(rows: Array<FloatArray>, size: Int, minScore: Float = MIN_SCORE): List<Face> {
        val found = ArrayList<Face>()
        var start = 0
        for (stride in STRIDES) {
            val cols = size / stride
            val n = cols * cols
            for (i in 0 until min(n, rows.size - start)) {
                val r = rows[start + i]
                val score = sqrt(r[0].coerceIn(0f, 1f) * r[1].coerceIn(0f, 1f))
                if (score < minScore) continue
                val cx = (i % cols + r[2]) * stride
                val cy = (i / cols + r[3]) * stride
                val w = exp(r[4]) * stride
                val h = exp(r[5]) * stride
                found += Face(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, score)
            }
            start += n
        }
        found.sortByDescending { it.score }
        val kept = ArrayList<Face>()
        for (f in found) if (kept.none { iou(it, f) > NMS_IOU }) kept += f
        return kept
    }

    /**
     * Caja con el mismo convenio que la de BlazeFace (el detector anterior), con el que están ajustados los
     * umbrales de la app (tamaño de la cara en un retrato, aire sobre la cabeza, zonas de luz de la cara):
     * BlazeFace da un cuadrado algo mayor que la caja de YuNet (1,15 veces su ancho; medido con 277 caras
     * de las fotos del usuario a distintos tamaños) y un poco más abajo (0,04 de su alto).
     * @return x1, y1, x2, y2 en píxeles de la entrada.
     */
    fun blazeBox(f: Face): FloatArray {
        val w = f.x2 - f.x1
        val h = f.y2 - f.y1
        val half = w * BLAZE_SIDE / 2f
        val cx = (f.x1 + f.x2) / 2f
        val cy = (f.y1 + f.y2) / 2f + h * BLAZE_SHIFT_Y
        return floatArrayOf(cx - half, cy - half, cx + half, cy + half)
    }

    private const val BLAZE_SIDE = 1.15f
    private const val BLAZE_SHIFT_Y = 0.04f

    /** Para comprobar los aceleradores con [OutputCheck]: filas x1, y1, x2, y2, confianza, 0 (clase única). */
    @Suppress("UNCHECKED_CAST")
    fun rows(output: Any, size: Int = 640): Array<Array<FloatArray>> {
        val rows = (output as? Array<*>)?.firstOrNull() as? Array<FloatArray> ?: return arrayOf(emptyArray())
        return arrayOf(decode(rows, size).map { floatArrayOf(it.x1, it.y1, it.x2, it.y2, it.score, 0f) }.toTypedArray())
    }

    private fun iou(a: Face, b: Face): Float {
        val w = min(a.x2, b.x2) - max(a.x1, b.x1)
        val h = min(a.y2, b.y2) - max(a.y1, b.y1)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union > 0f) inter / union else 0f
    }
}
