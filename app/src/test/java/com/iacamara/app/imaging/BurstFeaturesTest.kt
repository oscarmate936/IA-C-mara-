package com.iacamara.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class BurstFeaturesTest {

    private val w = 240
    private val h = 180

    /** Fondo con textura que no se repite (para poder alinear). */
    private fun background(seed: Long = 7): IntArray {
        val r = Random(seed)
        val noise = FloatArray(w * h) { r.nextFloat() * 255f }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0f
            var n = 0
            for (j in -2..2) for (k in -2..2) {
                val xx = x + k
                val yy = y + j
                if (xx in 0 until w && yy in 0 until h) {
                    s += noise[yy * w + xx]
                    n++
                }
            }
            val v = ((s / n - 128) * 3 + 128).toInt()
            argb(v, (v * 0.9).toInt(), (v * 0.8).toInt())
        }
    }

    private fun shifted(src: IntArray, dx: Int, dy: Int) = IntArray(w * h) { i ->
        src[((i / w + dy).coerceIn(0, h - 1)) * w + (i % w + dx).coerceIn(0, w - 1)]
    }

    /** Un "transeúnte": un rectángulo de color que cruza la escena. */
    private fun withWalker(src: IntArray, x0: Int): IntArray = src.copyOf().also { px ->
        for (y in 60 until 140) for (x in x0 until minOf(w, x0 + 30)) px[y * w + x] = argb(250, 20, 20)
    }

    @Test
    fun laMedianaQuitaAQuienPasa() {
        val bg = background()
        // 7 fotos con pequeños temblores; la persona aparece en un sitio distinto en cada una.
        val frames = (0 until 7).map { k ->
            val moved = shifted(bg, k % 3 - 1, (k % 2))
            ArrayFrameSource(w, h, withWalker(moved, 20 + k * 30))
        }
        val result = BurstPipeline.removeMoving(frames)
        val reds = result.pixels.count { ((it shr 16) and 0xFF) > 230 && ((it shr 8) and 0xFF) < 60 }
        assertTrue("quedan $reds píxeles de la persona", reds < 50)
    }

    @Test
    fun laLargaExposicionDejaEstelaDeLuz() {
        // Noche: fondo oscuro (con algo de textura) y unos faros que recorren la escena.
        val dark = background(5).map { p ->
            argb(((p shr 16) and 0xFF) / 8, ((p shr 8) and 0xFF) / 8, (p and 0xFF) / 8)
        }.toIntArray()
        val frames = (0 until 8).map { k ->
            val px = dark.copyOf()
            for (y in 88 until 92) for (x in 20 + k * 20 until 45 + k * 20) px[y * w + x] = argb(255, 230, 150)
            ArrayFrameSource(w, h, px)
        }
        val trail = BurstPipeline.longExposure(frames, lighten = true)
        val lit = (20 until 180).count { x -> LumaPlane.luma(trail.pixels[90 * w + x]) > 150 }
        assertTrue("estela de $lit píxeles", lit > 140)
        // El promedio (de día) en cambio la diluye.
        val mean = BurstPipeline.longExposure(frames, lighten = false)
        assertTrue(LumaPlane.luma(mean.pixels[90 * w + 100]) < 120)
    }

    @Test
    fun elModoNocheNoSaleRosa() {
        // Fotos de noche como las de la cámara: muy oscuras, con ruido y negros con exceso de rojo y azul.
        // Se compara con la misma escena sin ese defecto del sensor: las sombras tienen que quedar igual
        // (la escena es algo cálida y eso debe conservarse; el rosa, no).
        val bg = background(13)
        fun shoot(sensorTint: Boolean): IntArray {
            val r = Random(21)
            val frames = (0 until 6).map { k ->
                val moved = shifted(bg, k % 2, k % 3 - 1)
                val px = IntArray(w * h) { i ->
                    val p = moved[i]
                    fun dark(v: Int, offset: Int) = (v * 0.12 + (if (sensorTint) offset else 0) + r.nextGaussian() * 4).toInt()
                    argb(dark((p shr 16) and 0xFF, 9), dark((p shr 8) and 0xFF, 0), dark(p and 0xFF, 8))
                }
                ArrayFrameSource(w, h, px)
            }
            return BurstPipeline.night(frames).pixels
        }
        fun shadows(px: IntArray): Pair<Double, Double> {
            var rg = 0.0
            var bg2 = 0.0
            var n = 0
            for (p in px) {
                if (LumaPlane.luma(p) < 60) {
                    rg += ((p shr 16) and 0xFF) - ((p shr 8) and 0xFF)
                    bg2 += (p and 0xFF) - ((p shr 8) and 0xFF)
                    n++
                }
            }
            return rg / n to bg2 / n
        }
        val (rgTrue, bgTrue) = shadows(shoot(false))
        val (rg, bg2) = shadows(shoot(true))
        assertTrue("sombras R−G $rg (sin defecto $rgTrue), B−G $bg2 (sin defecto $bgTrue)",
            abs(rg - rgTrue) < 3.5 && abs(bg2 - bgTrue) < 3.5)
        // Rosa sería azul por encima del verde.
        assertTrue("B−G $bg2", bg2 < 0)
    }

    @Test
    fun pegarUnaCaraAlineada() {
        val bg = background(11)
        // Base: "ojos cerrados" = mancha oscura en la cara. Donante: la cara buena, desplazada 6, 4 px.
        val base = bg.copyOf()
        for (y in 70 until 80) for (x in 100 until 140) base[y * w + x] = argb(10, 10, 10)
        val donor = shifted(bg, 6, 4)
        val region = PixelRect(80, 50, 160, 110)
        val (dx, dy) = FaceMerge.paste(base, w, h, ArrayFrameSource(w, h, donor), region)
        assertEquals(-6, dx)
        assertEquals(-4, dy)
        // En el centro de la cara ya se ve la cara buena (como el fondo original).
        var err = 0
        for (y in 72 until 78) for (x in 110 until 130) err += abs(LumaPlane.luma(base[y * w + x]) - LumaPlane.luma(bg[y * w + x]))
        assertTrue("error medio ${err / 120}", err / 120 < 12)
        // Fuera de la elipse no cambia nada.
        assertEquals(bg[10 * w + 10], base[10 * w + 10])
    }
}
