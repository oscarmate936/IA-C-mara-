package com.iacamara.app.imaging

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.director.CaptureDirector
import com.iacamara.app.director.CaptureStrategy
import com.iacamara.app.perception.CaptureMeta
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.perception.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ImagingTest {

    private val w = 320
    private val h = 240

    /**
     * Escena sintética que no se repite, como una foto real: ruido aleatorio suavizado
     * (textura) sobre un degradado suave (zonas claras y oscuras para el HDR).
     * Un patrón periódico no sirve: varios desplazamientos encajan igual de bien.
     */
    private fun scene(): IntArray {
        val r = Random(5)
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
            val gradient = 70.0 * (x - w / 2) / w + 30 * sin(y / 40.0)
            val v = ((s / n - 128) * 3 + 128 + gradient).toInt()
            argb(v, (v * 0.85).toInt(), (v * 0.7).toInt())
        }
    }

    private fun shifted(src: IntArray, dx: Int, dy: Int): IntArray = IntArray(w * h) { i ->
        val x = (i % w + dx).coerceIn(0, w - 1)
        val y = (i / w + dy).coerceIn(0, h - 1)
        src[y * w + x]
    }

    private fun noisy(src: IntArray, sigma: Double, seed: Long): IntArray {
        val r = Random(seed)
        return IntArray(src.size) {
            val p = src[it]
            fun n(c: Int) = (c + r.nextGaussian() * sigma).toInt()
            argb(n((p shr 16) and 255), n((p shr 8) and 255), n(p and 255))
        }
    }

    private fun rmse(a: IntArray, b: IntArray, margin: Int): Double {
        var se = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val d = LumaPlane.luma(a[y * w + x]) - LumaPlane.luma(b[y * w + x])
            se += d * d
            n++
        }
        return sqrt(se / n)
    }

    @Test
    fun alineaUnDesplazamientoConocido() {
        val base = scene()
        val moved = shifted(base, 9, -6)
        val flow = Aligner.align(
            LumaPlane.fromArgb(base, w, h, 2), LumaPlane.fromArgb(moved, w, h, 2), 2, w, h, AlignMode.SSD, tiles = 4,
        )
        val s = FloatArray(2)
        flow.at(w / 2f, h / 2f, s)
        // El píxel (x, y) de la referencia está en (x − 9, y + 6) del fotograma desplazado.
        assertEquals(-9f, s[0], 1.5f)
        assertEquals(6f, s[1], 1.5f)
    }

    @Test
    fun nocheReduceElRuido() {
        val clean = scene()
        val frames = (0 until 6).map { k ->
            ArrayFrameSource(w, h, noisy(if (k == 0) clean else shifted(clean, k, -k), 12.0, k.toLong()))
        }
        val flows = frames.indices.map { k ->
            if (k == 0) Flow.zero(w, h)
            else Aligner.align(frames[0].luma(2), frames[k].luma(2), 2, w, h, AlignMode.SSD, tiles = 4)
        }
        val out = IntArray(w * h)
        Fusion.night(frames, flows, 0, 12f, out)
        val before = rmse(frames[0].pixels, clean, 12)
        val after = rmse(out, clean, 12)
        assertTrue("ruido antes $before, después $after", after < before * 0.65)
    }

    @Test
    fun eligeLaFotoNitida() {
        val sharp = scene()
        val blurred = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0
            for (d in 0 until 9) s += LumaPlane.luma(sharp[y * w + minOf(w - 1, x + d)])
            argb(s / 9, s / 9, s / 9)
        }
        val frames = listOf(blurred, blurred, sharp, blurred).map { ArrayFrameSource(w, h, it) }
        assertEquals(2, BurstPipeline.bestFrame(frames))
    }

    @Test
    fun curvaHdrNoApagaLasSombras() {
        val curve = HdrCurve(pre = 1f, white = 1f, shadowGamma = 0.9f)
        var previous = -1f
        for (v in 1..255) {
            val t = curve.tone(HdrCurve.TO_LINEAR[v])
            assertTrue("la curva debe crecer en $v", t >= previous)
            previous = t
        }
        // Un gris oscuro (sRGB 20) no debe volverse negro.
        assertTrue(HdrCurve.toSrgb(curve.tone(HdrCurve.TO_LINEAR[20])) >= 15)
    }

    @Test
    fun hdrRecuperaLucesYSombras() {
        val base = scene()
        fun exposed(gain: Float) = IntArray(base.size) {
            val p = base[it]
            fun c(v: Int) = HdrCurve.toSrgb(HdrCurve.TO_LINEAR[v] * gain)
            argb(c((p shr 16) and 255), c((p shr 8) and 255), c(p and 255))
        }
        val frames = listOf(exposed(0.25f), exposed(1f), exposed(4f)).map { ArrayFrameSource(w, h, it) }
        val merged = BurstPipeline.hdr(frames, floatArrayOf(-2f, 0f, 2f))
        val clippedAfter = merged.pixels.count { LumaPlane.luma(it) >= 250 }
        val clippedBright = frames[2].pixels.count { LumaPlane.luma(it) >= 250 }
        assertTrue(clippedAfter < clippedBright)
    }

    private fun perception(conditions: Set<Condition>, faces: Int = 0, exposureNs: Long = 10_000_000L) = Perception(
        scene = SceneType.GENERAL,
        conditions = conditions,
        subject = null,
        faces = List(faces) { NRect(0.4f, 0.3f, 0.6f, 0.5f) },
        light = null,
        meta = CaptureMeta(800, exposureNs, 1.8f, null, 0L),
        labels = emptyList(),
        frameMs = 10L,
        timestampMs = 0L,
    )

    @Test
    fun directorEligeLaTecnica() {
        assertEquals(
            CaptureStrategy.NIGHT,
            CaptureDirector.plan(PhotoMode.FOTO, perception(setOf(Condition.POCA_LUZ), exposureNs = 60_000_000L), true, false).strategy,
        )
        assertEquals(8, CaptureDirector.nightFrames(perception(emptySet(), exposureNs = 60_000_000L)))
        assertEquals(CaptureStrategy.HDR, CaptureDirector.plan(PhotoMode.FOTO, perception(setOf(Condition.CONTRALUZ)), true, false).strategy)
        assertEquals(CaptureStrategy.BEST_OF_BURST, CaptureDirector.plan(PhotoMode.FOTO, perception(emptySet(), faces = 2), true, false).strategy)
        assertEquals(CaptureStrategy.SINGLE, CaptureDirector.plan(PhotoMode.FOTO, perception(emptySet()), true, false).strategy)
        // Con flash o sin IA: foto normal.
        assertEquals(CaptureStrategy.SINGLE, CaptureDirector.plan(PhotoMode.FOTO, perception(setOf(Condition.POCA_LUZ)), true, true).strategy)
        assertEquals(CaptureStrategy.SINGLE, CaptureDirector.plan(PhotoMode.FOTO, perception(setOf(Condition.POCA_LUZ)), false, false).strategy)
        // Modos forzados.
        assertEquals(CaptureStrategy.NIGHT, CaptureDirector.plan(PhotoMode.NOCHE_IA, null, true, false).strategy)
        assertEquals(CaptureStrategy.HDR, CaptureDirector.plan(PhotoMode.HDR_IA, null, false, false).strategy)
        assertEquals(CaptureStrategy.SINGLE, CaptureDirector.plan(PhotoMode.PRO, null, true, false).strategy)
    }

    @Test
    fun ruidoEstimado() {
        val clean = IntArray(w * h) { argb(120, 120, 120) }
        val sigma = ImageStats.noiseSigma(LumaPlane.fromArgb(noisy(clean, 8.0, 3), w, h))
        assertTrue("σ estimado $sigma", abs(sigma - 6f) < 4f)
    }
}
