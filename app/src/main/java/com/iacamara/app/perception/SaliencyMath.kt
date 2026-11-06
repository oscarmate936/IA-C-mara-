package com.iacamara.app.perception

import kotlin.math.max
import kotlin.math.min

/** Cálculos sobre el mapa de saliencia (qué llama la atención en la imagen). */
object SaliencyMath {

    const val PORTRAIT_W = 24
    const val PORTRAIT_H = 32

    /**
     * Convierte la rejilla calculada sobre la imagen del modelo (girada `rotation` grados respecto al
     * encuadre vertical) en una rejilla del encuadre vertical de la pantalla (24×32).
     */
    fun toPortraitGrid(model: FloatArray, modelW: Int, modelH: Int, rotation: Int): SaliencyMap {
        val out = FloatArray(PORTRAIT_W * PORTRAIT_H)
        for (j in 0 until PORTRAIT_H) {
            for (i in 0 until PORTRAIT_W) {
                val (mx, my) = FrameMath.rotate((i + 0.5f) / PORTRAIT_W, (j + 0.5f) / PORTRAIT_H, rotation)
                val gi = (mx * modelW).toInt().coerceIn(0, modelW - 1)
                val gj = (my * modelH).toInt().coerceIn(0, modelH - 1)
                out[j * PORTRAIT_W + i] = model[gj * modelW + gi]
            }
        }
        return SaliencyMap(PORTRAIT_W, PORTRAIT_H, out)
    }

    /** Reduce un mapa denso (size×size) a una rejilla gw×gh promediando bloques. */
    fun downsample(dense: Array<FloatArray>, gw: Int, gh: Int): FloatArray {
        val h = dense.size
        val w = dense.firstOrNull()?.size ?: return FloatArray(gw * gh)
        val out = FloatArray(gw * gh)
        val counts = IntArray(gw * gh)
        for (y in 0 until h) {
            val gj = min(gh - 1, y * gh / h)
            val row = dense[y]
            for (x in 0 until w) {
                val gi = min(gw - 1, x * gw / w)
                out[gj * gw + gi] += row[x]
                counts[gj * gw + gi]++
            }
        }
        for (k in out.indices) if (counts[k] > 0) out[k] /= counts[k]
        return out
    }

    /** Normaliza a 0–1 (el modelo devuelve valores relativos). */
    fun normalize(values: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in values) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val range = hi - lo
        if (range < 1e-6f) return FloatArray(values.size)
        return FloatArray(values.size) { (values[it] - lo) / range }
    }

    /**
     * Caja de la región más grande que llama la atención (componente conexa por encima del umbral),
     * en coordenadas normalizadas del mapa, con su saliencia media. Null si no hay nada claro.
     */
    fun mainRegion(map: SaliencyMap, threshold: Float = 0.5f): Pair<NRect, Float>? {
        val w = map.width
        val h = map.height
        val visited = BooleanArray(w * h)
        var best: List<Int>? = null
        var bestMass = 0f
        val queue = ArrayDeque<Int>()
        for (start in 0 until w * h) {
            if (visited[start] || map.value(start % w, start / w) < threshold) continue
            val cells = mutableListOf<Int>()
            var mass = 0f
            visited[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                cells += c
                val cx = c % w
                val cy = c / w
                mass += map.value(cx, cy)
                for ((nx, ny) in listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (!visited[n] && map.value(nx, ny) >= threshold) {
                        visited[n] = true
                        queue.addLast(n)
                    }
                }
            }
            if (mass > bestMass) {
                bestMass = mass
                best = cells
            }
        }
        val cells = best ?: return null
        val xs = cells.map { it % w }
        val ys = cells.map { it / w }
        val box = NRect(
            xs.min().toFloat() / w,
            ys.min().toFloat() / h,
            (xs.max() + 1).toFloat() / w,
            (ys.max() + 1).toFloat() / h,
        )
        return box to bestMass / cells.size
    }
}

/** Geometría del "letterbox": la imagen se escala sin deformar y se centra en un cuadrado. */
data class Letterbox(val scale: Float, val padX: Int, val padY: Int, val srcW: Int, val srcH: Int) {

    /** Punto en píxeles de la entrada del modelo → coordenada normalizada de la imagen original. */
    fun toNormalized(x: Float, y: Float): Pair<Float, Float> =
        ((x - padX) / scale / srcW) to ((y - padY) / scale / srcH)

    companion object {
        fun of(srcW: Int, srcH: Int, size: Int): Letterbox {
            val scale = min(size.toFloat() / srcW, size.toFloat() / srcH)
            val nw = Math.round(srcW * scale)
            val nh = Math.round(srcH * scale)
            return Letterbox(scale, (size - nw) / 2, (size - nh) / 2, srcW, srcH)
        }
    }
}

/**
 * Reloj de una tarea de IA: cada modelo se ejecuta como mucho cada `baseMs`, y si tarda mucho en
 * este móvil, se espacia automáticamente (`factor` × su tiempo medio). Así el Honor y el S22+ se
 * adaptan solos sin calentarse.
 */
class TaskClock(private val baseMs: Long, private val factor: Float) {
    var lastRunMs = 0L
        private set
    var averageMs = 0f
        private set

    fun intervalMs(slowdown: Long): Long = (max(baseMs.toFloat(), averageMs * factor) * slowdown).toLong()

    /** >1 si ya toca ejecutarla; cuanto mayor, más atrasada va. */
    fun overdue(now: Long, slowdown: Long): Float =
        (now - lastRunMs).toFloat() / intervalMs(slowdown).coerceAtLeast(1L)

    fun record(now: Long, durationMs: Long) {
        lastRunMs = now
        averageMs = if (averageMs == 0f) durationMs.toFloat() else averageMs * 0.8f + durationMs * 0.2f
    }

    fun reset() {
        lastRunMs = 0L
    }

    /**
     * Olvida la duración media (p. ej. al pasar el modelo al NPU): si no, durante un rato se seguiría
     * programando como si fuera lento y el panel mostraría el tiempo antiguo.
     */
    fun resetAverage() {
        averageMs = 0f
    }
}
