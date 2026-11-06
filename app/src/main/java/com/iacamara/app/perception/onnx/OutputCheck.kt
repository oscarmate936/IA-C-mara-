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
     */
    fun sameDetections(reference: Any, candidate: Any, minScore: Float = 0.3f): Boolean {
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

    /** La salida de un detector sirve para comparar si tiene al menos una detección clara. */
    fun hasDetections(output: Any, minScore: Float = 0.3f): Boolean = rows(output).any { it[4] >= minScore }

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

    @Suppress("UNCHECKED_CAST")
    private fun rows(value: Any): List<FloatArray> {
        val batch = value as? Array<*> ?: return emptyList()
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
