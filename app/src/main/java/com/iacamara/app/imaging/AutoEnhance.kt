package com.iacamara.app.imaging

import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SceneType
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Receta de mejora de una escena. Todos los valores son "cuánto" (0 = nada); `strength` del motor
 * los escala a la vez.
 */
data class Recipe(
    /** Curva en S (contraste global). */
    val contrast: Float,
    /** Contraste local ("claridad"): realza texturas sin quemar ni empastar. */
    val clarity: Float,
    /** Saturación que se suma sobre todo a los colores apagados. */
    val vibrance: Float,
    /** Saturación general. */
    val saturation: Float,
    /** Cuánto se corrige el tono de color de la luz (0 = respetarlo, p. ej. en un atardecer). */
    val whiteBalance: Float,
    /** Calidez que se añade (+) o se quita (−), en tanto por uno. */
    val warmth: Float,
    /** Aclarado de las zonas oscuras. */
    val shadows: Float,
    /** Recuperación de las zonas muy claras. */
    val highlights: Float,
    /** Nitidez fina. */
    val sharpen: Float,
    /** Realce del cielo (más profundo y con más color). */
    val sky: Float,
    /** Brillo medio deseado (0–1) al que se acerca la foto, sin llegar del todo. */
    val targetMid: Float,
) {
    fun scaled(k: Float) = copy(
        contrast = contrast * k, clarity = clarity * k, vibrance = vibrance * k, saturation = saturation * k,
        whiteBalance = whiteBalance * k, warmth = warmth * k, shadows = shadows * k, highlights = highlights * k,
        sharpen = sharpen * k, sky = sky * k,
    )

    companion object {
        /** La receta de cada escena: lo que haría un fotógrafo al revelar ese tipo de foto. */
        fun forScene(scene: SceneType): Recipe = when (scene) {
            SceneType.RETRATO, SceneType.SELFIE, SceneType.GRUPO ->
                Recipe(0.10f, 0.10f, 0.12f, 0.00f, 0.6f, 0.01f, 0.30f, 0.25f, 0.20f, 0.3f, 0.46f)
            SceneType.PAISAJE ->
                Recipe(0.16f, 0.28f, 0.25f, 0.03f, 0.5f, 0.00f, 0.25f, 0.35f, 0.40f, 0.8f, 0.44f)
            SceneType.PLAYA, SceneType.NIEVE ->
                Recipe(0.10f, 0.15f, 0.20f, 0.00f, 0.8f, 0.00f, 0.15f, 0.40f, 0.30f, 0.6f, 0.55f)
            SceneType.ATARDECER ->
                Recipe(0.14f, 0.15f, 0.20f, 0.04f, 0.0f, 0.03f, 0.20f, 0.45f, 0.25f, 0.6f, 0.38f)
            SceneType.CIUDAD ->
                Recipe(0.15f, 0.30f, 0.12f, 0.00f, 0.6f, 0.00f, 0.25f, 0.35f, 0.45f, 0.5f, 0.45f)
            SceneType.NOCHE ->
                Recipe(0.08f, 0.06f, 0.10f, 0.00f, 0.4f, 0.00f, 0.15f, 0.30f, 0.12f, 0.0f, 0.30f)
            SceneType.INTERIOR ->
                Recipe(0.10f, 0.12f, 0.12f, 0.00f, 0.7f, 0.00f, 0.30f, 0.25f, 0.25f, 0.0f, 0.45f)
            SceneType.COMIDA ->
                Recipe(0.12f, 0.20f, 0.28f, 0.02f, 0.4f, 0.02f, 0.25f, 0.30f, 0.40f, 0.0f, 0.48f)
            SceneType.FLORES, SceneType.MACRO ->
                Recipe(0.12f, 0.20f, 0.28f, 0.02f, 0.5f, 0.00f, 0.20f, 0.30f, 0.40f, 0.3f, 0.45f)
            SceneType.MASCOTA ->
                Recipe(0.10f, 0.20f, 0.15f, 0.00f, 0.6f, 0.00f, 0.25f, 0.25f, 0.45f, 0.4f, 0.45f)
            SceneType.OBJETO, SceneType.GENERAL ->
                Recipe(0.12f, 0.18f, 0.18f, 0.00f, 0.6f, 0.00f, 0.25f, 0.30f, 0.35f, 0.5f, 0.45f)
        }
    }
}

/** Lo que se sabe de la foto al dispararla, en coordenadas de la foto derecha (0..1). */
data class ShotContext(
    val scene: SceneType,
    val faces: List<NRect> = emptyList(),
    val iso: Int? = null,
    /** La foto ya pasó por la fusión de noche o HDR: se toca menos el tono. */
    val alreadyProcessed: Boolean = false,
)

/**
 * Mejora automática al disparar (Fase 5): una receta por escena con procesado clásico de imagen,
 * guiado por lo que vio la IA (escena, caras) y lo que midió la cámara (ISO).
 *
 * Pasos: balance de blancos moderado → curva de tono (negros, blancos, brillo medio y contraste) →
 * contraste local y sombras/luces sobre una capa de base suavizada → color (vibrancia que protege la
 * piel, realce del cielo) → ruido de color en fotos con ISO alto → nitidez.
 *
 * Todo trabaja sobre la foto entera en memoria (ARGB) y en paralelo por franjas.
 */
object AutoEnhance {

    fun enhance(pixels: IntArray, width: Int, height: Int, context: ShotContext, strength: Float = 1f): IntArray {
        var recipe = Recipe.forScene(context.scene).scaled(strength)
        if (context.alreadyProcessed) {
            // La fusión de noche/HDR ya ajustó el tono: solo color y acabado.
            recipe = recipe.copy(contrast = recipe.contrast * 0.4f, clarity = recipe.clarity * 0.5f, shadows = 0f, highlights = 0f)
        }
        val stats = Stats.of(pixels, width, height)
        val lut = toneLut(stats, recipe, strength)
        val gains = whiteBalance(stats, recipe)
        val out = IntArray(pixels.size)

        // Capa de base (luminancia muy suavizada) a 1/4 de resolución: contraste local, sombras y luces.
        val bw = max(1, width / BASE_SCALE)
        val bh = max(1, height / BASE_SCALE)
        val small = FloatArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            small[y * bw + x] = LumaPlane.luma(pixels[min(height - 1, y * BASE_SCALE) * width + min(width - 1, x * BASE_SCALE)]) / 255f
        }
        val radius = max(2, max(bw, bh) / 40)
        val base = Fusion.boxBlur(Fusion.boxBlur(small, bw, bh, radius), bw, bh, radius)

        // Caras: menos contraste local sobre la piel (no marcar poros ni arrugas).
        val faceBoxes = context.faces.map { f ->
            val mx = f.width * 0.25f
            val my = f.height * 0.3f
            floatArrayOf((f.left - mx) * width, (f.top - my) * height, (f.right + mx) * width, (f.bottom + my) * height)
        }

        val stripe = 64
        Parallel.forRange((height + stripe - 1) / stripe) { s ->
            val y0 = s * stripe
            val y1 = min(height, y0 + stripe)
            for (y in y0 until y1) {
                val by = y.toFloat() / BASE_SCALE
                val skyRow = y < height * 0.62f
                for (x in 0 until width) {
                    val i = y * width + x
                    val p = pixels[i]
                    var r = ((p shr 16) and 0xFF) * gains[0]
                    var g = ((p shr 8) and 0xFF) * gains[1]
                    var b = (p and 0xFF) * gains[2]

                    // Tono: curva sobre la luminancia, conservando el color.
                    val l0 = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 255f)
                    var l = lut[l0.toInt().coerceIn(0, 255)]

                    // Contraste local, sombras y luces con la capa de base.
                    val bv = sampleBase(base, bw, bh, x.toFloat() / BASE_SCALE, by)
                    var clarity = recipe.clarity
                    if (faceBoxes.isNotEmpty()) {
                        for (f in faceBoxes) if (x >= f[0] && x <= f[2] && y >= f[1] && y <= f[3]) clarity *= 0.3f
                    }
                    val detail = (l0 / 255f - bv).coerceIn(-0.25f, 0.25f)
                    var lf = l / 255f + clarity * detail
                    if (bv < 0.5f) lf += recipe.shadows * 0.22f * (1f - bv / 0.5f).pow(2) * (1f - lf)
                    if (bv > 0.7f) lf -= recipe.highlights * 0.18f * ((bv - 0.7f) / 0.3f).pow(2) * lf
                    l = (lf * 255f).coerceIn(0f, 255f)
                    // Los píxeles muy oscuros no se multiplican sin límite (sacaría el ruido de color).
                    val k = if (l0 > 0.5f) (l / l0).coerceAtMost(4f) else 1f
                    r *= k
                    g *= k
                    b *= k

                    // Color: vibrancia (más en colores apagados, menos en la piel) y saturación.
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val sat = if (mx > 1f) (mx - mn) / mx else 0f
                    val hue = hueOf(r, g, b, mx, mn)
                    val skin = hue in 5f..50f && sat in 0.12f..0.65f && lum > 50f
                    var boost = recipe.vibrance * (1f - sat) + recipe.saturation
                    if (skin) boost *= 0.35f
                    // Cielo: azul, claro y en la parte de arriba → algo más profundo y con más color.
                    if (recipe.sky > 0f && skyRow && hue in 185f..250f && lum > 90f && sat > 0.08f) {
                        val fade = 1f - (y / (height * 0.62f)).pow(2)
                        boost += 0.18f * recipe.sky * fade
                        val darken = 1f - 0.07f * recipe.sky * fade
                        r *= darken
                        g *= darken
                        b *= darken
                    }
                    val lum2 = 0.299f * r + 0.587f * g + 0.114f * b
                    r = lum2 + (r - lum2) * (1f + boost)
                    g = lum2 + (g - lum2) * (1f + boost)
                    b = lum2 + (b - lum2) * (1f + boost)
                    if (recipe.warmth != 0f) {
                        r *= 1f + recipe.warmth
                        b *= 1f - recipe.warmth
                    }
                    out[i] = softClip(r, g, b)
                }
            }
        }

        // Ruido de color en fotos con ISO alto o de noche.
        val iso = context.iso ?: 0
        if (iso >= 1600 || context.scene == SceneType.NOCHE) chromaDenoise(out, width, height, if (iso >= 3200) 3 else 2)
        if (recipe.sharpen > 0.01f) {
            val threshold = when {
                iso >= 3200 -> 7
                iso >= 1600 -> 5
                else -> 3
            }
            ToneCurve.sharpen(out, width, height, amount = recipe.sharpen, threshold = threshold)
        }
        return out
    }

    // ---------------------------------------------------------------- estadísticas y curvas

    /**
     * @param lnRG, lnBG media de ln(R/G) y ln(B/G) en los tonos medios: el color medio de la escena.
     */
    internal class Stats(val p01: Float, val p50: Float, val p995: Float, val lnRG: Float, val lnBG: Float) {
        companion object {
            fun of(pixels: IntArray, width: Int, height: Int): Stats {
                val hist = IntArray(256)
                var srg = 0.0
                var sbg = 0.0
                var n = 0
                // Una muestra de unos 400.000 píxeles basta para el histograma.
                val step = max(1, kotlin.math.sqrt(width * height / 400_000.0).toInt())
                var y = 0
                while (y < height) {
                    var x = 0
                    while (x < width) {
                        val p = pixels[y * width + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val l = LumaPlane.luma(p)
                        hist[l]++
                        // Para el color de la luz: tonos medios sin colores muy vivos.
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        if (l in 40..225 && mx - mn < 0.6f * mx) {
                            srg += ln((r + 1.0) / (g + 1.0))
                            sbg += ln((b + 1.0) / (g + 1.0))
                            n++
                        }
                        x += step
                    }
                    y += step
                }
                val total = hist.sum().coerceAtLeast(1)
                fun pct(q: Float): Float {
                    var acc = 0
                    val target = (total * q).toInt()
                    for (v in 0 until 256) {
                        acc += hist[v]
                        if (acc > target) return v / 255f
                    }
                    return 1f
                }
                val nn = max(1, n).toDouble()
                return Stats(pct(0.01f), pct(0.5f), pct(0.995f), (srg / nn).toFloat(), (sbg / nn).toFloat())
            }
        }
    }

    /**
     * Ganancias de color (R, G, B) contra la dominante de la luz. Solo se corrige de verdad en el eje
     * azul–ámbar (luz de día, sombra, bombilla…); en el eje verde–magenta apenas, porque ahí el color
     * medio suele ser la propia escena (césped, hojas) y no la luz.
     */
    internal fun whiteBalance(stats: Stats, recipe: Recipe): FloatArray {
        if (recipe.whiteBalance <= 0f) return floatArrayOf(1f, 1f, 1f)
        val amber = (stats.lnRG - stats.lnBG) / 2f // > 0: luz cálida
        val magenta = (stats.lnRG + stats.lnBG) / 2f // > 0: magenta, < 0: verde
        val fixAmber = -amber.coerceIn(-ln(1.12f), ln(1.12f)) * recipe.whiteBalance
        val fixMagenta = -magenta.coerceIn(-ln(1.03f), ln(1.03f)) * recipe.whiteBalance
        var gr = kotlin.math.exp(fixAmber + fixMagenta)
        var gb = kotlin.math.exp(-fixAmber + fixMagenta)
        var gg = 1f
        // Misma luminosidad que antes de corregir.
        val norm = 0.299f * gr + 0.587f * gg + 0.114f * gb
        gr /= norm
        gg /= norm
        gb /= norm
        return floatArrayOf(gr, gg, gb)
    }

    /** Curva de tono (0–255 → 0–255): negros, blancos, brillo medio y contraste en S. */
    internal fun toneLut(stats: Stats, recipe: Recipe, strength: Float): FloatArray {
        val black = min(stats.p01, 0.08f) * 0.7f * strength
        // Blancos: si la foto no llega a blanco se estira un poco (como mucho ×1,25 y sin llegar del
        // todo), para no quemar ni cambiar el ambiente de una escena oscura.
        val gainW = if (stats.p995 < 0.92f) min(1.25f, 0.97f / max(stats.p995, 0.05f)) else 1f
        val white = 1f / (1f + (gainW - 1f) * 0.7f * strength)
        val mid = ((stats.p50 - black) / max(0.05f, white - black)).coerceIn(0.02f, 0.98f)
        var gamma = (ln(recipe.targetMid) / ln(mid)).coerceIn(0.7f, 1.35f)
        gamma = 1f + (gamma - 1f) * 0.55f * strength
        val lut = FloatArray(256)
        for (v in 0 until 256) {
            var x = ((v / 255f - black) / (white - black)).coerceIn(0f, 1f)
            x = x.pow(gamma)
            // Curva en S suave alrededor de 0,5.
            x += recipe.contrast * 2f * x * (1f - x) * (2f * x - 1f)
            lut[v] = (x.coerceIn(0f, 1f) * 255f)
        }
        return lut
    }

    private fun sampleBase(base: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = min(w - 1, x0 + 1)
        val y1 = min(h - 1, y0 + 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val a = base[y0 * w + x0] + (base[y0 * w + x1] - base[y0 * w + x0]) * fx
        val c = base[y1 * w + x0] + (base[y1 * w + x1] - base[y1 * w + x0]) * fx
        return a + (c - a) * fy
    }

    internal fun hueOf(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d < 1e-3f) return -1f
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /** Si un canal se sale, se acerca el color al gris en vez de recortarlo (sin manchas de color). */
    private fun softClip(r: Float, g: Float, b: Float): Int {
        val mx = max(r, max(g, b))
        if (mx <= 255f) return argb(r.toInt(), g.toInt(), b.toInt())
        val l = (0.299f * r + 0.587f * g + 0.114f * b).coerceAtMost(255f)
        val t = ((255f - l) / max(1e-3f, mx - l)).coerceIn(0f, 1f)
        return argb((l + (r - l) * t).toInt(), (l + (g - l) * t).toInt(), (l + (b - l) * t).toInt())
    }

    /** Suaviza el color (no la luz): quita las manchas de colores del ruido sin perder detalle. */
    internal fun chromaDenoise(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val n = width * height
        val cr = FloatArray(n)
        val cb = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            cr[i] = r - l
            cb[i] = b - l
        }
        val sr = Fusion.boxBlur(cr, width, height, radius)
        val sb = Fusion.boxBlur(cb, width, height, radius)
        for (i in 0 until n) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = l + sr[i]
            val nb = l + sb[i]
            val ng = (l - 0.299f * nr - 0.114f * nb) / 0.587f
            pixels[i] = argb(nr.toInt(), ng.toInt(), nb.toInt())
        }
    }

    private const val BASE_SCALE = 4
}
