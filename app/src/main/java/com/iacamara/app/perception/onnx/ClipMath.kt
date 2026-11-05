package com.iacamara.app.perception.onnx

import kotlin.math.exp

/** Cálculos de CLIP sin Android (para poder probarlos). */
object ClipMath {
    /** Probabilidad de cada fila de `text` (rows × dim) para la imagen `embedding` (normalizada). */
    fun probabilities(embedding: FloatArray, text: FloatArray, rows: Int, dim: Int, scale: Float): FloatArray {
        val logits = FloatArray(rows) { r ->
            var s = 0f
            for (k in 0 until dim) s += text[r * dim + k] * embedding[k]
            s * scale
        }
        val max = logits.maxOrNull() ?: 0f
        val e = FloatArray(rows) { exp(logits[it] - max) }
        val sum = e.sum()
        return FloatArray(rows) { e[it] / sum }
    }
}
