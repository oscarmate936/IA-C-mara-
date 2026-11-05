package com.iacamara.app.imaging

import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class EnhanceTest {

    private val w = 400
    private val h = 300

    /** Escena con textura, algo oscura y con una dominante azul. */
    private fun dullScene(): IntArray {
        val r = Random(3)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = 70 + (25 * sin(x / 9.0) * cos(y / 11.0)).toInt() + r.nextInt(10)
            argb(v - 8, v, v + 18)
        }
    }

    private fun medianLuma(px: IntArray): Int = LumaPlane.fromArgb(px, w, h).median()

    @Test
    fun laMejoraAclaraUnaFotoApagadaSinQuemar() {
        val src = dullScene()
        val out = AutoEnhance.enhance(src, w, h, ShotContext(SceneType.GENERAL))
        assertEquals(src.size, out.size)
        assertTrue("mediana ${medianLuma(src)} → ${medianLuma(out)}", medianLuma(out) > medianLuma(src) + 10)
        val burnt = out.count { LumaPlane.luma(it) >= 254 }
        assertTrue("quemados $burnt", burnt < out.size / 100)
    }

    @Test
    fun corrigeLaDominanteDeColor() {
        val src = dullScene()
        val out = AutoEnhance.enhance(src, w, h, ShotContext(SceneType.INTERIOR))
        fun blueExcess(px: IntArray) = px.map { (it and 0xFF) - ((it shr 16) and 0xFF) }.average()
        assertTrue("azul ${blueExcess(src)} → ${blueExcess(out)}", blueExcess(out) < blueExcess(src))
    }

    @Test
    fun elAtardecerConservaSusTonosCalidos() {
        val warm = IntArray(w * h) { i -> argb(200, 120 + (i % 7), 60) }
        val out = AutoEnhance.enhance(warm, w, h, ShotContext(SceneType.ATARDECER))
        val r = out.map { (it shr 16) and 0xFF }.average()
        val b = out.map { it and 0xFF }.average()
        assertTrue("rojo $r, azul $b", r - b > 120)
    }

    @Test
    fun laPielSeSaturaMenos() {
        // Mitad piel (tono cálido suave) y mitad un verde apagado: el verde gana más saturación.
        val px = IntArray(w * h) { i -> if (i % w < w / 2) argb(210, 160, 130) else argb(110, 140, 110) }
        val out = AutoEnhance.enhance(px, w, h, ShotContext(SceneType.RETRATO))
        fun sat(p: Int): Float {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        }
        val skinGain = sat(out[h / 2 * w + 10]) - sat(px[h / 2 * w + 10])
        val greenGain = sat(out[h / 2 * w + w - 10]) - sat(px[h / 2 * w + w - 10])
        assertTrue("piel $skinGain, verde $greenGain", greenGain > skinGain)
    }

    @Test
    fun enderezarDejaVerticalUnaLineaInclinada() {
        // Móvil girado 5° a la izquierda: en la foto, una vertical aparece inclinada 5° a la derecha.
        val a = Math.toRadians(5.0)
        val top = PhotoGeometry.forward(100 * sin(a), -100 * cos(a), a, 0.0, 1000.0)
        val bottom = PhotoGeometry.forward(-100 * sin(a), 100 * cos(a), a, 0.0, 1000.0)
        assertEquals(top.first, bottom.first, 1e-6)
    }

    @Test
    fun laPerspectivaPoneParalelasLasVerticales() {
        // Cámara mirando 20° hacia arriba: dos verticales de un edificio convergen hacia arriba.
        val f = 1000.0
        val theta = Math.toRadians(20.0)
        fun seen(x: Double, y: Double, z: Double): Pair<Double, Double> {
            // Rayo de la cámara nivelada → cámara inclinada hacia arriba (giro de −theta alrededor del eje x).
            val c = cos(-theta)
            val s = sin(-theta)
            val yy = y * c - z * s
            val zz = y * s + z * c
            return f * x / zz to f * yy / zz
        }
        for (x in listOf(-3.0, 3.0)) {
            val low = seen(x, 1.0, 10.0)
            val high = seen(x, -6.0, 10.0)
            assertTrue("convergen antes", abs(low.first - high.first) > 5)
            val a = PhotoGeometry.forward(low.first, low.second, 0.0, theta, f)
            val b = PhotoGeometry.forward(high.first, high.second, 0.0, theta, f)
            assertEquals(a.first, b.first, 1e-6)
        }
    }

    @Test
    fun elPlanNoHaceNadaSiNoHaceFalta() {
        assertNull(PhotoGeometry.plan(w, h, rollDeg = 0.1f))
        val p = PhotoGeometry.plan(w, h, rollDeg = 3f)
        assertNotNull(p)
        // Al enderezar se recorta un poco para no dejar esquinas vacías, con la misma proporción.
        assertTrue(p!!.width < w && p.height < h)
        assertEquals(w.toFloat() / h, p.width.toFloat() / p.height, 0.02f)
    }

    @Test
    fun elRecorteAcercaElSujetoALaGuia() {
        // Sujeto en el centro; la guía lo quería en el tercio izquierdo.
        val hint = CropHint(0.5f, 0.5f, 1f / 3f, 0.5f)
        val p = PhotoGeometry.plan(w, h, rollDeg = 0f, crop = hint)
        assertNotNull(p)
        val src = DoubleArray(2)
        // ¿Dónde cae el sujeto en la foto recortada?
        var best = Double.MAX_VALUE
        var bx = 0
        for (x in 0 until p!!.width) {
            p.sourceOf(x + 0.5, p.height / 2.0, src)
            val d = abs(src[0] - w / 2.0)
            if (d < best) {
                best = d
                bx = x
            }
        }
        val before = abs(0.5 - 1.0 / 3)
        val after = abs(bx.toDouble() / p.width - 1.0 / 3)
        assertTrue("antes $before, después $after", after < before - 0.03)
        assertTrue("no recorta demasiado", p.width >= w * 0.84)
    }

    @Test
    fun aplicarElPlanDaUnaImagenDelTamanoPrevisto() {
        val src = dullScene()
        val plan = PhotoGeometry.plan(w, h, rollDeg = 4f, pitchDeg = 15f, keystone = 0.7f, focalPx = 450f)!!
        val out = PhotoGeometry.apply(src, w, h, plan)
        assertEquals(plan.width * plan.height, out.size)
        assertTrue(plan.description.contains("perspectiva"))
    }

    @Test
    fun lasCarasRecibenMenosContrasteLocal() {
        val src = dullScene()
        val face = NRect(0.4f, 0.3f, 0.6f, 0.6f)
        val withFace = AutoEnhance.enhance(src, w, h, ShotContext(SceneType.RETRATO, faces = listOf(face)))
        val without = AutoEnhance.enhance(src, w, h, ShotContext(SceneType.RETRATO))
        fun localVar(px: IntArray): Double {
            var s = 0.0
            for (y in 110 until 160) for (x in 180 until 220) {
                val d = LumaPlane.luma(px[y * w + x]) - LumaPlane.luma(px[y * w + x + 1])
                s += d * d
            }
            return s
        }
        assertTrue(localVar(withFace) <= localVar(without))
    }
}
