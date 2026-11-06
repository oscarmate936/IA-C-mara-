package com.iacamara.app.coach

import com.iacamara.app.director.TipAction
import com.iacamara.app.learning.TipLearner

/** El consejo que se muestra en el visor. [mutable] = se puede silenciar con la ✕. */
data class CoachTip(
    val id: String,
    val text: String,
    val action: TipAction? = null,
    val habit: HabitCount? = null,
    val mutable: Boolean = false,
)

/**
 * Elige el único consejo que se muestra en cada momento, entre los del encuadre, los del Director y los
 * del fotógrafo veterano:
 * - el más importante (los que tienen que ver con un hábito tuyo suben de prioridad y te lo recuerdan;
 *   los de técnica que ya dominas bajan, y los que silenciaste no salen);
 * - se mantiene al menos [MIN_MS] para que dé tiempo a leerlo, salvo que aparezca algo bastante más
 *   importante; si el problema se arregla, se quita enseguida (tras un margen corto contra parpadeos);
 * - cada vez que vuelve un consejo se dice de otra forma; con [tips], se elige más la frase que más veces
 *   te ha llevado a arreglarlo enseguida.
 *
 * @param wallClock hora real (para los silenciados, que duran días).
 */
class CoachSession(
    private val tips: TipLearner? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    private var currentId: String? = null
    private var currentPriority = 0f
    private var currentVariant = 0
    private var currentVariants = 1
    private var sinceMs = 0L
    private var lastSeenMs = 0L
    private var lastTip: CoachTip? = null
    private var judged = false
    private val nextVariant = HashMap<String, Int>()
    private val lastVariant = HashMap<String, Int>()
    private val shownSinceShot = LinkedHashSet<String>()

    fun update(candidates: List<Advice>, habits: List<HabitCount>, now: Long): CoachTip? {
        val counts = habits.associateBy { it.habit }
        val wall = wallClock()
        val usable = if (tips == null) candidates else candidates.filter { !tips.isMuted(it.id, wall) }
        fun score(a: Advice): Float {
            val base = tips?.priority(a.id, a.priority) ?: a.priority
            return base + if (a.habit != null && a.habit in counts) HABIT_BOOST else 0f
        }

        val best = usable.maxByOrNull { score(it) }
        val current = usable.firstOrNull { it.id == currentId }
        if (current != null) lastSeenMs = now

        val chosen: Advice? = when {
            // El consejo actual sigue: se mantiene salvo que haya algo bastante más importante y ya se haya leído.
            current != null -> {
                val urgent = best != null && best.id != current.id && score(best) >= score(current) + URGENT
                val read = now - sinceMs >= MIN_MS
                if (best != null && best.id != current.id && (urgent || (read && score(best) > score(current)))) best else current
            }
            // Acaba de desaparecer: un instante de margen (evita parpadeos si el problema va y viene).
            currentId != null && now - lastSeenMs < GRACE_MS && (best == null || score(best) < currentPriority + URGENT) ->
                return lastTip
            else -> {
                // Ha desaparecido de verdad: si fue poco después de decirlo, el consejo sirvió.
                if (currentId != null && now - sinceMs <= FOLLOW_MS) judge(followed = true)
                best
            }
        }
        if (current != null && chosen?.id == current.id && now - sinceMs > STUCK_MS) judge(followed = false)
        if (chosen == null) {
            currentId = null
            lastTip = null
            return null
        }
        if (chosen.id != currentId) {
            currentId = chosen.id
            sinceMs = now
            lastSeenMs = now
            judged = false
            currentVariants = chosen.variants.size
            currentVariant = tips?.chooseVariant(chosen.id, chosen.variants.size, lastVariant[chosen.id])
                ?: (nextVariant[chosen.id] ?: 0).also { nextVariant[chosen.id] = it + 1 }
            lastVariant[chosen.id] = currentVariant
        }
        shownSinceShot += chosen.id
        currentPriority = score(chosen)
        val base = chosen.variants[currentVariant % chosen.variants.size]
        val habit = chosen.habit?.let { counts[it] }
        val text = if (habit != null) "${habit.habit.reminder} (${habit.text}). $base" else base
        val mutable = tips != null && TipLearner.learnable(chosen.id)
        return CoachTip(chosen.id, text, chosen.action, habit, mutable).also { lastTip = it }
    }

    /** Has disparado: si había un consejo en pantalla, no lo seguiste; y se anota qué salió al encuadrar. */
    fun onShutter() {
        if (currentId != null) judge(followed = false)
        tips?.recordShot(shownSinceShot.toSet())
        shownSinceShot.clear()
    }

    /** La ✕ del consejo: no vuelve a salir en un tiempo. @return el texto silenciado, o null si no se puede. */
    fun muteCurrent(): String? {
        val id = currentId ?: return null
        val learner = tips ?: return null
        if (!TipLearner.learnable(id)) return null
        val text = lastTip?.text ?: id
        judge(followed = false)
        learner.mute(id, text, wallClock())
        reset()
        return text
    }

    private fun judge(followed: Boolean) {
        val id = currentId ?: return
        if (judged) return
        judged = true
        tips?.outcome(id, currentVariant, currentVariants, followed)
    }

    fun reset() {
        currentId = null
        lastTip = null
    }

    companion object {
        /** Tiempo mínimo que se ve un consejo antes de cambiarlo por otro. */
        const val MIN_MS = 3500L

        /** Un consejo que desaparece se sigue viendo este tiempo (contra parpadeos). */
        const val GRACE_MS = 700L

        /** Un consejo así de más importante sustituye al actual aunque no se haya terminado de leer. */
        const val URGENT = 0.2f

        /** Un consejo relacionado con un hábito tuyo sube esta prioridad. */
        const val HABIT_BOOST = 0.15f

        /** Si el problema se arregla antes de esto, el consejo sirvió. */
        const val FOLLOW_MS = 10_000L

        /** Si sigue en pantalla después de esto, no sirvió (o no se pudo seguir). */
        const val STUCK_MS = 15_000L

        /** Frases para cuando el encuadre ya está bien. */
        val GOOD_FRAMING = listOf("Buen encuadre: ¡dispara!", "¡Así está bien! Dispara", "Encuadre listo: dispara cuando quieras")
    }
}
