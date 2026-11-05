package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaliencyMathTest {

    @Test
    fun regionPrincipalEsLaMayor() {
        val w = 10
        val h = 10
        val v = FloatArray(w * h)
        // Mancha grande (3x3) y punto suelto.
        for (y in 2..4) for (x in 5..7) v[y * w + x] = 0.9f
        v[9 * w + 0] = 0.9f
        val (box, strength) = SaliencyMath.mainRegion(SaliencyMap(w, h, v))!!
        assertEquals(0.5f, box.left, 1e-5f)
        assertEquals(0.8f, box.right, 1e-5f)
        assertEquals(0.2f, box.top, 1e-5f)
        assertEquals(0.5f, box.bottom, 1e-5f)
        assertEquals(0.9f, strength, 1e-5f)
    }

    @Test
    fun sinNadaQueLlameLaAtencion() {
        assertNull(SaliencyMath.mainRegion(SaliencyMap(4, 4, FloatArray(16))))
    }

    @Test
    fun rejillaVerticalSinGiroEsIgual() {
        val w = SaliencyMath.PORTRAIT_W
        val h = SaliencyMath.PORTRAIT_H
        val v = FloatArray(w * h) { it.toFloat() }
        val map = SaliencyMath.toPortraitGrid(v, w, h, 0)
        assertEquals(v[5 * w + 7], map.value(7, 5), 1e-5f)
    }

    @Test
    fun rejillaGiradaColocaBienLaMancha() {
        // Imagen del modelo en horizontal (32x24) girada 90°: una mancha arriba a la derecha del modelo
        // debe aparecer arriba a la izquierda del encuadre vertical.
        val mw = 32
        val mh = 24
        val v = FloatArray(mw * mh)
        for (y in 0..3) for (x in mw - 4 until mw) v[y * mw + x] = 1f
        val map = SaliencyMath.toPortraitGrid(v, mw, mh, 90)
        assertEquals(1f, map.value(0, 0), 1e-5f)
        assertEquals(0f, map.value(SaliencyMath.PORTRAIT_W - 1, SaliencyMath.PORTRAIT_H - 1), 1e-5f)
    }

    @Test
    fun reducirYNormalizar() {
        val dense = Array(4) { y -> FloatArray(4) { x -> if (x < 2 && y < 2) 1f else 0f } }
        val grid = SaliencyMath.normalize(SaliencyMath.downsample(dense, 2, 2))
        assertEquals(1f, grid[0], 1e-5f)
        assertEquals(0f, grid[3], 1e-5f)
    }

    @Test
    fun letterbox() {
        // Fotograma vertical 480x640 en la entrada cuadrada de 512 de YOLO11.
        val lb = Letterbox.of(480, 640, 512)
        assertEquals(0.8f, lb.scale, 1e-5f)
        assertEquals(64, lb.padX)
        assertEquals(0, lb.padY)
        val (x0, y0) = lb.toNormalized(64f, 0f)
        val (x1, y1) = lb.toNormalized(448f, 512f)
        assertEquals(0f, x0, 1e-4f)
        assertEquals(0f, y0, 1e-4f)
        assertEquals(1f, x1, 1e-4f)
        assertEquals(1f, y1, 1e-4f)
    }

    @Test
    fun relojAdaptativo() {
        val clock = TaskClock(baseMs = 100, factor = 3f)
        assertTrue(clock.overdue(now = 1000, slowdown = 1) >= 1f)
        clock.record(now = 1000, durationMs = 200)
        // Tarda 200 ms → se espacia a 600 ms.
        assertEquals(600L, clock.intervalMs(1))
        assertTrue(clock.overdue(now = 1300, slowdown = 1) < 1f)
        assertTrue(clock.overdue(now = 1700, slowdown = 1) >= 1f)
        // Si el móvil se calienta, todavía más.
        assertEquals(1200L, clock.intervalMs(2))
    }

    @Test
    fun horizonte() {
        // Cielo claro arriba (40 %) y tierra oscura abajo.
        val rows = FloatArray(100) { if (it < 40) 190f else 80f }
        val y = LightAnalyzer.findHorizon(rows)
        assertNotNull(y)
        assertEquals(0.4f, y!!, 0.03f)
        // Imagen uniforme: no hay horizonte.
        assertNull(LightAnalyzer.findHorizon(FloatArray(100) { 120f }))
    }
}
