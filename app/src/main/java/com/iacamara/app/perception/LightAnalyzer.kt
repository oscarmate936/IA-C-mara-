package com.iacamara.app.perception

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Histograma, luces quemadas, contraluz y color del cielo a partir del fotograma. */
class LightAnalyzer {

    private var buffer = IntArray(0)

    /** @param subject zona del sujeto en coordenadas normalizadas de este bitmap (o null = centro). */
    fun analyze(bitmap: Bitmap, subject: NRect?): LightInfo {
        val w = bitmap.width
        val h = bitmap.height
        if (buffer.size != w * h) buffer = IntArray(w * h)
        bitmap.getPixels(buffer, 0, w, 0, 0, w, h)

        val zone = subject ?: NRect(0.3f, 0.3f, 0.7f, 0.7f)
        val sx0 = (zone.left * w).toInt()
        val sx1 = (zone.right * w).toInt()
        val sy0 = (zone.top * h).toInt()
        val sy1 = (zone.bottom * h).toInt()
        val topLimit = h / 3

        val hist = IntArray(BINS)
        var count = 0
        var sum = 0L
        var high = 0
        var low = 0
        var subjectSum = 0L
        var subjectCount = 0
        var backgroundSum = 0L
        var backgroundCount = 0
        var topCount = 0
        var sky = 0
        var warm = 0
        var neutral = 0
        var saturationSum = 0L
        val rowMeans = FloatArray((h + STEP - 1) / STEP)

        var y = 0
        while (y < h) {
            val row = y * w
            val inSubjectRow = y in sy0..sy1
            var rowSum = 0L
            var rowCount = 0
            var x = 0
            while (x < w) {
                val c = buffer[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (77 * r + 150 * g + 29 * b) shr 8
                hist[luma * BINS / 256]++
                sum += luma
                count++
                if (luma >= 250) high++
                if (luma <= 6) low++
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                saturationSum += mx - mn
                if (inSubjectRow && x in sx0..sx1) {
                    subjectSum += luma
                    subjectCount++
                } else {
                    backgroundSum += luma
                    backgroundCount++
                }
                if (y < topLimit) {
                    topCount++
                    if (b > r + 15 && b >= g - 5 && luma > 90) sky++
                    if (r > g + 20 && g > b + 5 && luma > 60) warm++
                }
                if (luma > 170 && mx - mn < 28) neutral++
                rowSum += luma
                rowCount++
                x += STEP
            }
            rowMeans[y / STEP] = if (rowCount > 0) rowSum / rowCount.toFloat() else 0f
            y += STEP
        }

        val maxBin = hist.maxOrNull()?.coerceAtLeast(1) ?: 1
        val n = count.coerceAtLeast(1).toFloat()
        val meanLuma = sum / n
        return LightInfo(
            histogram = FloatArray(BINS) { hist[it] / maxBin.toFloat() },
            meanLuma = meanLuma,
            highlightClip = high / n,
            shadowClip = low / n,
            subjectLuma = if (subjectCount > 0) subjectSum / subjectCount.toFloat() else meanLuma,
            backgroundLuma = if (backgroundCount > 0) backgroundSum / backgroundCount.toFloat() else meanLuma,
            skyBlue = sky / topCount.coerceAtLeast(1).toFloat(),
            warmTop = warm / topCount.coerceAtLeast(1).toFloat(),
            brightNeutral = neutral / n,
            saturation = saturationSum / n / 255f,
            horizonY = findHorizon(rowMeans),
        )
    }

    companion object {
        private const val BINS = 32
        private const val STEP = 2

        /**
         * Busca la fila donde la imagen pasa de un cielo claro a una tierra más oscura (el horizonte).
         * Devuelve su altura (0 arriba … 1 abajo) o null si no hay un cambio claro.
         */
        fun findHorizon(rowMeans: FloatArray): Float? {
            val n = rowMeans.size
            if (n < 20) return null
            val band = (n * 0.06f).toInt().coerceAtLeast(3)
            var bestRow = -1
            var bestDrop = 0f
            for (r in (n * 0.15f).toInt()..(n * 0.85f).toInt()) {
                if (r - band < 0 || r + band > n) continue
                var above = 0f
                var below = 0f
                for (k in 1..band) {
                    above += rowMeans[r - k]
                    below += rowMeans[r + k - 1]
                }
                above /= band
                below /= band
                val drop = above - below
                if (drop > bestDrop && above >= 100f) {
                    bestDrop = drop
                    bestRow = r
                }
            }
            return if (bestRow >= 0 && bestDrop >= 25f) bestRow / n.toFloat() else null
        }
    }
}
