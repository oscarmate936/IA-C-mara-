package com.iacamara.app.perception.onnx

import com.iacamara.app.perception.SceneRules
import kotlin.math.max
import kotlin.math.min

/**
 * Selección de detecciones (NMS) para los YOLO que se exportan sin ella (YOLO26n): la red da, para cada
 * una de sus N posiciones, la caja (centro x, centro y, ancho, alto, en píxeles de la entrada) y la
 * puntuación de cada clase, en una salida [1][4 + clases][N].
 *
 * Se hace como Ultralytics (comprobado con sus fotos: mismo resultado): clase con más puntuación por
 * posición, umbral 0,25, NMS por clase con IoU 0,7 y como mucho 300 detecciones. Así la red que va en el
 * NPU es solo la red (sin las operaciones dinámicas del NMS, que el NPU no siempre ejecuta bien), y el
 * resultado tiene el mismo formato que los YOLO con NMS incluido: [1][n][6] = x1, y1, x2, y2, puntuación, clase.
 */
object YoloPost {

    const val CONF = 0.25f
    const val IOU = 0.7f
    const val MAX_DET = 300

    /** La salida es la de la red sin NMS ([1][4 + clases][N], con N mucho mayor que el número de filas). */
    fun isRaw(output: Any): Boolean {
        val batch = output as? Array<*> ?: return false
        val first = batch.firstOrNull() as? Array<*> ?: return false
        val rows = first.size
        val cols = (first.firstOrNull() as? FloatArray)?.size ?: return false
        return rows in 5..(cols - 1) && cols > MAX_DET
    }

    /** Convierte cualquier salida de YOLO al formato con NMS ([1][n][6]); si ya lo tiene, la deja igual. */
    @Suppress("UNCHECKED_CAST")
    fun toRows(output: Any, conf: Float = CONF, iou: Float = IOU): Array<Array<FloatArray>> {
        if (!isRaw(output)) return output as Array<Array<FloatArray>>
        return arrayOf(select((output as Array<Array<FloatArray>>)[0], conf, iou))
    }

    /** @param p [4 + clases][N]. */
    fun select(p: Array<FloatArray>, conf: Float = CONF, iou: Float = IOU): Array<FloatArray> {
        val n = p[0].size
        val classes = p.size - 4
        val bestScore = FloatArray(n)
        val bestClass = IntArray(n)
        for (c in 0 until classes) {
            val row = p[4 + c]
            for (i in 0 until n) {
                if (row[i] > bestScore[i]) {
                    bestScore[i] = row[i]
                    bestClass[i] = c
                }
            }
        }
        val candidates = (0 until n).filter { bestScore[it] >= conf }.sortedByDescending { bestScore[it] }
        val kept = ArrayList<FloatArray>()
        for (i in candidates) {
            val cx = p[0][i]
            val cy = p[1][i]
            val w = p[2][i]
            val h = p[3][i]
            val box = floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, bestScore[i], bestClass[i].toFloat())
            val duplicate = kept.any { k -> k[5] == box[5] && iouOf(k, box) > iou }
            if (!duplicate) kept += box
            if (kept.size >= MAX_DET) break
        }
        return kept.toTypedArray()
    }

    /**
     * YOLO26n puntúa distinto que YOLO11, con el que están ajustados los umbrales de la app (personas,
     * objetos, escenas). Medido con 346 personas emparejadas en las mismas fotos (también de noche): da
     * 0,04 menos a las personas (mediana); a los animales, lo mismo; y ve más objetos dudosos de otras
     * clases (un reloj en una pared lisa, un camión en un perro). Se pasa a la escala de YOLO11 para que
     * todos los umbrales sigan valiendo con los dos detectores.
     */
    fun yolo26Score(label: String, score: Float): Float = when (label) {
        "person" -> min(1f, score + 0.04f)
        in SceneRules.ANIMALS -> score
        else -> max(0f, score - 0.05f)
    }

    private fun iouOf(a: FloatArray, b: FloatArray): Float {
        val w = min(a[2], b[2]) - max(a[0], b[0])
        val h = min(a[3], b[3]) - max(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }
}
