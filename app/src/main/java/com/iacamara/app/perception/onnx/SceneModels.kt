package com.iacamara.app.perception.onnx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.iacamara.app.perception.SceneType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * CLIP pequeño (TinyCLIP, licencia MIT): compara la imagen con descripciones de cada tipo de escena
 * ("una foto de un atardecer", "una foto de comida en un plato"...). Las descripciones se codificaron
 * en el ordenador (scripts/export_ia_avanzada.py); en el móvil solo va el codificador de imagen.
 */
class ClipSceneClassifier private constructor(
    private val model: OnnxModel,
    private val text: FloatArray,
    private val rows: Int,
    private val dim: Int,
    private val scale: Float,
    private val scenes: List<SceneType>,
) {
    private val size = 224
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input: FloatBuffer = ByteBuffer.allocateDirect(3 * size * size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    @Synchronized
    fun classify(bitmap: Bitmap): Map<SceneType, Float> {
        // Recorte central cuadrado, como en el entrenamiento de CLIP.
        val side = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        canvas.drawBitmap(bitmap, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        val shifts = intArrayOf(16, 8, 0)
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val v = ((pixels[i] shr shifts[c]) and 0xFF) / 255f
                input.put((v - mean[c]) / std[c])
            }
        }
        input.rewind()

        @Suppress("UNCHECKED_CAST")
        val embedding = (model.runFirst(input, longArrayOf(1, 3, size.toLong(), size.toLong())) as Array<FloatArray>)[0]
        val p = ClipMath.probabilities(embedding, text, rows, dim, scale)
        return scenes.indices.associate { scenes[it] to p[it] }
    }

    companion object {
        fun load(context: Context, model: OnnxModel, matrixAsset: String, namesAsset: String): ClipSceneClassifier {
            val bytes = context.assets.open(matrixAsset).use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rows = buffer.int
            val dim = buffer.int
            val scale = buffer.float
            val text = FloatArray(rows * dim) { buffer.float }
            val names = context.assets.open(namesAsset).bufferedReader().readLines().filter { it.isNotBlank() }
            require(names.size == rows) { "clip_scenes: $rows filas y ${names.size} nombres" }
            return ClipSceneClassifier(model, text, rows, dim, scale, names.map { SceneType.valueOf(it.trim()) })
        }
    }
}

/**
 * NIMA (MobileNet, pesos de idealo, Apache 2.0): nota de estética de 1 a 10 aprendida de unas
 * 250.000 fotos puntuadas por personas (AVA). Sirve de apoyo a las reglas, no como juez único.
 */
class AestheticScorer(private val model: OnnxModel) {
    private val size = 224
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input: FloatBuffer = ByteBuffer.allocateDirect(3 * size * size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    @Synchronized
    fun score(bitmap: Bitmap): Float {
        // NIMA se entrenó con la foto entera reducida a 224 × 224 (sin recortar).
        canvas.drawBitmap(bitmap, null, Rect(0, 0, size, size), paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        val shifts = intArrayOf(16, 8, 0)
        for (c in 0 until 3) {
            for (i in pixels.indices) input.put(((pixels[i] shr shifts[c]) and 0xFF) / 127.5f - 1f)
        }
        input.rewind()

        @Suppress("UNCHECKED_CAST")
        val probabilities = (model.runFirst(input, longArrayOf(1, 3, size.toLong(), size.toLong())) as Array<FloatArray>)[0]
        return OutputCheck.meanScore(probabilities)
    }
}
