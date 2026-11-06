package com.iacamara.app.imaging

import com.iacamara.app.perception.SceneType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Errores de color "del tipo del tono rosa": el procesado cambia el color de la escena sin querer.
 * Cada prueba simula lo que entrega la cámara en una situación concreta (sensor, luz, exposición).
 */
class ColorFidelityTest {

    private val w = 240
    private val h = 180

    /** Textura que no se repite (0..1), para poder alinear y para que haya detalle. */
    private fun texture(seed: Long, lo: Float = 0.3f, hi: Float = 1f): FloatArray {
        val r = Random(seed)
        val noise = FloatArray(w * h) { r.nextFloat() }
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0f
            var n = 0
            for (j in -3..3) for (k in -3..3) {
                val xx = x + k
                val yy = y + j
                if (xx in 0 until w && yy in 0 until h) {
                    s += noise[yy * w + xx]
                    n++
                }
            }
            val v = ((s / n - 0.5f) * 4f + 0.5f).coerceIn(0f, 1f)
            lo + (hi - lo) * v
        }
    }

    private fun shifted(src: IntArray, dx: Int, dy: Int) = IntArray(w * h) { i ->
        src[((i / w + dy).coerceIn(0, h - 1)) * w + (i % w + dx).coerceIn(0, w - 1)]
    }

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    private fun hue(p: Int): Float {
        val rr = r(p).toFloat()
        val gg = g(p).toFloat()
        val bb = b(p).toFloat()
        return AutoEnhance.hueOf(rr, gg, bb, max(rr, max(gg, bb)), min(rr, min(gg, bb)))
    }

    /** Media de (R−G, B−G) de los píxeles con luminancia en `range`. */
    private fun cast(px: IntArray, range: IntRange): Pair<Double, Double> {
        var rg = 0.0
        var bg = 0.0
        var n = 0
        for (p in px) if (LumaPlane.luma(p) in range) {
            rg += r(p) - g(p)
            bg += b(p) - g(p)
            n++
        }
        assertTrue("sin píxeles en $range", n > 50)
        return rg / n to bg / n
    }

    @Test
    fun nocheConFarolasConservaLaLuzCalidaSinSalirRosa() {
        // Calle de noche con farolas de sodio (luz naranja) y el negro del sensor con exceso de rojo y azul.
        val t = texture(31, lo = 0.05f)
        val rnd = Random(5)
        val scene = IntArray(w * h) { i ->
            val v = t[i] * 0.2f * 255f
            argb((v * 1.0f).toInt(), (v * 0.72f).toInt(), (v * 0.42f).toInt())
        }
        val frames = (0 until 6).map { k ->
            val moved = shifted(scene, k % 2, k % 3 - 1)
            ArrayFrameSource(w, h, IntArray(w * h) { i ->
                val p = moved[i]
                fun c(v: Int, off: Int) = (v + off + rnd.nextGaussian() * 3).toInt()
                argb(c(r(p), 7), c(g(p), 0), c(b(p), 7))
            })
        }
        val out = BurstPipeline.night(frames).pixels
        val (rg, bg) = cast(out, 20..70)
        assertTrue("las sombras deben seguir cálidas: R−G $rg", rg > 8)
        assertTrue("sin rosa: B−G $bg", bg < 0)
        // Los negros (lo más oscuro) no pueden quedar magenta.
        val (rg0, bg0) = cast(out, 3..14)
        assertTrue("negros: R−G $rg0, B−G $bg0", bg0 < 2.5)
    }

    @Test
    fun aclararDeNocheNoCambiaElTonoDeUnNeon() {
        // Foto oscura con un letrero de neón naranja: al aclarar, si el rojo se recorta, vira a amarillo.
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 80..160 && y in 60..90) argb(240, 120, 20) else argb(12, 11, 12)
        }
        val before = hue(px[75 * w + 120])
        ToneCurve.autoBrighten(px, targetMean = 0.24f, minGamma = 0.65f)
        val after = hue(px[75 * w + 120])
        assertTrue("tono del neón $before → $after", abs(after - before) < 2f)
    }

    @Test
    fun zoomDeDiaNoApagaElVerdeDeLasHojasEnSombra() {
        // Zoom digital de día: arriba cielo claro, abajo hojas oscuras (verde de verdad, no del sensor).
        val t = texture(17)
        val scene = IntArray(w * h) { i ->
            val y = i / w
            if (y < h / 3) {
                argb(150, 190, 235)
            } else {
                val v = t[i]
                argb((v * 22).toInt(), (v * 48).toInt(), (v * 16).toInt())
            }
        }
        val frames = (0 until 4).map { k -> ArrayFrameSource(w, h, shifted(scene, k % 2, 0)) }
        val out = BurstPipeline.zoomDetail(frames).pixels
        fun greenness(px: IntArray): Double {
            var s = 0.0
            var n = 0
            for (i in px.indices) if (i / w > h / 2 && i % w in 20 until w - 20) {
                s += g(px[i]) - (r(px[i]) + b(px[i])) / 2.0
                n++
            }
            return s / n
        }
        assertTrue("verde ${greenness(scene)} → ${greenness(out)}", greenness(out) > greenness(scene) - 2)
    }

    /** Escena en luz lineal → foto con una exposición (EV), como la entrega la cámara (canales recortados). */
    private fun exposed(linear: Array<FloatArray>, ev: Float) = IntArray(w * h) { i ->
        val gain = 2f.pow(ev)
        fun c(v: Float) = HdrCurve.toSrgb(v * gain)
        argb(c(linear[0][i]), c(linear[1][i]), c(linear[2][i]))
    }

    @Test
    fun hdrConservaElColorDelCieloDelAtardecer() {
        // Atardecer: cielo naranja muy brillante (el rojo se quema en la foto normal) y suelo oscuro.
        val t = texture(23, lo = 0.6f)
        val lin = Array(3) { FloatArray(w * h) }
        for (i in 0 until w * h) {
            val sky = i / w < h / 2
            val v = t[i]
            if (sky) {
                lin[0][i] = 1.9f * v; lin[1][i] = 0.62f * v; lin[2][i] = 0.12f * v
            } else {
                lin[0][i] = 0.05f * v; lin[1][i] = 0.04f * v; lin[2][i] = 0.03f * v
            }
        }
        val evs = floatArrayOf(-2f, 0f, 2f)
        val frames = evs.map { ArrayFrameSource(w, h, exposed(lin, it)) }
        val out = BurstPipeline.hdr(frames, evs).pixels
        // Tono de verdad del cielo (sin recortar): el de la foto oscura, donde nada se quema.
        val dark = frames[0].pixels
        var diff = 0.0
        var n = 0
        for (i in 0 until w * (h / 2 - 4)) {
            if (i % w !in 10 until w - 10) continue
            diff += abs(hue(out[i]) - hue(dark[i]))
            n++
        }
        assertTrue("el cielo cambia de tono ${diff / n}°", diff / n < 3.0)
    }

    @Test
    fun hdrNoVuelveCianElAzulIntenso() {
        // Cielo azul intenso y oscuro: al levantar las sombras, el azul no puede saturarse y virar.
        val t = texture(29, lo = 0.7f)
        val lin = Array(3) { FloatArray(w * h) }
        for (i in 0 until w * h) {
            val v = t[i]
            if (i % w < w / 2) {
                lin[0][i] = 0.010f * v; lin[1][i] = 0.045f * v; lin[2][i] = 0.42f * v
            } else {
                lin[0][i] = 0.6f * v; lin[1][i] = 0.6f * v; lin[2][i] = 0.6f * v
            }
        }
        val evs = floatArrayOf(-2f, 0f, 2f)
        val frames = evs.map { ArrayFrameSource(w, h, exposed(lin, it)) }
        val out = BurstPipeline.hdr(frames, evs).pixels
        val ref = frames[1].pixels
        var diff = 0.0
        var n = 0
        for (i in out.indices) if (i % w in 10 until w / 2 - 10 && i / w in 10 until h - 10) {
            diff += abs(hue(out[i]) - hue(ref[i]))
            n++
        }
        assertTrue("el azul cambia de tono ${diff / n}°", diff / n < 3.0)
    }

    @Test
    fun conUnHorizonteNoSeInventaDesplazamientos() {
        // Cielo arriba y suelo abajo: moverlo a los lados "encaja" igual. Entre fotos iguales, sin moverse.
        val t = texture(53, lo = 0.6f)
        val sky = IntArray(w * h) { i -> if (i / w < h / 2) argb(255, 250, 240) else (t[i] * 60).toInt().let { argb(it, it, it) } }
        val dark = IntArray(w * h) { i -> if (i / w < h / 2) (t[i] * 200).toInt().let { argb(it, it, it) } else argb(4, 4, 4) }
        // HDR: dos exposiciones distintas (mapas de bits por la mediana).
        val hdr = Aligner.align(LumaPlane.fromArgb(sky, w, h, 2), LumaPlane.fromArgb(dark, w, h, 2), 2, w, h, AlignMode.MTB)
        assertTrue("HDR: desplazamiento ${hdr.maxAbs} px", hdr.maxAbs <= 2f)
        // Noche: misma exposición, distinto ruido.
        val rnd = Random(3)
        val noisy = IntArray(w * h) { i -> val v = LumaPlane.luma(sky[i]) + (rnd.nextGaussian() * 4).toInt(); argb(v, v, v) }
        val night = Aligner.align(LumaPlane.fromArgb(sky, w, h, 2), LumaPlane.fromArgb(noisy, w, h, 2), 2, w, h, AlignMode.SSD)
        assertTrue("noche: desplazamiento ${night.maxAbs} px", night.maxAbs <= 2f)
    }

    @Test
    fun laMejoraNoEnfriaLaHoraDorada() {
        // Playa a la hora dorada: toda la escena es cálida porque la luz lo es (no hay nada gris).
        val t = texture(41, lo = 0.5f)
        val px = IntArray(w * h) { i ->
            val v = t[i]
            argb((235 * v).toInt(), (178 * v).toInt(), (112 * v).toInt())
        }
        fun ratio(a: IntArray) = a.sumOf { r(it).toDouble() } / a.sumOf { b(it).toDouble() + 1 }
        val out = AutoEnhance.enhance(px, w, h, ShotContext(SceneType.PLAYA))
        assertTrue("R/B ${ratio(px)} → ${ratio(out)}", ratio(out) > ratio(px) * 0.96)
    }

    @Test
    fun laMejoraCorrigeUnaDominanteSobreParedBlanca() {
        // Interior con luz de bombilla: la pared blanca y los muebles grises salen anaranjados.
        val t = texture(43, lo = 0.45f, hi = 0.95f)
        val px = IntArray(w * h) { i ->
            val v = t[i] * 230
            argb((v * 1.10f).toInt(), (v * 1.0f).toInt(), (v * 0.84f).toInt())
        }
        fun warmth(a: IntArray) = a.sumOf { r(it).toDouble() - b(it) } / a.size
        val out = AutoEnhance.enhance(px, w, h, ShotContext(SceneType.INTERIOR))
        assertTrue("calidez ${warmth(px)} → ${warmth(out)}", warmth(out) < warmth(px) * 0.75)
    }

    @Test
    fun laCaraPegadaTieneLaMismaLuzQueLaFoto() {
        // "Todos con los ojos abiertos": la foto donante salió un 12 % más clara (la exposición cambió).
        val t = texture(47)
        val base = IntArray(w * h) { i -> val v = (t[i] * 200).toInt(); argb(v, (v * 0.85).toInt(), (v * 0.7).toInt()) }
        val truth = base.copyOf()
        val donor = IntArray(w * h) { i ->
            val p = base[i]
            argb((r(p) * 1.12).toInt(), (g(p) * 1.12).toInt(), (b(p) * 1.12).toInt())
        }
        val region = PixelRect(80, 50, 160, 130)
        FaceMerge.paste(base, w, h, ArrayFrameSource(w, h, donor), region)
        var err = 0.0
        var n = 0
        for (y in 80 until 100) for (x in 110 until 130) {
            err += LumaPlane.luma(base[y * w + x]) - LumaPlane.luma(truth[y * w + x])
            n++
        }
        assertTrue("la cara queda ${err / n} niveles más clara", abs(err / n) < 3)
    }
}
