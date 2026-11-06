package com.iacamara.app.perception.onnx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class YuNetPostTest {

    private val size = 64 // rejillas de 8×8, 4×4 y 2×2
    private val n = 64 + 16 + 4

    /** Salida vacía de YuNet ([N][16]) con algunas posiciones rellenas. */
    private fun output(vararg at: Pair<Int, FloatArray>): Array<FloatArray> {
        val rows = Array(n) { FloatArray(16) }
        for ((i, v) in at) v.copyInto(rows[i])
        return rows
    }

    /** cls, obj, dx, dy, ancho y alto en celdas. */
    private fun anchor(cls: Float, obj: Float, dx: Float, dy: Float, w: Float, h: Float) =
        floatArrayOf(cls, obj, dx, dy, ln(w), ln(h))

    @Test
    fun decodificaComoOpenCv() {
        // Paso 8, fila 2, columna 3: centro ((3 + 0,5)·8, (2 + 0,25)·8) = (28, 18); 2×3 celdas = 16×24 px.
        val faces = YuNetPost.decode(output((2 * 8 + 3) to anchor(0.81f, 0.64f, 0.5f, 0.25f, 2f, 3f)), size)
        assertEquals(1, faces.size)
        val f = faces[0]
        assertEquals(sqrt(0.81f * 0.64f), f.score, 1e-5f)
        assertEquals(20f, f.x1, 1e-4f)
        assertEquals(6f, f.y1, 1e-4f)
        assertEquals(36f, f.x2, 1e-4f)
        assertEquals(30f, f.y2, 1e-4f)
    }

    @Test
    fun cadaRejillaConSuPaso() {
        // La primera posición de la rejilla de paso 32 (después de 64 + 16).
        val f = YuNetPost.decode(output(80 to anchor(0.9f, 0.9f, 0.5f, 0.5f, 1f, 1f)), size).single()
        assertEquals(0f, f.x1, 1e-4f)
        assertEquals(32f, f.x2, 1e-4f)
        // Paso 16, fila 1, columna 2.
        val g = YuNetPost.decode(output((64 + 4 + 2) to anchor(0.9f, 0.9f, 0f, 0f, 1f, 1f)), size).single()
        assertEquals(32f - 8f, g.x1, 1e-4f)
        assertEquals(16f - 8f, g.y1, 1e-4f)
    }

    @Test
    fun quitaRepetidasYDudosas() {
        val faces = YuNetPost.decode(
            output(
                10 to anchor(0.9f, 0.9f, 0.5f, 0.5f, 2f, 2f),
                11 to anchor(0.8f, 0.8f, -0.3f, 0.5f, 2f, 2f), // la misma cara vista desde la celda de al lado
                40 to anchor(0.3f, 0.5f, 0.5f, 0.5f, 2f, 2f), // dudosa: √0,15 ≈ 0,39
                45 to anchor(0.7f, 0.9f, 0.5f, 0.5f, 1f, 1f), // otra cara
            ),
            size,
        )
        assertEquals(2, faces.size)
        assertTrue(faces[0].score > faces[1].score)
    }

    @Test
    fun cajaComoLaDeBlazeFace() {
        val f = YuNetPost.Face(100f, 100f, 140f, 150f, 0.9f)
        val b = YuNetPost.blazeBox(f)
        // Cuadrada, 1,15 veces el ancho, centrada en x y algo más abajo.
        assertEquals(46f, b[2] - b[0], 1e-4f)
        assertEquals(46f, b[3] - b[1], 1e-4f)
        assertEquals(120f, (b[0] + b[2]) / 2f, 1e-4f)
        assertEquals(127f, (b[1] + b[3]) / 2f, 1e-4f)
    }

    @Test
    fun laComprobacionDelAceleradorCompruebaLasCaras() {
        val cpu = arrayOf(output(10 to anchor(0.9f, 0.9f, 0.5f, 0.5f, 2f, 2f)))
        val npu = arrayOf(output(10 to anchor(0.88f, 0.9f, 0.52f, 0.5f, 2f, 2.05f)))
        val wrong = arrayOf(output(30 to anchor(0.9f, 0.9f, 0.5f, 0.5f, 2f, 2f)))
        // Con el tamaño de entrada de la app (640), estas posiciones caen en la rejilla de paso 8.
        assertTrue(OutputCheck.hasFaces(cpu))
        assertTrue(OutputCheck.sameFaces(cpu, npu))
        assertFalse(OutputCheck.sameFaces(cpu, wrong))
        assertFalse(OutputCheck.hasFaces(arrayOf(output())))
    }
}
