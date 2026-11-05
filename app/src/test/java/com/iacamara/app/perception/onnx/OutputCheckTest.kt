package com.iacamara.app.perception.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCheckTest {

    private fun detections(vararg rows: FloatArray): Array<Array<FloatArray>> {
        val padded = Array(5) { i -> rows.getOrNull(i) ?: FloatArray(6) }
        return arrayOf(padded)
    }

    private val person = floatArrayOf(100f, 50f, 200f, 300f, 0.9f, 0f)
    private val dog = floatArrayOf(250f, 200f, 350f, 300f, 0.7f, 16f)

    @Test
    fun mismasDeteccionesAunqueCambienUnPoco() {
        val npu = detections(
            floatArrayOf(101f, 51f, 199f, 302f, 0.88f, 0f),
            floatArrayOf(252f, 199f, 349f, 301f, 0.72f, 16f),
        )
        assertTrue(OutputCheck.sameDetections(detections(person, dog), npu))
    }

    @Test
    fun claseDistintaNoVale() {
        val wrong = detections(person, floatArrayOf(250f, 200f, 350f, 300f, 0.7f, 17f))
        assertFalse(OutputCheck.sameDetections(detections(person, dog), wrong))
    }

    @Test
    fun noPuedeInventarseDetecciones() {
        val garbage = detections(
            floatArrayOf(0f, 0f, 50f, 50f, 0.9f, 3f),
            floatArrayOf(60f, 60f, 90f, 90f, 0.9f, 5f),
        )
        assertFalse(OutputCheck.sameDetections(detections(), garbage))
        // Una imagen sin nada que detectar no sirve para comprobar un acelerador.
        assertFalse(OutputCheck.sameDetections(detections(), detections()))
        assertFalse(OutputCheck.hasDetections(detections()))
        assertTrue(OutputCheck.hasDetections(detections(person)))
    }

    @Test
    fun salidasDensas() {
        val a = arrayOf(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))
        val b = arrayOf(floatArrayOf(1.01f, 2f, 2.98f), floatArrayOf(4f, 5.02f, 6f))
        val c = arrayOf(floatArrayOf(6f, 5f, 4f), floatArrayOf(3f, 2f, 1f))
        assertTrue(OutputCheck.similar(a, b, 0.99f))
        assertFalse(OutputCheck.similar(a, c, 0.99f))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), OutputCheck.flatten(a), 0f)
    }

    @Test
    fun notaMediaDeNima() {
        val p = FloatArray(10).also { it[4] = 0.5f; it[5] = 0.5f } // notas 5 y 6 → 5,5
        assertEquals(5.5f, OutputCheck.meanScore(p), 1e-5f)
        val q = FloatArray(10).also { it[4] = 0.4f; it[5] = 0.6f }
        assertTrue(OutputCheck.sameMeanScore(arrayOf(p), arrayOf(q)))
        val r = FloatArray(10).also { it[7] = 1f }
        assertFalse(OutputCheck.sameMeanScore(arrayOf(p), arrayOf(r)))
    }

    @Test
    fun probabilidadesDeClip() {
        // Dos textos: la imagen se parece más al primero.
        val text = floatArrayOf(1f, 0f, 0f, 1f)
        val p = ClipMath.probabilities(floatArrayOf(0.8f, 0.6f), text, 2, 2, 50f)
        assertEquals(1f, p.sum(), 1e-5f)
        assertTrue(p[0] > 0.99f)
    }
}
