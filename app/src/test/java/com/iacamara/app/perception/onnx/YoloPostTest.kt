package com.iacamara.app.perception.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloPostTest {

    private val classes = 3
    private val anchors = 400

    /** Salida de un YOLO sin NMS: [1][4 + clases][posiciones], con las posiciones dadas y el resto a cero. */
    private fun raw(vararg at: Pair<Int, FloatArray>): Array<Array<FloatArray>> {
        val p = Array(4 + classes) { FloatArray(anchors) }
        for ((i, v) in at) for (r in v.indices) p[r][i] = v[r]
        return arrayOf(p)
    }

    /** cx, cy, w, h y la puntuación de cada clase. */
    private fun anchor(cx: Float, cy: Float, w: Float, h: Float, vararg scores: Float) =
        floatArrayOf(cx, cy, w, h, *scores)

    @Test
    fun decodificaLaCajaYSeQuedaConLaMejorClase() {
        val out = YoloPost.toRows(raw(7 to anchor(100f, 100f, 40f, 60f, 0.1f, 0.9f, 0.3f)))[0]
        assertEquals(1, out.size)
        assertArrayEquals(floatArrayOf(80f, 70f, 120f, 130f, 0.9f, 1f), out[0], 1e-5f)
    }

    @Test
    fun quitaLasRepetidasDeLaMismaClaseNoLasDeOtra() {
        val out = YoloPost.toRows(
            raw(
                0 to anchor(100f, 100f, 40f, 60f, 0f, 0.9f, 0f),
                1 to anchor(102f, 101f, 40f, 60f, 0f, 0.8f, 0f), // la misma, algo movida: fuera
                2 to anchor(100f, 100f, 40f, 60f, 0f, 0f, 0.7f), // mismo sitio, otra clase: se queda
                3 to anchor(300f, 300f, 20f, 20f, 0.2f, 0f, 0f), // por debajo del umbral
                4 to anchor(200f, 100f, 40f, 60f, 0f, 0.5f, 0f), // misma clase pero en otro sitio
            ),
        )[0]
        assertEquals(listOf(0.9f, 0.7f, 0.5f), out.map { it[4] })
        assertEquals(listOf(1f, 2f, 1f), out.map { it[5] })
    }

    @Test
    fun lasDeUnYoloConNmsSeDejanIgual() {
        val withNms = arrayOf(Array(5) { FloatArray(6) })
        assertFalse(YoloPost.isRaw(withNms))
        assertSame(withNms, YoloPost.toRows(withNms))
        // Una salida con NMS de 300 detecciones tampoco se confunde con una sin NMS.
        assertFalse(YoloPost.isRaw(arrayOf(Array(300) { FloatArray(6) })))
        assertTrue(YoloPost.isRaw(raw()))
        assertEquals(0, YoloPost.toRows(raw())[0].size)
    }

    @Test
    fun comoMuchoTrescientas() {
        val many = (0 until anchors).map { i -> i to anchor((i % 20) * 30f + 10f, (i / 20) * 30f + 10f, 8f, 8f, 0.5f, 0f, 0f) }
        assertEquals(YoloPost.MAX_DET, YoloPost.toRows(raw(*many.toTypedArray()))[0].size)
    }

    @Test
    fun puntuacionesEnLaEscalaDeYolo11() {
        assertEquals(0.50f, YoloPost.yolo26Score("person", 0.46f), 1e-6f)
        assertEquals(1f, YoloPost.yolo26Score("person", 0.99f), 1e-6f)
        assertEquals(0.62f, YoloPost.yolo26Score("dog", 0.62f), 1e-6f)
        assertEquals(0.55f, YoloPost.yolo26Score("clock", 0.60f), 1e-6f)
    }

    @Test
    fun laComprobacionDelAceleradorEntiendeLaSalidaSinNms() {
        val cpu = raw(0 to anchor(100f, 100f, 40f, 60f, 0.9f, 0f, 0f), 9 to anchor(250f, 250f, 80f, 80f, 0f, 0f, 0.7f))
        val npu = raw(0 to anchor(101f, 100f, 40f, 61f, 0.88f, 0f, 0f), 9 to anchor(251f, 249f, 80f, 80f, 0f, 0f, 0.72f))
        assertTrue(OutputCheck.hasDetections(cpu))
        assertTrue(OutputCheck.sameDetections(cpu, npu))
        val wrongClass = raw(0 to anchor(100f, 100f, 40f, 60f, 0.9f, 0f, 0f), 9 to anchor(250f, 250f, 80f, 80f, 0f, 0.7f, 0f))
        assertFalse(OutputCheck.sameDetections(cpu, wrongClass))
    }
}
