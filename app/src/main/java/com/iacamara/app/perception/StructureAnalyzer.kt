package com.iacamara.app.perception

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Segmento de recta en coordenadas normalizadas (0..1). */
data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val strength: Float = 1f) {
    val length: Float get() = hypot(x2 - x1, y2 - y1)
    val midX: Float get() = (x1 + x2) / 2f
    val midY: Float get() = (y1 + y2) / 2f

    fun map(f: (Float, Float) -> Pair<Float, Float>): Segment {
        val (a, b) = f(x1, y1)
        val (c, d) = f(x2, y2)
        return Segment(a, b, c, d, strength)
    }

    /** Distancia de un punto a la recta que contiene el segmento. */
    fun distanceToLine(x: Float, y: Float): Float {
        val len = length
        if (len < 1e-6f) return hypot(x - x1, y - y1)
        return abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / len
    }
}

/**
 * Geometría de la escena que usa el coach de composición: líneas que llevan la mirada, el punto
 * donde se juntan (punto de fuga) y si la escena es simétrica o tiene un reflejo.
 */
data class SceneStructure(
    /** Líneas guía: diagonales largas y claras (como mucho 4). */
    val leadingLines: List<Segment>,
    /** Punto donde convergen las líneas guía (puede quedar algo fuera del encuadre). */
    val vanishingPoint: Pair<Float, Float>?,
    /** Eje de simetría izquierda-derecha (vertical en la escena derecha), si la escena es simétrica. */
    val symmetryAxis: Segment?,
    /** Línea de un reflejo (agua, cristal): horizontal en la escena derecha. */
    val reflectionAxis: Segment?,
    /** Fuerza de la simetría y del reflejo (0–1), para depurar. */
    val symmetry: Float = 0f,
    val reflection: Float = 0f,
) {
    fun map(f: (Float, Float) -> Pair<Float, Float>): SceneStructure = copy(
        leadingLines = leadingLines.map { it.map(f) },
        vanishingPoint = vanishingPoint?.let { f(it.first, it.second) },
        symmetryAxis = symmetryAxis?.map(f),
        reflectionAxis = reflectionAxis?.map(f),
    )

    val isEmpty: Boolean get() = leadingLines.isEmpty() && symmetryAxis == null && reflectionAxis == null
}

/** Lectura de un solo fotograma, antes de estabilizarla en el tiempo. */
data class StructureReading(
    val leadingLines: List<Segment>,
    val vanishingPoint: Pair<Float, Float>?,
    /** Posición x (0..1) del mejor eje de simetría vertical y su fuerza (−1..1). */
    val symmetryX: Float,
    val symmetry: Float,
    /** Posición y (0..1) de la mejor línea de reflejo y su fuerza (−1..1). */
    val reflectionY: Float,
    val reflection: Float,
)

/**
 * Analiza la luminancia de un fotograma pequeño (≈160 px de lado mayor), con la escena derecha:
 *
 * - **Líneas guía:** bordes (Sobel) → transformada de Hough orientada → segmentos largos y continuos.
 *   Las diagonales que convergen en un punto forman el punto de fuga (caminos, vías, pasillos, vallas).
 * - **Simetría:** compara cada mitad con la otra reflejada (luminancia y bordes) para varios ejes
 *   cercanos al centro. Se ignora el sujeto, para que una persona sola no parezca una escena simétrica.
 * - **Reflejo:** lo mismo con un eje horizontal (lagos, charcos, cristales).
 *
 * Todo es Kotlin puro y rápido (pocos milisegundos), sin modelos.
 */
object StructureAnalyzer {

    private const val THETA_STEP_DEG = 2
    private const val N_THETA = 180 / THETA_STEP_DEG
    private const val MIN_EDGE = 50f
    private const val MAX_GAP_PX = 4
    private const val ORIENTATION_TOL_DEG = 14f

    /** Ángulos (respecto a la horizontal) que cuentan como diagonal. */
    private const val DIAGONAL_MIN_DEG = 14f
    private const val DIAGONAL_MAX_DEG = 76f

    /**
     * @param argb fotograma pequeño (≈160 px de lado mayor) con la escena derecha.
     * @param exclude zona del sujeto (normalizada) que no cuenta para la simetría.
     */
    fun analyze(argb: IntArray, w: Int, h: Int, exclude: NRect? = null): StructureReading {
        val n = w * h
        val r = FloatArray(n) { ((argb[it] shr 16) and 255).toFloat() }
        val g = FloatArray(n) { ((argb[it] shr 8) and 255).toFloat() }
        val b = FloatArray(n) { (argb[it] and 255).toFloat() }
        val channels = listOf(blur3(r, w, h), blur3(g, w, h), blur3(b, w, h))
        val luma = FloatArray(n) { 0.299f * channels[0][it] + 0.587f * channels[1][it] + 0.114f * channels[2][it] }
        val (lines, vp) = leadingLines(channels, w, h)
        val sym = symmetry(luma, w, h, exclude)
        return StructureReading(lines, vp, sym.first, sym.second, sym.third, sym.fourth)
    }

    // ---------------------------------------------------------------- líneas guía

    private class RawLine(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val lengthPx: Float, val strength: Float,
    ) {
        /** Ángulo de la línea respecto a la horizontal, 0..90°. */
        val tilt: Float
            get() {
                val a = Math.toDegrees(atan2(abs(y2 - y1).toDouble(), abs(x2 - x1).toDouble())).toFloat()
                return a
            }

        /** Dirección (0..180°) para comparar líneas. */
        val direction: Float
            get() {
                var a = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
                if (a < 0f) a += 180f
                if (a >= 180f) a -= 180f
                return a
            }

        fun distanceToLine(x: Float, y: Float): Float {
            val len = hypot(x2 - x1, y2 - y1)
            if (len < 1e-6f) return hypot(x - x1, y - y1)
            return abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / len
        }

        /** Posición del punto proyectado sobre el segmento: 0 = extremo 1, 1 = extremo 2. */
        fun along(x: Float, y: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6f) return 0f
            return ((x - x1) * dx + (y - y1) * dy) / len2
        }
    }

    /** Detecta segmentos largos y elige las líneas guía (y su punto de fuga, si lo hay). */
    internal fun leadingLines(channels: List<FloatArray>, w: Int, h: Int): Pair<List<Segment>, Pair<Float, Float>?> {
        val segments = detectSegments(channels, w, h)
        val diag = hypot(w.toFloat(), h.toFloat())
        val diagonals = segments.filter { it.tilt in DIAGONAL_MIN_DEG..DIAGONAL_MAX_DEG }
        fun norm(r: RawLine) = Segment((r.x1 + 0.5f) / w, (r.y1 + 0.5f) / h, (r.x2 + 0.5f) / w, (r.y2 + 0.5f) / h, r.strength)

        // Punto de fuga: intersección de diagonales hacia la que apuntan (sin atravesarla) varias líneas.
        var bestScore = 0f
        var bestSupport: List<RawLine> = emptyList()
        var bestPoint: Pair<Float, Float>? = null
        val tol = 0.03f * diag
        for (i in diagonals.indices) for (j in i + 1 until diagonals.size) {
            val a = diagonals[i]
            val b = diagonals[j]
            var dAng = abs(a.direction - b.direction)
            if (dAng > 90f) dAng = 180f - dAng
            if (dAng < 12f) continue
            val p = intersect(a, b) ?: continue
            if (p.first < -0.15f * w || p.first > 1.15f * w || p.second < -0.15f * h || p.second > 1.15f * h) continue
            val support = diagonals.filter { s ->
                if (s.distanceToLine(p.first, p.second) > tol) return@filter false
                // La línea debe ir hacia el punto, no cruzarlo por el medio.
                val t = s.along(p.first, p.second)
                t <= 0.12f || t >= 0.88f
            }
            if (support.size < 2) continue
            val score = support.sumOf { it.strength.toDouble() }.toFloat()
            if (score > bestScore) {
                bestScore = score
                bestSupport = support
                bestPoint = p
            }
        }
        val point = bestPoint
        if (point != null) {
            val lines = bestSupport.sortedByDescending { it.strength }.take(4).map(::norm)
            return lines to ((point.first + 0.5f) / w to (point.second + 0.5f) / h)
        }
        // Sin punto de fuga: una diagonal muy larga que entra por un borde también guía la mirada.
        val single = diagonals
            .filter { it.lengthPx >= 0.45f * diag && touchesBorder(it, w, h) }
            .maxByOrNull { it.strength }
        return if (single != null) listOf(norm(single)) to null else emptyList<Segment>() to null
    }

    private fun touchesBorder(r: RawLine, w: Int, h: Int): Boolean {
        val m = 0.07f * max(w, h)
        fun near(x: Float, y: Float) = x < m || y < m || x > w - 1 - m || y > h - 1 - m
        return near(r.x1, r.y1) || near(r.x2, r.y2)
    }

    private fun intersect(a: RawLine, b: RawLine): Pair<Float, Float>? {
        val d = (a.x1 - a.x2) * (b.y1 - b.y2) - (a.y1 - a.y2) * (b.x1 - b.x2)
        if (abs(d) < 1e-6f) return null
        val ca = a.x1 * a.y2 - a.y1 * a.x2
        val cb = b.x1 * b.y2 - b.y1 * b.x2
        val x = (ca * (b.x1 - b.x2) - (a.x1 - a.x2) * cb) / d
        val y = (ca * (b.y1 - b.y2) - (a.y1 - a.y2) * cb) / d
        return x to y
    }

    /**
     * Bordes → Hough orientado → segmentos largos, continuos y sin duplicados.
     * El borde de cada píxel es el del canal de color que más cambia: así se ve, por ejemplo,
     * un muro rosa sobre una acera gris aunque los dos tengan la misma luminosidad.
     */
    private fun detectSegments(channels: List<FloatArray>, w: Int, h: Int): List<RawLine> {
        val n = w * h
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val mag = FloatArray(n)
        for (l in channels) {
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val a = l[i - w - 1]; val b = l[i - w]; val c = l[i - w + 1]
                val d = l[i - 1]; val f = l[i + 1]
                val g = l[i + w - 1]; val k = l[i + w]; val m = l[i + w + 1]
                val sx = (c + 2 * f + m) - (a + 2 * d + g)
                val sy = (g + 2 * k + m) - (a + 2 * b + c)
                val mg = hypot(sx, sy)
                if (mg > mag[i]) {
                    gx[i] = sx
                    gy[i] = sy
                    mag[i] = mg
                }
            }
        }
        // Umbral: los bordes más marcados de la imagen, nunca por debajo de un mínimo.
        // Las texturas (hierba, hojas) también pasan, pero no forman rectas largas con la misma orientación.
        val sorted = mag.copyOf().also { it.sort() }
        val threshold = max(MIN_EDGE, sorted[(n * 0.8f).toInt().coerceIn(0, n - 1)])
        val edge = BooleanArray(n)
        val angle = FloatArray(n) // dirección del gradiente, 0..180°
        for (i in 0 until n) {
            if (mag[i] < threshold) continue
            edge[i] = true
            var a = Math.toDegrees(atan2(gy[i].toDouble(), gx[i].toDouble())).toFloat()
            if (a < 0f) a += 180f
            if (a >= 180f) a -= 180f
            angle[i] = a
        }

        val diagLen = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val nRho = 2 * diagLen + 1
        val acc = IntArray(N_THETA * nRho)
        val cosT = FloatArray(N_THETA) { cos(it * THETA_STEP_DEG * PI / 180).toFloat() }
        val sinT = FloatArray(N_THETA) { sin(it * THETA_STEP_DEG * PI / 180).toFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!edge[i]) continue
            val k0 = (angle[i] / THETA_STEP_DEG).roundToInt()
            for (dk in -2..2) {
                val k = ((k0 + dk) % N_THETA + N_THETA) % N_THETA
                val r = (x * cosT[k] + y * sinT[k]).roundToInt() + diagLen
                acc[k * nRho + r]++
            }
        }

        // Picos del acumulador (máximos locales con votos suficientes).
        val minVotes = (0.2f * min(w, h)).toInt().coerceAtLeast(8)
        val peaks = ArrayList<Triple<Int, Int, Int>>()
        for (k in 0 until N_THETA) for (r in 0 until nRho) {
            val v = acc[k * nRho + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dk in -2..2) for (dr in -2..2) {
                if (dk == 0 && dr == 0) continue
                val kk = ((k + dk) % N_THETA + N_THETA) % N_THETA
                val rr = r + dr
                if (rr !in 0 until nRho) continue
                val o = acc[kk * nRho + rr]
                if (o > v || (o == v && (dk < 0 || (dk == 0 && dr < 0)))) {
                    isMax = false
                    break@loop
                }
            }
            if (isMax) peaks += Triple(k, r, v)
        }
        peaks.sortByDescending { it.third }

        val diag = hypot(w.toFloat(), h.toFloat())
        val minLen = 0.22f * diag
        val result = ArrayList<RawLine>()
        for ((k, r, _) in peaks.take(32)) {
            val theta = k * THETA_STEP_DEG.toFloat()
            val seg = trace(edge, angle, w, h, theta, (r - diagLen).toFloat(), cosT[k], sinT[k]) ?: continue
            if (seg.lengthPx < minLen) continue
            val duplicate = result.any { o ->
                var d = abs(o.direction - seg.direction)
                if (d > 90f) d = 180f - d
                d < 6f && o.distanceToLine(seg.x1, seg.y1) < 4f && o.distanceToLine(seg.x2, seg.y2) < 4f
            }
            if (!duplicate) result += seg
            if (result.size >= 10) break
        }
        return result
    }

    /** Recorre la recta (θ, ρ) y devuelve el tramo continuo más largo que tiene borde de verdad. */
    private fun trace(
        edge: BooleanArray, angle: FloatArray, w: Int, h: Int,
        theta: Float, rho: Float, c: Float, s: Float,
    ): RawLine? {
        val dx = -s
        val dy = c
        val px = rho * c
        val py = rho * s
        val reach = hypot(w.toFloat(), h.toFloat())
        fun hit(x: Int, y: Int): Boolean {
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) return false
            val i = y * w + x
            if (!edge[i]) return false
            var d = abs(angle[i] - theta)
            if (d > 90f) d = 180f - d
            return d <= ORIENTATION_TOL_DEG
        }
        // Tramos continuos (huecos de hasta MAX_GAP_PX).
        val runs = ArrayList<FloatArray>() // [inicio, fin, aciertos]
        var runStart = Float.NaN
        var lastHit = Float.NaN
        var hits = 0
        var t = -reach
        while (t <= reach) {
            val x = px + t * dx
            val y = py + t * dy
            val inside = x >= 0f && y >= 0f && x <= w - 1f && y <= h - 1f
            val found = inside && (hit(x.roundToInt(), y.roundToInt()) ||
                hit((x + c).roundToInt(), (y + s).roundToInt()) ||
                hit((x - c).roundToInt(), (y - s).roundToInt()))
            if (found) {
                if (runStart.isNaN() || t - lastHit > MAX_GAP_PX) {
                    if (!runStart.isNaN() && hits >= 3) runs += floatArrayOf(runStart, lastHit, hits.toFloat())
                    runStart = t
                    hits = 0
                }
                lastHit = t
                hits++
            }
            t += 1f
        }
        if (!runStart.isNaN() && hits >= 3) runs += floatArrayOf(runStart, lastHit, hits.toFloat())
        if (runs.isEmpty()) return null
        // El tramo más largo que, uniendo tramos seguidos, sigue teniendo borde en al menos el 55 %:
        // así una línea tapada por el sujeto en el medio sigue contando como una sola.
        var bestStart = 0f
        var bestEnd = -1f
        var bestHits = 0f
        for (i in runs.indices) {
            var total = 0f
            for (j in i until runs.size) {
                total += runs[j][2]
                val span = runs[j][1] - runs[i][0]
                if (total / (span + 1f) >= 0.55f && span > bestEnd - bestStart) {
                    bestStart = runs[i][0]
                    bestEnd = runs[j][1]
                    bestHits = total
                }
            }
        }
        val length = bestEnd - bestStart
        if (length <= 0f) return null
        val coverage = bestHits / (length + 1f)
        val diag = hypot(w.toFloat(), h.toFloat())
        return RawLine(
            px + bestStart * dx, py + bestStart * dy,
            px + bestEnd * dx, py + bestEnd * dy,
            length, (length / diag) * coverage,
        )
    }

    // ---------------------------------------------------------------- simetría y reflejos

    private data class Quad(val first: Float, val second: Float, val third: Float, val fourth: Float)

    /** (eje x, fuerza de la simetría, eje y, fuerza del reflejo). */
    private fun symmetry(l: FloatArray, w: Int, h: Int, exclude: NRect?): Quad {
        // Rejilla gruesa (~40 px de lado menor): tolera pequeñas diferencias de perspectiva.
        val f = max(1, (min(w, h) / 40f).roundToInt())
        val sw = w / f
        val sh = h / f
        val a = FloatArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var sum = 0f
            for (j in 0 until f) for (i in 0 until f) sum += l[(y * f + j) * w + x * f + i]
            a[y * sw + x] = sum / (f * f)
        }
        val mask = BooleanArray(sw * sh)
        if (exclude != null) {
            val mx = exclude.width * 0.15f
            val my = exclude.height * 0.15f
            val x0 = floor((exclude.left - mx) * sw).toInt()
            val x1 = ceil((exclude.right + mx) * sw).toInt()
            val y0 = floor((exclude.top - my) * sh).toInt()
            val y1 = ceil((exclude.bottom + my) * sh).toInt()
            for (y in max(0, y0) until min(sh, y1)) for (x in max(0, x0) until min(sw, x1)) mask[y * sw + x] = true
        }
        val (sx, sScore) = mirrorAxis(a, sw, sh, mask)
        // Reflejo: lo mismo sobre la imagen traspuesta (filas ↔ columnas).
        val t = FloatArray(sw * sh)
        val tm = BooleanArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            t[x * sh + y] = a[y * sw + x]
            tm[x * sh + y] = mask[y * sw + x]
        }
        val (ry, rScore) = mirrorAxis(t, sh, sw, tm)
        // La rejilla gruesa puede dejar fuera las últimas columnas o filas.
        return Quad(sx * sw * f / w, sScore, ry * sh * f / h, rScore)
    }

    /**
     * Mejor eje vertical de simetría espejo de la imagen `a` (w×h): posición normalizada y parecido
     * entre las dos mitades (−1..1), el menor entre el de la luminancia y el de los bordes verticales.
     */
    internal fun mirrorAxis(a: FloatArray, w: Int, h: Int, mask: BooleanArray): Pair<Float, Float> {
        val gx = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            gx[y * w + x] = a[y * w + min(w - 1, x + 1)] - a[y * w + max(0, x - 1)]
        }
        var bestPos = 0.5f
        var bestScore = -1f
        val from = (0.3f * 2 * (w - 1)).toInt()
        val to = (0.7f * 2 * (w - 1)).toInt()
        for (a2 in from..to) {
            val s = a2 / 2f
            val half = min(s, w - 1 - s)
            if (half < 0.25f * w) continue
            val lo = ceil(s - half).toInt()
            var suv = 0.0; var suu = 0.0; var svv = 0.0
            var guv = 0.0; var guu = 0.0; var gvv = 0.0
            var count = 0
            for (y in 0 until h) {
                // Media de cada mitad en esta fila (quita los cambios que solo van de arriba abajo).
                var ml = 0f; var mr = 0f; var nl = 0
                var i = lo
                while (i < s) {
                    val j = a2 - i
                    if (!mask[y * w + i] && !mask[y * w + j]) {
                        ml += a[y * w + i]; mr += a[y * w + j]; nl++
                    }
                    i++
                }
                if (nl < 2) continue
                ml /= nl; mr /= nl
                i = lo
                while (i < s) {
                    val j = a2 - i
                    val p = y * w + i
                    val q = y * w + j
                    if (!mask[p] && !mask[q]) {
                        val u = a[p] - ml
                        val v = a[q] - mr
                        suv += u * v; suu += u * u; svv += v * v
                        // En un espejo, el cambio de izquierda a derecha cambia de signo. Solo se usa
                        // ese: los bordes horizontales (una fila de árboles, el horizonte) "se parecen"
                        // en cualquier eje y darían simetrías falsas.
                        guv += -gx[p] * gx[q]
                        guu += gx[p] * gx[p]
                        gvv += gx[q] * gx[q]
                        count++
                    }
                    i++
                }
            }
            if (count < 0.15f * w * h) continue
            // Hace falta detalle de verdad: una pared lisa no es "simétrica".
            val std = sqrt((suu + svv) / (2.0 * count))
            if (std < 7.0 || guu <= 0.0 || gvv <= 0.0) continue
            val ncc = (suv / sqrt(suu * svv)).toFloat()
            val gncc = (guv / sqrt(guu * gvv)).toFloat()
            val score = min(ncc, gncc)
            if (score > bestScore) {
                bestScore = score
                bestPos = (s + 0.5f) / w
            }
        }
        return bestPos to bestScore
    }

    // ---------------------------------------------------------------- utilidades

    internal fun blur3(l: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            var c = 0
            for (j in -1..1) {
                val yy = y + j
                if (yy !in 0 until h) continue
                for (i in -1..1) {
                    val xx = x + i
                    if (xx !in 0 until w) continue
                    s += l[yy * w + xx]
                    c++
                }
            }
            out[y * w + x] = s / c
        }
        return out
    }
}

/**
 * Suaviza la estructura en el tiempo para que las guías no parpadeen: la simetría se enciende con
 * fuerza ≥ 0,55 y se apaga por debajo de 0,46; las líneas guía se muestran si aparecen en 2 de las
 * últimas 3 lecturas.
 */
class StructureStabilizer {
    private var symmetry = 0f
    private var symmetryX = 0.5f
    private var symmetryOn = false
    private var reflection = 0f
    private var reflectionY = 0.5f
    private var reflectionOn = false
    private val lineHistory = ArrayDeque<Boolean>()
    private var lastLines: List<Segment> = emptyList()
    private var lastVp: Pair<Float, Float>? = null

    fun update(r: StructureReading): SceneStructure {
        symmetry += 0.5f * (r.symmetry - symmetry)
        symmetryX += 0.5f * (r.symmetryX - symmetryX)
        symmetryOn = if (symmetryOn) symmetry >= SYM_OFF else symmetry >= SYM_ON
        reflection += 0.5f * (r.reflection - reflection)
        reflectionY += 0.5f * (r.reflectionY - reflectionY)
        reflectionOn = if (reflectionOn) reflection >= REFL_OFF else reflection >= REFL_ON

        val found = r.leadingLines.isNotEmpty()
        lineHistory.addLast(found)
        while (lineHistory.size > 3) lineHistory.removeFirst()
        if (found) {
            lastLines = r.leadingLines
            lastVp = r.vanishingPoint
        }
        val showLines = lineHistory.count { it } >= 2 && lastLines.isNotEmpty()

        return SceneStructure(
            leadingLines = if (showLines) lastLines else emptyList(),
            vanishingPoint = if (showLines) lastVp else null,
            symmetryAxis = if (symmetryOn) Segment(symmetryX, 0f, symmetryX, 1f, symmetry) else null,
            // Si las dos aparecen, manda la más fuerte (una fachada con filas de ventanas iguales
            // también "se parece" arriba y abajo, pero es simétrica de izquierda a derecha).
            reflectionAxis = if (reflectionOn && !(symmetryOn && symmetry >= reflection)) {
                Segment(0f, reflectionY, 1f, reflectionY, reflection)
            } else {
                null
            },
            symmetry = symmetry,
            reflection = reflection,
        )
    }

    fun clear() {
        symmetry = 0f
        symmetryOn = false
        reflection = 0f
        reflectionOn = false
        lineHistory.clear()
        lastLines = emptyList()
        lastVp = null
    }

    companion object {
        const val SYM_ON = 0.55f
        const val SYM_OFF = 0.46f
        const val REFL_ON = 0.5f
        const val REFL_OFF = 0.38f
    }
}
