package com.iacamara.app.coach

import com.iacamara.app.perception.FaceDetail
import com.iacamara.app.perception.FaceLight
import com.iacamara.app.perception.SceneType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lo que sabía la cámara en el momento del disparo, para la crítica y los hábitos. */
data class ShotReview(
    val scene: SceneType,
    /** Puntuación del encuadre (0–100) y el consejo que había en ese momento. */
    val compositionScore: Int?,
    val compositionTip: String?,
    val criteria: Map<String, Float>,
    /** Desenfoque previsto por el pulso (píxeles) y si algo se movía. */
    val blurPx: Float?,
    val moving: Boolean,
    val lowLight: Boolean,
    val backlit: Boolean,
    val faceLight: FaceLight?,
    /** Técnica usada y por qué (del Director). */
    val technique: String,
    val reason: String,
    /** La técnica junta o elige entre varias fotos (la nitidez depende menos del pulso de una sola). */
    val burst: Boolean,
    /** Fallos de encuadre en el disparo, para los hábitos. */
    val flags: Set<Habit>,
)

/** Medidas de la foto guardada (reducida, unos 1000 px). */
data class PhotoStats(
    val mean: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val saturation: Float,
    /** Color de la luz medido en las superficies casi neutras: > 0 cálido / magenta. */
    val amber: Float,
    val magenta: Float,
    /** Fracción de la foto que son superficies casi neutras (si es muy poca, el color de la luz no se sabe). */
    val neutral: Float,
) {
    companion object {
        fun of(pixels: IntArray, w: Int, h: Int): PhotoStats {
            var n = 0
            var sum = 0.0
            var high = 0
            var low = 0
            var sat = 0.0
            var srg = 0.0
            var sbg = 0.0
            var nn = 0
            val step = max(1, kotlin.math.sqrt(w * h / 250_000.0).toInt())
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val p = pixels[y * w + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val l = (77 * r + 150 * g + 29 * b) shr 8
                    sum += l
                    if (l >= 250) high++
                    if (l <= 5) low++
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    if (mx > 0) sat += (mx - mn) / mx.toDouble()
                    if (l in 40..235 && mx - mn < 0.35f * mx) {
                        srg += ln((r + 1.0) / (g + 1.0))
                        sbg += ln((b + 1.0) / (g + 1.0))
                        nn++
                    }
                    n++
                    x += step
                }
                y += step
            }
            if (n == 0) return PhotoStats(0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val rg = if (nn > 0) srg / nn else 0.0
            val bg = if (nn > 0) sbg / nn else 0.0
            return PhotoStats(
                mean = (sum / n / 255.0).toFloat(),
                highlightClip = high / n.toFloat(),
                shadowClip = low / n.toFloat(),
                saturation = (sat / n).toFloat(),
                amber = ((rg - bg) / 2).toFloat(),
                magenta = ((rg + bg) / 2).toFloat(),
                neutral = nn / n.toFloat(),
            )
        }
    }
}

/** Un apartado de la crítica: nota 0–100 y un comentario corto. */
data class CritiquePart(val name: String, val score: Int, val note: String)

/**
 * La crítica del fotógrafo: nota general, nota por apartados, qué fue lo mejor, 2–3 consejos para la
 * próxima vez y por qué se usó esa técnica.
 */
data class Critique(
    val overall: Int,
    val parts: List<CritiquePart>,
    val best: String?,
    val tips: List<String>,
    val technique: String,
)

/**
 * Crítica de cada foto con medidas reales: el encuadre en el disparo (coach), la luz y el color de la
 * foto guardada, la nitidez prevista por el pulso y el tiempo de exposición, el momento (ojos abiertos en
 * la foto final) y la nota de estética aprendida (NIMA).
 */
object PhotoCritic {

    fun critique(
        review: ShotReview,
        stats: PhotoStats,
        faces: List<FaceDetail>?,
        aesthetic: Float?,
        habits: List<HabitCount>,
    ): Critique {
        val parts = mutableListOf<CritiquePart>()
        // Consejos candidatos con su gravedad (cuánto le falta a su apartado).
        val tips = mutableListOf<Pair<Int, String>>()
        val night = review.scene == SceneType.NOCHE || review.lowLight

        // --- Encuadre.
        review.compositionScore?.let { score ->
            parts += CritiquePart("Composición", score, if (score >= 80) "buen encuadre" else if (score >= 60) "mejorable" else "flojo")
            if (score < 75) {
                val text = review.compositionTip?.let { "Encuadre: ${it.replaceFirstChar { c -> c.lowercase() }}" }
                    ?: "Encuadre: coloca el sujeto en un punto fuerte de la guía"
                tips += (100 - score) to text
            }
        }

        // --- Luz.
        var light = 100f
        var lightNote = "bien expuesta"
        if (stats.highlightClip > 0.01f) {
            val p = min(35f, (stats.highlightClip - 0.01f) * 700f)
            light -= p
            lightNote = "luces quemadas"
            tips += p.roundToInt() + 10 to "Luces quemadas: antes de disparar, toca la zona más clara para bajar la exposición (o usa HDR)"
        }
        if (!night && stats.shadowClip > 0.05f) {
            val p = min(25f, (stats.shadowClip - 0.05f) * 300f)
            light -= p
            if (p > 8f) {
                lightNote = "sombras sin detalle"
                tips += p.roundToInt() to "Sombras sin detalle: el modo HDR las recupera"
            }
        }
        val darkLimit = if (night) 0.12f else 0.22f
        if (stats.mean < darkLimit) {
            val p = min(30f, (darkLimit - stats.mean) * 200f)
            light -= p
            lightNote = "algo oscura"
            tips += p.roundToInt() + 5 to if (night) "Quedó muy oscura: usa el modo Noche y apoya el móvil" else "Quedó oscura: toca el sujeto para medir la luz ahí"
        } else if (stats.mean > 0.72f) {
            light -= min(20f, (stats.mean - 0.72f) * 150f)
            lightNote = "algo clara"
        }
        review.faceLight?.let { fl ->
            if (fl.clipped > 0.12f) {
                light -= 15f
                lightNote = "cara quemada"
                tips += 30 to "La cara se quemó: toca la cara antes de disparar para medir la luz ahí"
            } else if (!night && fl.eyeShadow > 0.3f) {
                light -= 12f
                lightNote = "ojos en sombra"
                tips += 25 to "Con sol de mediodía, busca una sombra abierta para los retratos: los ojos no quedan oscuros"
            }
        }
        if (review.backlit) {
            light -= 15f
            lightNote = "contraluz"
            tips += 30 to "Con la luz detrás, gira a la persona hacia la luz o usa el flash de relleno"
        }
        parts += CritiquePart("Luz", light.roundToInt().coerceIn(0, 100), lightNote)

        // --- Nitidez (prevista por el pulso, el tiempo de exposición y el zoom).
        review.blurPx?.let { blur ->
            val effective = if (review.burst) blur * 0.6f else blur
            var sharp = when {
                effective <= 1.5f -> 100f
                effective <= 3f -> 100f - (effective - 1.5f) * 10f
                effective <= 6f -> 85f - (effective - 3f) * 8f
                else -> max(25f, 61f - (effective - 6f) * 4f)
            }
            if (review.moving && !review.burst) sharp -= 10f
            val score = sharp.roundToInt().coerceIn(0, 100)
            val note = if (score >= 85) "estable" else if (score >= 60) "algo movida" else "movida"
            parts += CritiquePart("Nitidez", score, note)
            if (score < 75) {
                tips += (100 - score) to if (night) {
                    "Con poca luz, apoya el móvil en algo firme o los codos en el cuerpo"
                } else {
                    "Salió algo movida: sujeta el móvil con las dos manos y dispara con suavidad (o usa el disparo inteligente)"
                }
            }
        }

        // --- Momento (personas en la foto final).
        if (!faces.isNullOrEmpty()) {
            val closed = faces.count { !it.eyesOpen }
            val score = if (closed == 0) 95 else max(30, 100 - 60 * closed / faces.size)
            parts += CritiquePart("Momento", score, if (closed == 0) "ojos abiertos" else if (closed == 1) "alguien cerró los ojos" else "ojos cerrados")
            if (closed > 0) tips += (100 - score) to "Alguien cerró los ojos: con personas, espera a que todos miren (el modo Foto hace 4 fotos y elige)"
        }

        // --- Color.
        var color = 100f
        var colorNote = "natural"
        if (stats.neutral >= 0.03f) {
            val warmScene = review.scene == SceneType.ATARDECER || review.scene == SceneType.NOCHE || review.scene == SceneType.INTERIOR
            val tolerance = if (warmScene) 0.25f else 0.08f
            val excess = abs(stats.amber) - tolerance
            if (excess > 0f) {
                val p = min(30f, excess * 200f)
                color -= p
                colorNote = if (stats.amber > 0f) "tono anaranjado" else "tono azulado"
                tips += p.roundToInt() to if (stats.amber > 0f) {
                    "La luz tiñe la foto de naranja: la copia mejorada lo corrige (o busca luz de ventana)"
                } else {
                    "Tono azulado (sombra o nublado): la copia mejorada lo calienta un poco"
                }
            }
            val pink = abs(stats.magenta) - 0.05f
            if (pink > 0f) {
                val p = min(30f, pink * 300f)
                color -= p
                colorNote = if (stats.magenta > 0f) "tono rosado" else "tono verdoso"
                tips += p.roundToInt() to "Tono ${if (stats.magenta > 0f) "rosado" else "verdoso"}: la copia mejorada lo corrige"
            }
        }
        if (stats.saturation < 0.12f && review.scene != SceneType.NOCHE && review.scene != SceneType.NIEVE) {
            color -= 15f
            colorNote = "colores apagados"
            tips += 15 to "Colores apagados: busca una luz más directa o de lado"
        } else if (stats.saturation > 0.6f) {
            color -= 10f
            colorNote = "colores muy saturados"
        }
        parts += CritiquePart("Color", color.roundToInt().coerceIn(0, 100), colorNote)

        // --- Nota general: media ponderada + la estética aprendida (NIMA) como ajuste.
        val weights = mapOf("Composición" to 0.3f, "Luz" to 0.25f, "Nitidez" to 0.2f, "Momento" to 0.1f, "Color" to 0.15f)
        val total = parts.sumOf { (weights[it.name] ?: 0.1f).toDouble() }
        var overall = (parts.sumOf { (weights[it.name] ?: 0.1f) * it.score.toDouble() } / max(1e-6, total)).toFloat()
        aesthetic?.let { overall += ((it - 4.5f) * 4f).coerceIn(-8f, 8f) }

        // --- Consejos: los dos más importantes y, si hay un hábito, uno sobre él.
        val chosen = tips.sortedByDescending { it.first }.map { it.second }.distinct().take(2).toMutableList()
        habits.firstOrNull()?.let { chosen += habitTip(it) }
        val best = parts.filter { it.score >= 80 }.maxByOrNull { it.score }?.let { "Lo mejor: ${it.name.lowercase()} (${it.note})" }
        return Critique(
            overall = overall.roundToInt().coerceIn(0, 100),
            parts = parts,
            best = best,
            tips = chosen,
            technique = "${review.technique}: ${review.reason}",
        )
    }

    /** Consejo sobre un hábito: cuántas veces pasa y qué hacer (o qué hace ya la cámara). */
    fun habitTip(c: HabitCount): String = when (c.habit) {
        Habit.TORCIDO -> "Llevas ${c.text} con el horizonte torcido: activa el nivel (📏) para verlo al encuadrar; la copia mejorada las endereza"
        Habit.CENTRADO -> "Llevas ${c.text} con el sujeto en el centro: prueba a ponerlo en un punto fuerte de la guía"
        Habit.MOVIDA -> "Llevas ${c.text} movidas: mientras tanto la cámara hace 4 fotos y elige la más nítida"
        Habit.HORIZONTE_MEDIO -> "Llevas ${c.text} con el horizonte en medio: súbelo o bájalo a un tercio"
        Habit.CORTES -> "Llevas ${c.text} con el sujeto cortado por el borde: deja un poco de margen"
    }
}
