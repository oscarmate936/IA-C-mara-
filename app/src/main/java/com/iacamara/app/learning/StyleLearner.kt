package com.iacamara.app.learning

import com.iacamara.app.imaging.StyleAdjust
import com.iacamara.app.perception.SceneType
import java.util.EnumMap
import java.util.Locale
import kotlin.random.Random

/** Grupos de escenas para aprender tu estilo (con pocas fotos de cada tipo no se aprendería nada). */
enum class SceneGroup(val label: String, val scenes: Set<SceneType>) {
    PERSONAS("Personas", setOf(SceneType.RETRATO, SceneType.SELFIE, SceneType.GRUPO)),
    PAISAJES("Paisajes", setOf(SceneType.PAISAJE, SceneType.PLAYA, SceneType.NIEVE)),
    ATARDECERES("Atardeceres", setOf(SceneType.ATARDECER)),
    CIUDAD("Ciudad y arquitectura", setOf(SceneType.CIUDAD)),
    NOCHE("Noche", setOf(SceneType.NOCHE)),
    INTERIOR("Interiores", setOf(SceneType.INTERIOR)),
    COMIDA("Comida", setOf(SceneType.COMIDA)),
    DETALLES("Flores y detalles", setOf(SceneType.FLORES, SceneType.MACRO, SceneType.OBJETO)),
    ANIMALES("Animales", setOf(SceneType.MASCOTA)),
    OTRAS("Otras fotos", setOf(SceneType.GENERAL)),
    ;

    companion object {
        fun of(scene: SceneType): SceneGroup = entries.firstOrNull { scene in it.scenes } ?: OTRAS
    }
}

/** Los cuatro ajustes de tu estilo. */
enum class Dimension(val label: String) {
    CALIDEZ("Calidez"),
    COLOR("Color"),
    CONTRASTE("Contraste"),
    LUZ("Luz"),
    ;

    fun step(dir: Int): StyleAdjust = when (this) {
        CALIDEZ -> StyleAdjust(warmth = dir)
        COLOR -> StyleAdjust(vivid = dir)
        CONTRASTE -> StyleAdjust(contrast = dir)
        LUZ -> StyleAdjust(bright = dir)
    }
}

/** Las versiones que se pueden probar: tu estilo tal cual o un paso en una dirección. */
enum class Variant(val dimension: Dimension?, val dir: Int, val label: String) {
    BASE(null, 0, "tu estilo"),
    CALIDA(Dimension.CALIDEZ, 1, "más cálidas"),
    FRIA(Dimension.CALIDEZ, -1, "más frías"),
    VIVA(Dimension.COLOR, 1, "con colores más vivos"),
    SOBRIA(Dimension.COLOR, -1, "con colores más sobrios"),
    CONTRASTE(Dimension.CONTRASTE, 1, "con más contraste"),
    SUAVE(Dimension.CONTRASTE, -1, "más suaves"),
    CLARA(Dimension.LUZ, 1, "más claras"),
    OSCURA(Dimension.LUZ, -1, "más oscuras"),
    ;

    val delta: StyleAdjust get() = dimension?.step(dir) ?: StyleAdjust.NONE

    /** El paso en sentido contrario. */
    val opposite: Variant get() = entries.firstOrNull { it.dimension == dimension && it.dir == -dir } ?: BASE

    companion object {
        fun of(dimension: Dimension, dir: Int): Variant =
            entries.firstOrNull { it.dimension == dimension && it.dir == dir } ?: BASE
    }
}

/** Algo nuevo aprendido: en este tipo de fotos te gustan [variant] y tu estilo pasa a ser [style]. */
data class Learned(val group: SceneGroup, val variant: Variant, val style: StyleAdjust) {
    val text: String get() = "Aprendido: tus fotos de ${group.label.lowercase()}, ${variant.label}"
}

/**
 * "Tu estilo": aprende, para cada grupo de escenas, cómo te gusta la mejora automática. De dos formas:
 *
 * - **Afinar mi estilo** (la principal): como en el óptico, con una foto tuya se enseñan tres versiones de
 *   un ajuste (un paso menos, igual y un paso más) y eliges la que más te gusta. Cuando una dirección
 *   gana a las otras por [TASTE_LEAD] elecciones, tu estilo da ese paso. Se pregunta siempre por el ajuste
 *   menos decidido. En simulación, con ~20 elecciones llega a tu gusto aunque esté a 3 pasos; si te gusta
 *   la receta tal cual, no se mueve.
 * - **👍/👎 y lo que borras** (poco a poco): en algunas fotos (≈ 1 de cada 3) la copia mejorada se hace
 *   con una versión algo distinta (bandido contextual con muestreo de Thompson: las que te gustan se
 *   eligen más). Con valoraciones sueltas hace falta mucha más información para estar seguro, así que tu
 *   estilo solo se mueve cuando una versión te gusta claramente más que el actual: al menos
 *   [MIN_TRIALS] valoraciones y una diferencia de [Z_PROMOTE] veces su incertidumbre (≈ 98 %).
 *
 * Al dar un paso no se tira lo sabido: la versión que ganó pasa a ser tu estilo con sus valoraciones, y
 * tu estilo anterior queda como el paso de vuelta. Si dejas de valorar, deja de probar versiones.
 */
class StyleLearner(private val random: Random = Random.Default) {

    class Group(var style: StyleAdjust = StyleAdjust.NONE, var photos: Int = 0, var ratings: Int = 0) {
        val arms: EnumMap<Variant, Arm> = EnumMap<Variant, Arm>(Variant::class.java).apply {
            Variant.entries.forEach { put(it, Arm()) }
        }

        /** Elecciones de la prueba de gusto por ajuste: (un paso menos, igual, un paso más). */
        val votes: Array<FloatArray> = Array(Dimension.entries.size) { FloatArray(3) }

        /** Elecciones hechas en la prueba de gusto. */
        var choices: Int = 0
    }

    private val groups = EnumMap<SceneGroup, Group>(SceneGroup::class.java)

    /** Fotos con una versión de prueba desde la última valoración. */
    private var unanswered = 0

    @Synchronized
    fun style(group: SceneGroup): StyleAdjust = groups[group]?.style ?: StyleAdjust.NONE

    /** Lo aprendido de cada grupo con alguna valoración o un estilo propio. */
    @Synchronized
    fun summary(): List<Pair<SceneGroup, Group>> =
        SceneGroup.entries.mapNotNull { g ->
            groups[g]?.takeIf { it.ratings > 0 || it.choices > 0 || !it.style.isNeutral }?.let { g to it }
        }

    /**
     * Qué versión usar en la copia mejorada de una foto nueva.
     * @param explore se pueden probar versiones (el aprendizaje está activado).
     */
    @Synchronized
    fun choose(group: SceneGroup, explore: Boolean = true): Variant {
        val g = state(group)
        g.photos++
        if (!explore || unanswered >= MAX_UNANSWERED || random.nextFloat() >= EXPLORE) return Variant.BASE
        val options = Variant.entries.filter { it != Variant.BASE && g.style + it.delta != g.style }
        val chosen = options.maxByOrNull { g.arms.getValue(it).sample(random) } ?: return Variant.BASE
        unanswered++
        return chosen
    }

    /**
     * ¿Se pregunta si te gusta la copia? Siempre mientras valoras; si llevas muchas fotos sin valorar, solo
     * de vez en cuando (1 de cada [ASK_EVERY]), para no insistir.
     */
    @Synchronized
    fun shouldAsk(): Boolean = unanswered < MAX_UNANSWERED || groups.values.sumOf { it.photos } % ASK_EVERY == 0

    /** El estilo con el que se revela la copia mejorada. */
    @Synchronized
    fun adjust(group: SceneGroup, variant: Variant): StyleAdjust = style(group) + variant.delta

    /**
     * Te gustó (o no) la copia mejorada hecha con [variant].
     * @param weight 1 para una valoración tuya; menos para lo que se deduce (p. ej. qué copia borras).
     * @return lo aprendido, si tu estilo cambió.
     */
    @Synchronized
    fun feedback(group: SceneGroup, variant: Variant, liked: Boolean, weight: Float = 1f): Learned? {
        unanswered = 0
        val g = state(group)
        g.ratings++
        g.arms.getValue(variant).add(liked, weight)
        val base = g.arms.getValue(Variant.BASE)
        val best = Variant.entries
            .filter { it != Variant.BASE && g.arms.getValue(it).trials >= MIN_TRIALS }
            .maxByOrNull { g.arms.getValue(it).advantageOver(base) } ?: return null
        if (g.arms.getValue(best).advantageOver(base) < Z_PROMOTE) return null
        return move(group, g, best)
    }

    /** Prueba de gusto: el ajuste del que menos se sabe aún (el de menos ventaja entre sus opciones). */
    @Synchronized
    fun tasteDimension(group: SceneGroup): Dimension {
        val g = state(group)
        return Dimension.entries.minWith(
            compareBy<Dimension>({ lead(g.votes[it.ordinal]) }, { g.votes[it.ordinal].sum() }, { random.nextFloat() }),
        )
    }

    /** Las versiones de la prueba: dirección (−1, 0, +1) y estilo; sin repetir (en el tope solo hay dos). */
    @Synchronized
    fun tasteOptions(group: SceneGroup, dimension: Dimension): List<Pair<Int, StyleAdjust>> {
        val center = style(group)
        return listOf(-1, 0, 1).map { it to center + dimension.step(it) }.distinctBy { it.second }
    }

    /**
     * Elegiste la versión [dir] (−1, 0 o +1) de [dimension]; null = "me da igual" (cuenta un poco a favor de
     * no tocar). @return lo aprendido, si tu estilo cambió.
     */
    @Synchronized
    fun tasteVote(group: SceneGroup, dimension: Dimension, dir: Int?): Learned? {
        val g = state(group)
        g.choices++
        unanswered = 0
        val v = g.votes[dimension.ordinal]
        if (dir == null) {
            v[1] += 0.5f
            return null
        }
        v[dir.coerceIn(-1, 1) + 1] += 1f
        for (d in intArrayOf(-1, 1)) {
            val mine = v[d + 1]
            val others = maxOf(v[1], v[-d + 1])
            if (mine - others >= TASTE_LEAD && g.style + dimension.step(d) != g.style) {
                return move(group, g, Variant.of(dimension, d))
            }
        }
        return null
    }

    /** Tu estilo da un paso; lo sabido se reaprovecha (ver la descripción de la clase). */
    private fun move(group: SceneGroup, g: Group, variant: Variant): Learned? {
        val dim = variant.dimension ?: return null
        val next = g.style + variant.delta
        if (next == g.style) return null
        g.style = next
        // 👍/👎: la versión que ganó es ahora tu estilo; tu estilo anterior, el paso de vuelta; un paso
        // más allá aún no se sabe; las demás se comparaban con el estilo anterior: cuentan la mitad.
        val won = g.arms.getValue(variant).let { Arm(it.good, it.bad) }
        val before = g.arms.getValue(Variant.BASE).let { Arm(it.good, it.bad) }
        g.arms.values.forEach { it.decay(0.5f) }
        g.arms[Variant.BASE] = won
        g.arms[variant.opposite] = before
        g.arms[variant] = Arm()
        // Prueba de gusto: lo mismo con las elecciones de ese ajuste.
        val v = g.votes[dim.ordinal]
        g.votes[dim.ordinal] = if (variant.dir > 0) floatArrayOf(v[1], v[2], 0f) else floatArrayOf(0f, v[0], v[1])
        return Learned(group, variant, next)
    }

    /** Ventaja de la opción más elegida sobre la siguiente. */
    private fun lead(v: FloatArray): Float {
        val sorted = v.sortedDescending()
        return sorted[0] - sorted[1]
    }

    /** Olvida todo. */
    @Synchronized
    fun clear() {
        groups.clear()
        unanswered = 0
    }

    private fun state(group: SceneGroup): Group = groups.getOrPut(group) { Group() }

    /**
     * Una línea por grupo: "PERSONAS;1,0,1,0;23;9;BASE=3.000:2.000,CALIDA=…;0:1:2.5|…;7" (estilo, fotos,
     * valoraciones, 👍/👎 de cada versión, elecciones de la prueba de gusto y cuántas) y "sin_respuesta;N".
     */
    @Synchronized
    fun encode(): String = buildString {
        append("sin_respuesta;").append(unanswered).append('\n')
        for ((group, g) in groups) {
            val s = g.style
            append(group.name).append(';')
            append("${s.warmth},${s.vivid},${s.contrast},${s.bright}").append(';')
            append(g.photos).append(';').append(g.ratings).append(';')
            append(g.arms.entries.joinToString(",") { (v, a) -> "${v.name}=${a.encode()}" }).append(';')
            append(g.votes.joinToString("|") { v -> v.joinToString(":") { "%.1f".format(Locale.ROOT, it) } }).append(';')
            append(g.choices)
            append('\n')
        }
    }

    @Synchronized
    fun decode(text: String?) {
        groups.clear()
        unanswered = 0
        text ?: return
        for (line in text.lines()) {
            val cols = line.split(';')
            if (cols.size == 2 && cols[0] == "sin_respuesta") {
                unanswered = cols[1].toIntOrNull() ?: 0
                continue
            }
            if (cols.size < 5) continue
            val group = SceneGroup.entries.firstOrNull { it.name == cols[0] } ?: continue
            val s = cols[1].split(',').map { it.toIntOrNull() ?: 0 }
            val g = Group(
                StyleAdjust.NONE + StyleAdjust(s.getOrElse(0) { 0 }, s.getOrElse(1) { 0 }, s.getOrElse(2) { 0 }, s.getOrElse(3) { 0 }),
                cols[2].toIntOrNull() ?: 0,
                cols[3].toIntOrNull() ?: 0,
            )
            for (item in cols[4].split(',')) {
                val (name, arm) = item.split('=').takeIf { it.size == 2 } ?: continue
                val v = Variant.entries.firstOrNull { it.name == name } ?: continue
                Arm.decode(arm)?.let { g.arms[v] = it }
            }
            cols.getOrNull(5)?.split('|')?.forEachIndexed { i, block ->
                val nums = block.split(':').mapNotNull { it.toFloatOrNull() }
                if (i < g.votes.size && nums.size == 3) g.votes[i] = nums.toFloatArray()
            }
            g.choices = cols.getOrNull(6)?.toIntOrNull() ?: 0
            groups[group] = g
        }
    }

    companion object {
        /** Parte de las fotos en las que se prueba una versión distinta. */
        const val EXPLORE = 0.35f

        /** Sin ninguna valoración en tantas fotos de prueba seguidas, se deja de probar. */
        const val MAX_UNANSWERED = 12
        const val ASK_EVERY = 10

        const val MIN_TRIALS = 3f
        const val Z_PROMOTE = 2.0f

        /** Elecciones de ventaja con las que la prueba de gusto da un paso. */
        const val TASTE_LEAD = 2f
    }
}

/**
 * Las copias mejoradas recientes, para aprender también de lo que haces sin decir nada: si de las dos
 * (original y mejorada) borras la mejorada, preferías la original; si borras la original, te gustó la
 * mejorada. Solo cuenta cuando se guardan las dos.
 */
class ShotJournal {

    data class Entry(
        val time: Long,
        val group: SceneGroup,
        val variant: Variant,
        val original: String,
        val enhanced: String,
        /** Se guardaron las dos (si solo se guarda la mejorada, la original la borra la propia app). */
        val bothKept: Boolean,
        var rated: Boolean = false,
    )

    /** Lo que se deduce de una foto: te gustó la mejorada (true) o preferías la original (false). */
    data class Signal(val entry: Entry, val liked: Boolean)

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun add(e: Entry) {
        entries += e
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Ya la valoraste tú: no hace falta deducir nada. */
    @Synchronized
    fun markRated(enhanced: String) {
        entries.filter { it.enhanced == enhanced }.forEach { it.rated = true }
    }

    @Synchronized
    fun find(enhanced: String): Entry? = entries.lastOrNull { it.enhanced == enhanced }

    /**
     * Mira qué copias siguen en la galería.
     * @param exists si una foto sigue (null = no se pudo saber).
     */
    @Synchronized
    fun check(now: Long, exists: (String) -> Boolean?): List<Signal> {
        val signals = ArrayList<Signal>()
        val done = HashSet<Entry>()
        for (e in entries) {
            val age = now - e.time
            if (age > MAX_AGE_MS || e.rated || !e.bothKept) {
                done += e
                continue
            }
            if (age < MIN_AGE_MS) continue
            val original = exists(e.original) ?: continue
            val enhanced = exists(e.enhanced) ?: continue
            when {
                original && !enhanced -> signals += Signal(e, liked = false)
                !original && enhanced -> signals += Signal(e, liked = true)
                !original && !enhanced -> Unit // borraste la foto entera: no dice nada de la mejora
                else -> continue // siguen las dos: se vuelve a mirar más adelante
            }
            done += e
        }
        entries.removeAll(done)
        return signals
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    /** Una línea por foto: "hora;GRUPO;VARIANTE;1;0;uri original;uri mejorada". */
    @Synchronized
    fun encode(): String = entries.joinToString("\n") { e ->
        listOf(e.time, e.group.name, e.variant.name, if (e.bothKept) 1 else 0, if (e.rated) 1 else 0, e.original, e.enhanced).joinToString(";")
    }

    @Synchronized
    fun decode(text: String?) {
        entries.clear()
        text ?: return
        for (line in text.lines()) {
            val c = line.split(';')
            if (c.size != 7) continue
            entries += Entry(
                time = c[0].toLongOrNull() ?: continue,
                group = SceneGroup.entries.firstOrNull { it.name == c[1] } ?: continue,
                variant = Variant.entries.firstOrNull { it.name == c[2] } ?: continue,
                original = c[5],
                enhanced = c[6],
                bothKept = c[3] == "1",
                rated = c[4] == "1",
            )
        }
    }

    companion object {
        /** Antes de esto aún no se sabe qué vas a hacer con la foto. */
        const val MIN_AGE_MS = 15 * 60_000L

        /** Pasado esto, si siguen las dos, ya no se espera nada. */
        const val MAX_AGE_MS = 7 * 24 * 3_600_000L
        const val MAX_ENTRIES = 80
    }
}
