package com.iacamara.app.perception.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.iacamara.app.perception.DetectedObject
import com.iacamara.app.perception.Letterbox
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SaliencyMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Un modelo ONNX cargado desde los assets del APK (ONNX Runtime, sin internet). */
class OnnxModel(context: Context, assetPath: String, threads: Int = 4) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        }
        session = env.createSession(bytes, options)
        inputName = session.inputNames.first()
    }

    /** Ejecuta el modelo y devuelve su primera salida (arrays de Java de varias dimensiones). */
    fun runFirst(data: FloatBuffer, shape: LongArray): Any {
        OnnxTensor.createTensor(env, data, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                return result.get(0).value
            }
        }
    }

    override fun close() {
        session.close()
    }
}

private fun directFloats(count: Int): FloatBuffer =
    ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

/**
 * Detector tipo YOLO exportado con NMS incluido: salida [1, N, 6+] = x1, y1, x2, y2, puntuación, clase.
 * Sirve para YOLO11 (80 clases COCO) y para YOLOE (vocabulario propio).
 */
class YoloDetector(private val model: OnnxModel, private val size: Int, private val labels: List<String>) {
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input = directFloats(3 * size * size)

    fun detect(bitmap: Bitmap, minScore: Float): List<DetectedObject> {
        val box = Letterbox.of(bitmap.width, bitmap.height, size)
        val nw = Math.round(bitmap.width * box.scale)
        val nh = Math.round(bitmap.height * box.scale)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(bitmap, null, Rect(box.padX, box.padY, box.padX + nw, box.padY + nh), paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        fillChw(pixels, input, size * size)

        @Suppress("UNCHECKED_CAST")
        val rows = (model.runFirst(input, longArrayOf(1, 3, size.toLong(), size.toLong())) as Array<Array<FloatArray>>)[0]
        val result = ArrayList<DetectedObject>()
        for (r in rows) {
            val score = r[4]
            if (score < minScore) continue
            val cls = r[5].toInt()
            val label = labels.getOrNull(cls) ?: continue
            val (x1, y1) = box.toNormalized(r[0], r[1])
            val (x2, y2) = box.toNormalized(r[2], r[3])
            result += DetectedObject(label, NRect.of(x1, y1, x2, y2).clamped(), score)
        }
        return result
    }

    private fun fillChw(src: IntArray, dst: FloatBuffer, n: Int) {
        dst.rewind()
        for (shift in intArrayOf(16, 8, 0)) {
            for (i in 0 until n) dst.put(((src[i] shr shift) and 0xFF) / 255f)
        }
        dst.rewind()
    }
}

/** U²-Net pequeño: mapa de saliencia ("qué llama la atención") de la imagen. */
class SaliencyDetector(private val model: OnnxModel) {
    private val size = 320
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(size * size)
    private val input = directFloats(3 * size * size)
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Rejilla gw×gh (0–1) con la misma orientación que el bitmap de entrada. */
    fun saliency(bitmap: Bitmap, gw: Int, gh: Int): FloatArray {
        canvas.drawBitmap(bitmap, null, Rect(0, 0, size, size), paint)
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
        val dense = (model.runFirst(input, longArrayOf(1, 3, size.toLong(), size.toLong())) as Array<Array<Array<FloatArray>>>)[0][0]
        return SaliencyMath.normalize(SaliencyMath.downsample(dense, gw, gh))
    }
}
