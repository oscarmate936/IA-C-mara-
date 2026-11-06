package com.iacamara.app.imaging

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fusión de varios fotogramas alineados. Se trabaja por franjas horizontales para no tener todas las
 * fotos completas en memoria: en cada franja se leen solo las filas necesarias de cada fotograma.
 */
object Fusion {

    private const val STRIPE = 192

    /**
     * Modo noche: promedio robusto de la ráfaga. Cada píxel se promedia con los de los demás fotogramas,
     * pero si uno se parece poco a la referencia (algo se movió), cuenta menos: así baja el ruido sin
     * dejar "fantasmas".
     *
     * @param noiseSigma ruido de la referencia (niveles 0–255) a resolución completa.
     */
    fun night(
        frames: List<FrameSource>,
        flows: List<Flow>,
        reference: Int,
        noiseSigma: Float,
        out: IntArray,
        onProgress: (Float) -> Unit = {},
    ) {
        val ref = frames[reference]
        val w = ref.width
        val h = ref.height
        // Diferencias mayores que ~3σ se consideran movimiento real.
        val tolerance = max(6f, 3f * noiseSigma)
        val inv = 1f / (tolerance * tolerance)
        val margin = ceil(flows.maxOf { it.maxAbs }).toInt() + 2
        val stripes = (h + STRIPE - 1) / STRIPE
        val done = AtomicInteger()
        Parallel.forRange(stripes) { s ->
            val y0 = s * STRIPE
            val y1 = min(h, y0 + STRIPE)
            val rows = y1 - y0
            val shift = FloatArray(2)
            val refPx = ref.readRegion(0, y0, w, rows)
            val sr = FloatArray(w * rows)
            val sg = FloatArray(w * rows)
            val sb = FloatArray(w * rows)
            val sw = FloatArray(w * rows)
            for (i in refPx.indices) {
                val p = refPx[i]
                sr[i] = ((p shr 16) and 0xFF).toFloat()
                sg[i] = ((p shr 8) and 0xFF).toFloat()
                sb[i] = (p and 0xFF).toFloat()
                sw[i] = 1f
            }
            for (k in frames.indices) {
                if (k == reference) continue
                val ry0 = max(0, y0 - margin)
                val ry1 = min(h, y1 + margin)
                val src = frames[k].readRegion(0, ry0, w, ry1 - ry0)
                val srcRows = ry1 - ry0
                val flow = flows[k]
                for (y in y0 until y1) {
                    var bx = -1
                    var fdx = 0f
                    var fdy = 0f
                    for (x in 0 until w) {
                        // El desplazamiento varía despacio: se calcula cada 8 píxeles.
                        if (x shr 3 != bx) {
                            bx = x shr 3
                            flow.at(x + 4f, y.toFloat(), shift)
                            fdx = shift[0]
                            fdy = shift[1]
                        }
                        val sx = x + fdx
                        val sy = y + fdy - ry0
                        if (sx < 0f || sy < 0f || sx > w - 1f || sy > srcRows - 1f) continue
                        val sample = bilinear(src, w, srcRows, sx, sy)
                        val i = (y - y0) * w + x
                        val d = (LumaPlane.luma(sample) - LumaPlane.luma(refPx[i])).toFloat()
                        val weight = 1f / (1f + d * d * inv)
                        sr[i] += weight * ((sample shr 16) and 0xFF)
                        sg[i] += weight * ((sample shr 8) and 0xFF)
                        sb[i] += weight * (sample and 0xFF)
                        sw[i] += weight
                    }
                }
            }
            for (i in 0 until w * rows) {
                val n = sw[i]
                out[y0 * w + i] = argb(
                    (sr[i] / n + 0.5f).toInt(),
                    (sg[i] / n + 0.5f).toInt(),
                    (sb[i] / n + 0.5f).toInt(),
                )
            }
            onProgress(done.incrementAndGet() / stripes.toFloat())
        }
    }

    /**
     * Combina, píxel a píxel, las muestras alineadas de todos los fotogramas con `combine`
     * (recibe las muestras ARGB y cuántas hay; la primera es siempre la de la referencia).
     * Franjas más finas que en el modo noche: aquí se guardan todas las muestras a la vez.
     */
    fun combine(
        frames: List<FrameSource>,
        flows: List<Flow>,
        reference: Int,
        out: IntArray,
        onProgress: (Float) -> Unit = {},
        combine: (IntArray, Int) -> Int,
    ) {
        val ref = frames[reference]
        val w = ref.width
        val h = ref.height
        val n = frames.size
        val margin = ceil(flows.maxOf { it.maxAbs }).toInt() + 2
        val stripe = 48
        val stripes = (h + stripe - 1) / stripe
        val done = AtomicInteger()
        val order = listOf(reference) + frames.indices.filter { it != reference }
        Parallel.forRange(stripes) { s ->
            val y0 = s * stripe
            val y1 = min(h, y0 + stripe)
            val rows = y1 - y0
            val samples = IntArray(n * w * rows)
            val counts = IntArray(w * rows)
            val shift = FloatArray(2)
            for (k in order) {
                val ry0 = max(0, y0 - margin)
                val ry1 = min(h, y1 + margin)
                val src = frames[k].readRegion(0, ry0, w, ry1 - ry0)
                val srcRows = ry1 - ry0
                val flow = flows[k]
                for (y in y0 until y1) {
                    var bx = -1
                    var fdx = 0f
                    var fdy = 0f
                    for (x in 0 until w) {
                        if (x shr 3 != bx) {
                            bx = x shr 3
                            flow.at(x + 4f, y.toFloat(), shift)
                            fdx = shift[0]
                            fdy = shift[1]
                        }
                        val sx = x + fdx
                        val sy = y + fdy - ry0
                        if (sx < 0f || sy < 0f || sx > w - 1f || sy > srcRows - 1f) continue
                        val i = (y - y0) * w + x
                        samples[i * n + counts[i]] = bilinear(src, w, srcRows, sx, sy)
                        counts[i]++
                    }
                }
            }
            val pixel = IntArray(n)
            for (i in 0 until w * rows) {
                val c = counts[i]
                System.arraycopy(samples, i * n, pixel, 0, c)
                out[y0 * w + i] = combine(pixel, c)
            }
            onProgress(done.incrementAndGet() / stripes.toFloat())
        }
    }

    /**
     * Mediana de cada canal de color: lo que solo aparece en algunas fotos (gente que pasa, coches)
     * desaparece y lo que está quieto se queda. Por canales, para que un color distinto con el mismo
     * brillo que el fondo no se cuele.
     */
    fun medianSample(samples: IntArray, n: Int): Int {
        if (n == 0) return 0
        if (n <= 2) return samples[0]
        val v = IntArray(n)
        fun median(shift: Int): Int {
            for (k in 0 until n) v[k] = (samples[k] shr shift) and 0xFF
            // Ordenación por inserción (pocas muestras).
            for (a in 1 until n) {
                val x = v[a]
                var b = a - 1
                while (b >= 0 && v[b] > x) {
                    v[b + 1] = v[b]
                    b--
                }
                v[b + 1] = x
            }
            return v[n / 2]
        }
        return argb(median(16), median(8), median(0))
    }

    /** Promedio (larga exposición de día: agua sedosa, nubes y gente desenfocadas por el movimiento). */
    fun meanSample(samples: IntArray, n: Int): Int {
        if (n == 0) return 0
        var r = 0
        var g = 0
        var b = 0
        for (k in 0 until n) {
            val p = samples[k]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        return argb((r + n / 2) / n, (g + n / 2) / n, (b + n / 2) / n)
    }

    /**
     * Aclarar (larga exposición de noche): de cada píxel se queda el más luminoso, así las luces que
     * se mueven (coches, estrellas) dejan una estela; el fondo se promedia para bajar el ruido.
     */
    fun lightenSample(samples: IntArray, n: Int): Int {
        if (n == 0) return 0
        val mean = meanSample(samples, n)
        var best = samples[0]
        var bestL = LumaPlane.luma(best)
        for (k in 1 until n) {
            val l = LumaPlane.luma(samples[k])
            if (l > bestL) {
                bestL = l
                best = samples[k]
            }
        }
        // Solo donde una muestra es claramente más brillante que la media hay una luz que pasa.
        val ml = LumaPlane.luma(mean)
        val t = ((bestL - ml - 12) / 40f).coerceIn(0f, 1f)
        if (t <= 0f) return mean
        fun mix(shift: Int) = ((((mean shr shift) and 0xFF) * (1 - t)) + ((best shr shift) and 0xFF) * t).toInt()
        return argb(mix(16), mix(8), mix(0))
    }

    /**
     * HDR por horquillado, como las cámaras buenas:
     * 1. Se combinan las exposiciones en un mapa de luz real (lineal), corrigiendo la exposición de cada
     *    una y usando solo sus zonas bien expuestas: las luces salen de la foto oscura y las sombras de la
     *    clara (menos ruido). Donde algo se movió entre fotos, se usa la referencia (sin fantasmas).
     * 2. Una curva de tono global devuelve ese mapa a la pantalla: comprime solo las luces (conserva el
     *    color del cielo), mantiene los tonos medios y levanta las sombras. Al ser global no crea halos.
     *
     * @param evs exposición de cada fotograma en pasos (p. ej. −2, 0, +2).
     */
    fun hdr(
        frames: List<FrameSource>,
        flows: List<Flow>,
        reference: Int,
        evs: FloatArray,
        out: IntArray,
        onProgress: (Float) -> Unit = {},
    ) {
        val ref = frames[reference]
        val w = ref.width
        val h = ref.height
        val curve = HdrCurve.fit(frames, flows, reference, evs)
        val margin = ceil(flows.maxOf { it.maxAbs }).toInt() + 2
        val stripes = (h + STRIPE - 1) / STRIPE
        val done = AtomicInteger()
        val gains = FloatArray(evs.size) { 2f.pow(evs[reference] - evs[it]) }
        Parallel.forRange(stripes) { s ->
            val y0 = s * STRIPE
            val y1 = min(h, y0 + STRIPE)
            val rows = y1 - y0
            val shift = FloatArray(2)
            val refPx = ref.readRegion(0, y0, w, rows)
            val lr = FloatArray(w * rows)
            val lg = FloatArray(w * rows)
            val lb = FloatArray(w * rows)
            val sw = FloatArray(w * rows)
            for (k in frames.indices) {
                val ry0 = max(0, y0 - margin)
                val ry1 = min(h, y1 + margin)
                val src = if (k == reference) null else frames[k].readRegion(0, ry0, w, ry1 - ry0)
                val srcRows = ry1 - ry0
                val flow = flows[k]
                val gain = gains[k]
                val preference = if (k == reference) 1.5f else 1f
                for (y in y0 until y1) {
                    var bx = -1
                    var fdx = 0f
                    var fdy = 0f
                    for (x in 0 until w) {
                        val i = (y - y0) * w + x
                        val refPixel = refPx[i]
                        val sample = if (src == null) {
                            refPixel
                        } else {
                            if (x shr 3 != bx) {
                                bx = x shr 3
                                flow.at(x + 4f, y.toFloat(), shift)
                                fdx = shift[0]
                                fdy = shift[1]
                            }
                            val sx = x + fdx
                            val sy = y + fdy - ry0
                            if (sx < 0f || sy < 0f || sx > w - 1f || sy > srcRows - 1f) continue
                            bilinear(src, w, srcRows, sx, sy)
                        }
                        var weight = HdrCurve.VALID[LumaPlane.luma(sample)] * preference
                        if (weight <= 0f) continue
                        if (src != null && isGhost(refPixel, sample, 1f / gain)) weight = 0f
                        lr[i] += weight * HdrCurve.TO_LINEAR[(sample shr 16) and 0xFF] * gain
                        lg[i] += weight * HdrCurve.TO_LINEAR[(sample shr 8) and 0xFF] * gain
                        lb[i] += weight * HdrCurve.TO_LINEAR[sample and 0xFF] * gain
                        sw[i] += weight
                    }
                }
            }
            for (i in 0 until w * rows) {
                val n = sw[i]
                out[y0 * w + i] = if (n <= 1e-6f) {
                    // Ninguna foto bien expuesta aquí (negro o blanco puro en todas): se deja la referencia.
                    curve.apply(
                        HdrCurve.TO_LINEAR[(refPx[i] shr 16) and 0xFF],
                        HdrCurve.TO_LINEAR[(refPx[i] shr 8) and 0xFF],
                        HdrCurve.TO_LINEAR[refPx[i] and 0xFF],
                    )
                } else {
                    curve.apply(lr[i] / n, lg[i] / n, lb[i] / n)
                }
            }
            onProgress(done.incrementAndGet() / stripes.toFloat())
        }
    }

    /**
     * Fantasma: con la exposición corregida, el fotograma y la referencia deberían tener el mismo brillo.
     * Si difieren mucho en una zona bien expuesta en ambos, algo se movió.
     */
    private fun isGhost(refPixel: Int, sample: Int, ratio: Float): Boolean {
        val refLin = HdrCurve.TO_LINEAR[LumaPlane.luma(refPixel)]
        val sampleLin = HdrCurve.TO_LINEAR[LumaPlane.luma(sample)]
        val expected = refLin * ratio
        if (expected < 0.01f || expected > 0.85f || sampleLin < 0.01f || sampleLin > 0.85f) return false
        return abs(ln(sampleLin / expected)) > 0.8f
    }

    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (k in -radius..radius) {
                    val xx = x + k
                    if (xx in 0 until w) {
                        s += src[y * w + xx]
                        n++
                    }
                }
                tmp[y * w + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var n = 0
                for (k in -radius..radius) {
                    val yy = y + k
                    if (yy in 0 until h) {
                        s += tmp[yy * w + x]
                        n++
                    }
                }
                out[y * w + x] = s / n
            }
        }
        return out
    }

    /** Interpolación bilineal de un píxel ARGB en coordenadas no enteras. */
    fun bilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = floor(x).toInt().coerceIn(0, w - 1)
        val y0 = floor(y).toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = x - x0
        val fy = y - y0
        val a = px[y0 * w + x0]
        val b = px[y0 * w + x1]
        val c = px[y1 * w + x0]
        val d = px[y1 * w + x1]
        return argb(
            channel(a, b, c, d, 16, fx, fy),
            channel(a, b, c, d, 8, fx, fy),
            channel(a, b, c, d, 0, fx, fy),
        )
    }

    private fun channel(a: Int, b: Int, c: Int, d: Int, shift: Int, fx: Float, fy: Float): Int {
        val va = (a shr shift) and 0xFF
        val vb = (b shr shift) and 0xFF
        val vc = (c shr shift) and 0xFF
        val vd = (d shr shift) and 0xFF
        val top = va + (vb - va) * fx
        val bottom = vc + (vd - vc) * fx
        return (top + (bottom - top) * fy + 0.5f).toInt()
    }
}

/** Curvas de tono y nitidez para el resultado final. */
object ToneCurve {

    /**
     * Enfoque suave (máscara de enfoque sobre la luminancia, radio 1). Compensa la ligera suavidad que
     * deja promediar varias fotos. Solo realza el detalle que hay, sin crear ruido en zonas lisas.
     */
    fun sharpen(pixels: IntArray, width: Int, height: Int, amount: Float = 0.45f, threshold: Int = 3) {
        val luma = IntArray(pixels.size) { LumaPlane.luma(pixels[it]) }
        val rowsPerTask = 128
        val tasks = (height + rowsPerTask - 1) / rowsPerTask
        Parallel.forRange(tasks) { t ->
            val y0 = max(1, t * rowsPerTask)
            val y1 = min(height - 1, (t + 1) * rowsPerTask)
            for (y in y0 until y1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val blur = (luma[i] * 4 + luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width]) / 8
                    val detail = luma[i] - blur
                    if (kotlin.math.abs(detail) <= threshold) continue
                    val add = (detail * amount).toInt()
                    val p = pixels[i]
                    pixels[i] = argb(((p shr 16) and 0xFF) + add, ((p shr 8) and 0xFF) + add, (p and 0xFF) + add)
                }
            }
        }
    }

    /**
     * Neutraliza el color de los negros. En la oscuridad el sensor deja los negros con un pequeño exceso
     * de rojo y azul (o de verde); al aclarar la foto ese exceso se multiplica y la foto sale rosa.
     * Se mide el color medio de las zonas oscuras y se resta su desviación respecto al gris, con toda
     * su fuerza en los negros y desapareciendo hacia los tonos medios (las luces no se tocan).
     *
     * @return la corrección aplicada a (R, G, B) en los negros, para pruebas y diagnóstico.
     */
    fun neutralizeBlacks(pixels: IntArray, maxOffset: Int = 30): IntArray {
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        for (i in pixels.indices step 3) {
            val p = pixels[i]
            val l = LumaPlane.luma(p)
            if (l in 2..45) {
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
                n++
            }
        }
        // Con muy pocas zonas oscuras no hay nada fiable que medir.
        if (n < pixels.size / 3 / 50) return intArrayOf(0, 0, 0)
        val mr = sr / n.toFloat()
        val mg = sg / n.toFloat()
        val mb = sb / n.toFloat()
        val gray = minOf(mr, mg, mb)
        val off = intArrayOf(
            (mr - gray).toInt().coerceIn(0, maxOffset),
            (mg - gray).toInt().coerceIn(0, maxOffset),
            (mb - gray).toInt().coerceIn(0, maxOffset),
        )
        if (off.all { it <= 1 }) return intArrayOf(0, 0, 0)
        val fade = FloatArray(256) { l -> (1f - l / 140f).coerceIn(0f, 1f) }
        for (i in pixels.indices) {
            val p = pixels[i]
            val f = fade[LumaPlane.luma(p)]
            if (f <= 0f) continue
            pixels[i] = argb(
                (((p shr 16) and 0xFF) - off[0] * f + 0.5f).toInt(),
                (((p shr 8) and 0xFF) - off[1] * f + 0.5f).toInt(),
                ((p and 0xFF) - off[2] * f + 0.5f).toInt(),
            )
        }
        return off
    }

    /**
     * Aclara una foto oscura (modo noche) con una curva gamma calculada para llevar el brillo medio a
     * `targetMean`, sin quemar las luces (la curva deja el blanco en blanco). Respeta el color: escala los
     * tres canales por igual.
     */
    fun autoBrighten(pixels: IntArray, targetMean: Float = 0.30f, minGamma: Float = 0.55f) {
        var sum = 0L
        var n = 0
        for (i in pixels.indices step 7) {
            sum += LumaPlane.luma(pixels[i])
            n++
        }
        val mean = (sum / max(1, n).toFloat()) / 255f
        if (mean <= 0.01f || mean >= targetMean) return
        val gamma = (ln(targetMean) / ln(mean)).coerceIn(minGamma, 1f)
        val gain = FloatArray(256) { l ->
            if (l == 0) 1f else (255f * (l / 255f).pow(gamma)) / l
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = gain[LumaPlane.luma(p)]
            pixels[i] = argb(
                (((p shr 16) and 0xFF) * g + 0.5f).toInt(),
                (((p shr 8) and 0xFF) * g + 0.5f).toInt(),
                ((p and 0xFF) * g + 0.5f).toInt(),
            )
        }
    }
}
