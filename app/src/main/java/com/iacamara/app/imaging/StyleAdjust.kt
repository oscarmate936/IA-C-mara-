package com.iacamara.app.imaging

import kotlin.math.abs
import kotlin.math.max

/**
 * Tu estilo sobre la receta de una escena (Fase 7): pasos de −[MAX] a +[MAX] en cuatro direcciones que
 * cualquiera entiende al mirar una foto. Cero en todo = la receta tal cual.
 */
data class StyleAdjust(
    /** + más cálida (anaranjada), − más fría (azulada). */
    val warmth: Int = 0,
    /** + colores más vivos, − más sobrios. */
    val vivid: Int = 0,
    /** + más contraste y textura, − más suave. */
    val contrast: Int = 0,
    /** + más clara, − más oscura. */
    val bright: Int = 0,
) {
    val isNeutral: Boolean get() = this == NONE

    operator fun plus(o: StyleAdjust) = StyleAdjust(
        (warmth + o.warmth).coerceIn(-MAX, MAX),
        (vivid + o.vivid).coerceIn(-MAX, MAX),
        (contrast + o.contrast).coerceIn(-MAX, MAX),
        (bright + o.bright).coerceIn(-MAX, MAX),
    )

    /** La receta con este estilo. Cada paso es pequeño: se nota al comparar, no cambia la foto. */
    fun applyTo(r: Recipe): Recipe {
        if (isNeutral) return r
        return r.copy(
            warmth = r.warmth + WARMTH_STEP * warmth,
            vibrance = max(0f, r.vibrance * (1f + VIVID_FACTOR * vivid)),
            saturation = r.saturation + SATURATION_STEP * vivid,
            contrast = max(0f, r.contrast * (1f + CONTRAST_FACTOR * contrast)),
            clarity = max(0f, r.clarity * (1f + CLARITY_FACTOR * contrast)),
            targetMid = (r.targetMid + BRIGHT_STEP * bright).coerceIn(0.2f, 0.65f),
        )
    }

    /** "más cálidas, con más contraste" (vacío si es neutro). */
    fun describe(): String = buildList {
        word(warmth, "más cálidas", "más frías")?.let(::add)
        word(vivid, "con colores más vivos", "con colores más sobrios")?.let(::add)
        word(contrast, "con más contraste", "más suaves")?.let(::add)
        word(bright, "más claras", "más oscuras")?.let(::add)
    }.joinToString(", ")

    /** "más cálidas"; con dos pasos o más, "bastante más cálidas". */
    private fun word(v: Int, up: String, down: String): String? {
        if (v == 0) return null
        val text = if (v > 0) up else down
        return if (abs(v) >= 2) text.replaceFirst("más", "bastante más") else text
    }

    companion object {
        val NONE = StyleAdjust()
        const val MAX = 3
        // Un paso se nota al comparar dos versiones; tres pasos, sin exagerar (±6 % de calidez, ±0,09 de brillo).
        const val WARMTH_STEP = 0.02f
        const val VIVID_FACTOR = 0.35f
        const val SATURATION_STEP = 0.04f
        const val CONTRAST_FACTOR = 0.35f
        const val CLARITY_FACTOR = 0.25f
        const val BRIGHT_STEP = 0.03f
    }
}
