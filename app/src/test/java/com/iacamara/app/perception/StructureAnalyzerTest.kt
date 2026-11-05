package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

class StructureAnalyzerTest {

    private fun rgb(v: Int, g: Int = v, b: Int = v): Int =
        (0xFF shl 24) or (v.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Textura natural sin estructura (ruido suavizado). */
    private fun texture(w: Int, h: Int, seed: Long, amp: Float = 60f): FloatArray {
        val r = Random(seed)
        val n = FloatArray(w * h) { r.nextFloat() }
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0f
            var c = 0
            for (j in -1..1) for (k in -1..1) {
                val xx = x + k
                val yy = y + j
                if (xx in 0 until w && yy in 0 until h) {
                    s += n[yy * w + xx]
                    c++
                }
            }
            (s / c - 0.5f) * 2f * amp
        }
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    @Test
    fun caminoConPuntoDeFuga() {
        val w = 120
        val h = 160
        val vx = 0.5f * w
        val vy = 0.4f * h
        val tex = texture(w, h, 1)
        val img = IntArray(w * h) { i ->
            val x = i % w + 0.5f
            val y = i / w + 0.5f
            when {
                y < vy -> rgb(170, 190, 220)
                distToSegment(x, y, vx, vy, 0.08f * w, h.toFloat()) < 1.5f ||
                    distToSegment(x, y, vx, vy, 0.92f * w, h.toFloat()) < 1.5f -> rgb(235)
                else -> rgb((70 + tex[i]).toInt(), (120 + tex[i]).toInt(), (50 + tex[i]).toInt())
            }
        }
        val r = StructureAnalyzer.analyze(img, w, h)
        val vp = r.vanishingPoint
        assertNotNull(vp)
        assertEquals(0.5f, vp!!.first, 0.04f)
        assertEquals(0.4f, vp.second, 0.04f)
        assertTrue(r.leadingLines.size >= 2)
    }

    @Test
    fun fachadaSimetrica() {
        val w = 120
        val h = 160
        val tex = texture(w, h, 2, 40f)
        val r = Random(3)
        val half = IntArray(w / 2 * h)
        // Mitad izquierda: textura y "ventanas" al azar; la derecha es su reflejo.
        val windows = List(8) { intArrayOf(r.nextInt(w / 2 - 14), r.nextInt(h - 20), 6 + r.nextInt(8), 8 + r.nextInt(12)) }
        for (y in 0 until h) for (x in 0 until w / 2) {
            val inWindow = windows.any { (wx, wy, ww, wh) -> x in wx until wx + ww && y in wy until wy + wh }
            half[y * (w / 2) + x] = if (inWindow) 50 else (150 + tex[y * w + x]).toInt()
        }
        val noise = Random(4)
        // Eje desplazado a 0,45: la mitad derecha empieza antes.
        val axis = (0.45f * w).toInt()
        val img = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val d = if (x < axis) axis - 1 - x else x - axis
            val v = if (d < w / 2) half[y * (w / 2) + (w / 2 - 1 - d)] else 150
            rgb(v + (noise.nextGaussian() * 4).toInt())
        }
        val reading = StructureAnalyzer.analyze(img, w, h)
        assertTrue("simetría ${reading.symmetry}", reading.symmetry > 0.7f)
        assertEquals(0.45f, reading.symmetryX, 0.03f)
    }

    @Test
    fun reflejoEnElAgua() {
        val w = 160
        val h = 120
        val line = (0.55f * h).toInt()
        val tex = texture(w, h, 5, 50f)
        val img = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val src = if (y < line) y else (2 * line - 1 - y).coerceAtLeast(0)
            val skyline = 25 + (abs((x % 40) - 20)) // montañas en dientes de sierra
            val v = if (src < skyline) 200 else (90 + tex[src * w + x]).toInt()
            // El reflejo es más oscuro.
            if (y < line) rgb(v) else rgb((v * 0.7f).toInt() + 10)
        }
        val reading = StructureAnalyzer.analyze(img, w, h)
        assertTrue("reflejo ${reading.reflection}", reading.reflection > 0.6f)
        assertEquals(0.55f, reading.reflectionY, 0.03f)
    }

    @Test
    fun texturaSinEstructura() {
        val w = 120
        val h = 160
        val tex = texture(w, h, 7, 70f)
        val img = IntArray(w * h) { rgb((80 + tex[it]).toInt(), (130 + tex[it]).toInt(), (60 + tex[it]).toInt()) }
        val r = StructureAnalyzer.analyze(img, w, h)
        assertTrue(r.leadingLines.isEmpty())
        assertTrue("simetría ${r.symmetry}", r.symmetry < 0.3f)
        assertTrue("reflejo ${r.reflection}", r.reflection < 0.3f)
    }

    @Test
    fun elEstabilizadorEsperaVariasLecturas() {
        val s = StructureStabilizer()
        val symmetric = StructureReading(emptyList(), null, 0.5f, 0.9f, 0.5f, 0f)
        val plain = StructureReading(emptyList(), null, 0.5f, 0.1f, 0.5f, 0f)
        assertNull(s.update(symmetric).symmetryAxis) // 0,45 tras una lectura: aún no se enciende
        assertNotNull(s.update(symmetric).symmetryAxis) // 0,675: se enciende
        assertNull(s.update(plain).symmetryAxis) // 0,39 < 0,46: se apaga
        val line = Segment(0f, 1f, 0.5f, 0.4f)
        val withLine = StructureReading(listOf(line), null, 0.5f, 0f, 0.5f, 0f)
        val t = StructureStabilizer()
        assertTrue(t.update(withLine).leadingLines.isEmpty())
        assertEquals(1, t.update(withLine).leadingLines.size)
        assertEquals(1, t.update(plain).leadingLines.size) // 2 de las últimas 3
        assertTrue(t.update(plain).leadingLines.isEmpty())
    }
}
