package com.iacamara.app.learning

import kotlin.random.Random

/**
 * Consejos adaptativos:
 * - **Silenciar**: un consejo que no te interesa (la ✕ del consejo) no vuelve a salir en [MUTE_DAYS] días.
 * - **La frase que te funciona**: cada consejo tiene varias formas de decirse; con muestreo de Thompson se
 *   elige más la que más veces te lleva a arreglar el problema enseguida (sin repetir la misma dos veces
 *   seguidas).
 * - **Lo que ya dominas**: un consejo de técnica (nivel, colocación, horizonte, cortes, ángulo…) que te
 *   hacía falta antes y en tus últimas [WINDOW] fotos casi no ha salido baja de prioridad: así el coach se
 *   centra en lo que aún falla. Si vuelve a hacer falta a menudo, recupera su prioridad solo.
 */
class TipLearner(private val random: Random = Random.Default) {

    data class Muted(val id: String, val until: Long, val text: String)

    private val muted = LinkedHashMap<String, Muted>()
    private val phrases = HashMap<String, MutableList<Arm>>()

    /** Consejos de técnica que salieron al encuadrar cada una de tus últimas fotos. */
    private val recent = ArrayDeque<Set<String>>()

    /** En cuántas fotos ha salido cada consejo de técnica (desde siempre). */
    private val seen = HashMap<String, Int>()
    @Volatile private var masteredCache: Set<String> = emptySet()

    /** Se llama tras cada cambio (para guardarlo). */
    var onChange: (() -> Unit)? = null

    @Synchronized
    fun isMuted(id: String, now: Long): Boolean {
        val m = muted[id] ?: return false
        if (m.until > now) return true
        muted.remove(id)
        return false
    }

    @Synchronized
    fun mute(id: String, text: String, now: Long) {
        muted[id] = Muted(id, now + MUTE_DAYS * DAY_MS, text)
        changed()
    }

    @Synchronized
    fun unmute(id: String) {
        if (muted.remove(id) != null) changed()
    }

    @Synchronized
    fun mutedList(now: Long): List<Muted> = muted.values.filter { it.until > now }

    /** Qué forma de decir el consejo [id] (de [count]); [avoid] = la última que se usó. */
    @Synchronized
    fun chooseVariant(id: String, count: Int, avoid: Int?): Int {
        if (count <= 1) return 0
        val arms = arms(id, count)
        val order = (0 until count).sortedByDescending { arms[it].sample(random) }
        return if (order[0] == avoid) order[1] else order[0]
    }

    /** Con la frase [variant], ¿arreglaste el problema enseguida ([followed]) o no? */
    @Synchronized
    fun outcome(id: String, variant: Int, count: Int, followed: Boolean) {
        if (!learnable(id) || count <= 1) return
        arms(id, count).getOrNull(variant)?.add(followed)
        changed()
    }

    /** Una foto nueva: los consejos que salieron mientras la encuadrabas. */
    @Synchronized
    fun recordShot(shown: Set<String>) {
        val skills = shown.filter { isSkill(it) }.toSet()
        recent.addLast(skills)
        while (recent.size > WINDOW) recent.removeFirst()
        skills.forEach { seen[it] = (seen[it] ?: 0) + 1 }
        updateMastered()
        changed()
    }

    @Synchronized
    fun mastered(): Set<String> = masteredCache

    /** Prioridad del consejo teniendo en cuenta lo que ya dominas. */
    fun priority(id: String, base: Float): Float = if (id in masteredCache) base - MASTERED_PENALTY else base

    @Synchronized
    fun clear() {
        muted.clear()
        phrases.clear()
        recent.clear()
        seen.clear()
        masteredCache = emptySet()
        changed()
    }

    private fun updateMastered() {
        masteredCache = if (recent.size < WINDOW) {
            emptySet()
        } else {
            seen.filter { (id, n) -> n >= MIN_SEEN && recent.count { id in it } <= MAX_RECENT }.keys.toSet()
        }
    }

    private fun arms(id: String, count: Int): MutableList<Arm> {
        val list = phrases.getOrPut(id) { MutableList(count) { Arm() } }
        // Si cambia el número de frases de un consejo (otra versión de la app), se empieza de nuevo.
        if (list.size != count) {
            list.clear()
            repeat(count) { list += Arm() }
        }
        return list
    }

    private fun changed() {
        onChange?.invoke()
    }

    /** "m;id;hasta;texto", "p;id;bien:mal,…", "r;id|id" (una por foto, en orden) y "s;id;veces". */
    @Synchronized
    fun encode(): String = buildString {
        muted.values.forEach { append("m;${it.id};${it.until};${clean(it.text)}\n") }
        phrases.forEach { (id, arms) -> append("p;$id;${arms.joinToString(",") { it.encode() }}\n") }
        recent.forEach { append("r;${it.joinToString("|")}\n") }
        seen.forEach { (id, n) -> append("s;$id;$n\n") }
    }

    @Synchronized
    fun decode(text: String?) {
        muted.clear()
        phrases.clear()
        recent.clear()
        seen.clear()
        for (line in text.orEmpty().lines()) {
            val c = line.split(';')
            when (c.firstOrNull()) {
                "m" -> if (c.size >= 4) c[2].toLongOrNull()?.let { muted[c[1]] = Muted(c[1], it, c.drop(3).joinToString(";")) }
                "p" -> if (c.size == 3) phrases[c[1]] = c[2].split(',').mapNotNull { Arm.decode(it) }.toMutableList()
                "r" -> if (c.size == 2) recent.addLast(c[1].split('|').filter { it.isNotEmpty() }.toSet())
                "s" -> if (c.size == 3) c[2].toIntOrNull()?.let { seen[c[1]] = it }
            }
        }
        while (recent.size > WINDOW) recent.removeFirst()
        updateMastered()
    }

    private fun clean(text: String) = text.replace('\n', ' ')

    companion object {
        const val MUTE_DAYS = 30L
        private const val DAY_MS = 24 * 3_600_000L
        const val WINDOW = 20
        const val MIN_SEEN = 3
        const val MAX_RECENT = 1
        const val MASTERED_PENALTY = 0.15f

        /** Consejos de los que se aprende qué frase funciona (no los del Director ni el "¡dispara!"). */
        fun learnable(id: String): Boolean = !id.startsWith("director") && id != "encuadre.bueno"

        /** Consejos de técnica: dependen de cómo haces la foto, no de la luz o de la escena. */
        fun isSkill(id: String): Boolean =
            (id.startsWith("encuadre.") && id !in setOf("encuadre.bueno", "encuadre.exposición", "encuadre.general")) ||
                id.startsWith("angulo.")

        /** Nombre de una técnica para "Ya dominas". */
        fun skillLabel(id: String): String = SKILLS[id] ?: id.substringAfter('.').replaceFirstChar { it.uppercase() }

        private val SKILLS = mapOf(
            "encuadre.nivel" to "Móvil recto",
            "encuadre.posición" to "Colocar al sujeto",
            "encuadre.horizonte" to "Horizonte en su sitio",
            "encuadre.horizonte.medio" to "Horizonte fuera del centro",
            "encuadre.cortes" to "No cortar al sujeto",
            "encuadre.aire" to "Aire sobre la cabeza",
            "encuadre.bordes" to "Bordes limpios",
            "encuadre.equilibrio" to "Equilibrio",
            "encuadre.líneas" to "Líneas guía",
            "encuadre.simetría" to "Simetría",
            "encuadre.tamaño" to "Acercarte al sujeto",
            "encuadre.nitidez" to "Pulso firme",
            "encuadre.pose" to "Pose",
            "angulo.contrapicado" to "Cámara a la altura de los ojos",
            "angulo.mascota" to "Agacharte con los animales",
        )
    }
}
