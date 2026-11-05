package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMathTest {

    /** Gira un punto del encuadre vertical igual que Matrix.postRotate (sentido horario). */
    private fun rotate(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
        90 -> 1f - y to x
        180 -> 1f - x to 1f - y
        270 -> y to 1f - x
        else -> x to y
    }

    @Test
    fun unrotateDeshaceElGiro() {
        val points = listOf(0.1f to 0.2f, 0.8f to 0.3f, 0.5f to 0.9f)
        for (degrees in listOf(0, 90, 180, 270)) {
            for ((x, y) in points) {
                val (u, v) = rotate(x, y, degrees)
                val (bx, by) = FrameMath.unrotate(u, v, degrees)
                assertEquals("x con $degrees°", x, bx, 1e-5f)
                assertEquals("y con $degrees°", y, by, 1e-5f)
            }
        }
    }

    @Test
    fun rectanguloVuelveOrdenado() {
        val rect = FrameMath.unrotate(NRect(0.1f, 0.2f, 0.4f, 0.6f), 90)
        assert(rect.left <= rect.right && rect.top <= rect.bottom)
        assertEquals(0.4f * 0.3f, rect.area, 1e-5f)
    }

    @Test
    fun temblorSoloImportaConExposicionesLargas() {
        val dia = CaptureMeta(iso = 50, exposureNs = 1_000_000L, aperture = 1.8f, focusDistanceCm = null, timestampMs = 0L)
        val interior = CaptureMeta(iso = 800, exposureNs = 33_000_000L, aperture = 1.8f, focusDistanceCm = null, timestampMs = 0L)
        // Mano normal (0,3 rad/s): de día (1/1000 s) no se nota; en interior (1/30 s) sí.
        assertEquals(false, BlurRisk.isRisky(0.3f, dia, 1f))
        assertEquals(true, BlurRisk.isRisky(0.3f, interior, 1f))
        // Con zoom 3x el mismo temblor se nota más.
        assert(BlurRisk.blurPixels(0.1f, 4_000_000L, 3f) > BlurRisk.blurPixels(0.1f, 4_000_000L, 1f))
    }

    @Test
    fun rotacionDelModeloSegunCamara() {
        assertEquals(90, FrameMath.modelRotation(90, front = false))
        assertEquals(270, FrameMath.modelRotation(90, front = true))
        assertEquals(0, FrameMath.modelRotation(0, front = true))
        assertEquals(180, FrameMath.modelRotation(180, front = true))
    }
}
