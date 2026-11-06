package com.iacamara.app.perception.onnx

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Comprueba que un acelerador (NPU o GPU) da el mismo resultado que el procesador normal con la
 * misma imagen. Si no coincide, ese acelerador no se usa con ese modelo: más vale lento que mal.
 */
object OutputCheck {

    /**
     * Detectores con NMS incluido: salida [1][N][6+] = x1, y1, x2, y2, puntuación, clase.
     * Cada detección clara del procesador normal tiene que aparecer en el acelerador (misma clase,
     * casi la misma caja y puntuación), y el acelerador no puede inventarse detecciones claras.
     * Solo cuentan las claras (≥ [CLEAR]): una dudosa, cerca del umbral del detector, aparece o desaparece
     * con cualquier diferencia mínima de precisión (con niebla, de noche) y no dice nada del acelerador.
     */
    fun sameDetections(reference: Any, candidate: Any, minScore: Float = CLEAR): Boolean {
        val ref = rows(reference).filter { it[4] >= minScore }
        val out = rows(candidate)
        val strongOut = out.count { it[4] >= minScore + 0.15f }
        // Sin detecciones de referencia no se puede comprobar nada: no se da por bueno.
        if (ref.isEmpty()) return false
        var matched = 0
        for (r in ref) {
            val hit = out.any { o ->
                o[5].toInt() == r[5].toInt() && abs(o[4] - r[4]) < 0.15f && iou(o, r) >= 0.6f
            }
            if (hit) matched++
        }
        return matched >= (ref.size * 0.8f) && strongOut <= ref.size + 2
    }

    /**
     * Qué difiere entre dos salidas de un detector, para el diagnóstico: las detecciones claras de cada
     * uno (clase:puntuación) y, para cada una del procesador, la más parecida del acelerador (IoU).
     * Así se distingue un acelerador que no ve nada, que cambia las clases o que mueve las cajas.
     */
    fun explainDetections(reference: Any, candidate: Any): String {
        val ref = rows(reference).filter { it[4] >= 0.3f }.sortedByDescending { it[4] }.take(4)
        val out = rows(candidate).filter { it[4] >= 0.3f }.sortedByDescending { it[4] }.take(4)
        fun fmt(r: FloatArray) = "${r[5].toInt()}:${"%.2f".format(r[4])}"
        val pairs = ref.joinToString(", ") { r ->
            val best = rows(candidate).maxByOrNull { iou(it, r) }
            if (best == null || iou(best, r) <= 0f) "${fmt(r)}→nada" else "${fmt(r)}→${fmt(best)} IoU ${"%.2f".format(iou(best, r))}"
        }
        return "CPU [${ref.joinToString(", ") { fmt(it) }}] · acelerador [${out.joinToString(", ") { fmt(it) }}] · $pairs"
    }

    /** Coseno entre dos salidas densas, para el diagnóstico ("NaN" = el acelerador se desbordó). */
    fun explainSimilarity(reference: Any, candidate: Any): String {
        val a = flatten(reference)
        val b = flatten(candidate)
        if (a.size != b.size) return "tamaños distintos (${a.size} y ${b.size})"
        return "coseno ${"%.3f".format(cosine(a, b))}"
    }

    /** Nota media de NIMA en cada uno, para el diagnóstico. */
    fun explainMeanScore(reference: Any, candidate: Any): String =
        "nota ${"%.2f".format(meanScore(flatten(reference)))} en el procesador y ${"%.2f".format(meanScore(flatten(candidate)))} en el acelerador"

    /** La salida de un detector sirve para comparar si tiene al menos una detección clara. */
    fun hasDetections(output: Any, minScore: Float = CLEAR): Boolean = rows(output).any { it[4] >= minScore }

    /** Puntuación a partir de la cual una detección es clara (para comparar aceleradores). */
    const val CLEAR = 0.45f

    /** YuNet (caras): las mismas caras claras en el acelerador que en el procesador. */
    fun sameFaces(reference: Any, candidate: Any): Boolean =
        sameDetections(YuNetPost.rows(reference), YuNetPost.rows(candidate), FACE_CLEAR)

    fun hasFaces(output: Any): Boolean = hasDetections(YuNetPost.rows(output), FACE_CLEAR)

    fun explainFaces(reference: Any, candidate: Any): String =
        explainDetections(YuNetPost.rows(reference), YuNetPost.rows(candidate))

    /** Confianza de YuNet a partir de la cual una cara es clara (para comparar aceleradores). */
    const val FACE_CLEAR = 0.6f

    /**
     * Comprobación de respaldo para un YOLO sin NMS ([1][4 + clases][N]) cuando no aparece ninguna imagen con
     * algo claro que detectar (una calle vacía): la salida entera de la red tiene que ser la misma. Las cajas
     * (en píxeles) y las puntuaciones (0–1) se comparan por separado: si no, las cajas pesarían tanto que un
     * acelerador que estropeara solo las puntuaciones pasaría la prueba.
     */
    fun sameRawDetections(reference: Any, candidate: Any, minCosine: Float = DENSE_MIN): Boolean {
        if (!YoloPost.isRaw(reference) || !YoloPost.isRaw(candidate)) return false
        val a = rowsOf(reference)
        val b = rowsOf(candidate)
        if (a.size != b.size) return false
        return cosine(join(a, 0, 4), join(b, 0, 4)) >= minCosine && cosine(join(a, 4, a.size), join(b, 4, b.size)) >= minCosine
    }

    /** Lo mismo para YuNet ([1][N][16]): puntuaciones (cls, obj) por un lado y caja y puntos por otro. */
    fun sameRawFaces(reference: Any, candidate: Any, minCosine: Float = DENSE_MIN): Boolean {
        val a = rowsOf(reference)
        val b = rowsOf(candidate)
        if (a.isEmpty() || a.size != b.size || a[0].size != 16 || b[0].size != 16) return false
        fun cols(r: List<FloatArray>, from: Int, to: Int) = FloatArray(r.size * (to - from)) { k -> r[k / (to - from)][from + k % (to - from)] }
        return cosine(cols(a, 0, 2), cols(b, 0, 2)) >= minCosine && cosine(cols(a, 2, 16), cols(b, 2, 16)) >= minCosine
    }

    /** Similitud mínima de las comprobaciones de respaldo. */
    const val DENSE_MIN = 0.98f

    private fun rowsOf(value: Any): List<FloatArray> =
        ((value as? Array<*>)?.firstOrNull() as? Array<*>)?.filterIsInstance<FloatArray>().orEmpty()

    private fun join(rows: List<FloatArray>, from: Int, to: Int): FloatArray {
        val n = rows.firstOrNull()?.size ?: 0
        val out = FloatArray((to - from) * n)
        for (r in from until to) rows[r].copyInto(out, (r - from) * n)
        return out
    }

    /** Salidas densas (mapas, vectores): misma "forma" según el coseno entre ambas. */
    fun similar(reference: Any, candidate: Any, minCosine: Float): Boolean {
        val a = flatten(reference)
        val b = flatten(candidate)
        if (a.size != b.size || a.isEmpty()) return false
        return cosine(a, b) >= minCosine
    }

    /**
     * Un mapa de saliencia sirve para comparar si algo destaca (algún valor ≥ `minPeak`). Si todo es
     * casi cero (una pared lisa, la mano tapando la cámara), el coseno solo compara ruido.
     */
    fun hasSalience(output: Any, minPeak: Float = 0.2f): Boolean = flatten(output).any { it >= minPeak }

    /** Distribución de notas (NIMA): la nota media no puede moverse más de `maxDiff`. */
    fun sameMeanScore(reference: Any, candidate: Any, maxDiff: Float = 0.15f): Boolean {
        val a = flatten(reference)
        val b = flatten(candidate)
        if (a.size != b.size || a.isEmpty()) return false
        return abs(meanScore(a) - meanScore(b)) <= maxDiff
    }

    /** Nota media 1..N de una distribución de probabilidades sobre N notas. */
    fun meanScore(p: FloatArray): Float {
        var s = 0f
        var total = 0f
        for (i in p.indices) {
            s += (i + 1) * p[i]
            total += p[i]
        }
        return if (total > 0f) s / total else 0f
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa <= 0.0 || bb <= 0.0) return if (aa == bb) 1f else 0f
        return (ab / sqrt(aa * bb)).toFloat()
    }

    /** Aplana arrays de Java de varias dimensiones (lo que devuelve ONNX Runtime) a un FloatArray. */
    fun flatten(value: Any): FloatArray {
        val out = ArrayList<Float>()
        fun walk(v: Any?) {
            when (v) {
                is FloatArray -> v.forEach { out += it }
                is Array<*> -> v.forEach { walk(it) }
                is Float -> out += v
                else -> Unit
            }
        }
        walk(value)
        return out.toFloatArray()
    }

    /** Detecciones x1, y1, x2, y2, puntuación, clase (las de un YOLO sin NMS se seleccionan antes). */
    private fun rows(value: Any): List<FloatArray> {
        val batch = (if (YoloPost.isRaw(value)) YoloPost.toRows(value) else value) as? Array<*> ?: return emptyList()
        val first = batch.firstOrNull() as? Array<*> ?: return emptyList()
        return first.filterIsInstance<FloatArray>().filter { it.size >= 6 }
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val w = min(a[2], b[2]) - max(a[0], b[0])
        val h = min(a[3], b[3]) - max(a[1], b[1])
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }
}
