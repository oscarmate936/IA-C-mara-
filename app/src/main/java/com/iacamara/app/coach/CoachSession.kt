package com.iacamara.app.coach

import com.iacamara.app.director.TipAction

/** El consejo que se muestra en el visor. */
data class CoachTip(val id: String, val text: String, val action: TipAction? = null, val habit: HabitCount? = null)

/**
 * Elige el único consejo que se muestra en cada momento, entre los del encuadre, los del Director y los
 * del fotógrafo veterano:
 * - el más importante (los que tienen que ver con un hábito tuyo suben de prioridad y te lo recuerdan);
 * - se mantiene al menos [MIN_MS] para que dé tiempo a leerlo, salvo que aparezca algo bastante más
 *   importante; si el problema se arregla, se quita enseguida (tras un margen corto contra parpadeos);
 * - cada vez que vuelve un consejo se dice de otra forma, para que no suene repetitivo.
 */
class CoachSession {

    private var currentId: String? = null
    private var currentPriority = 0f
    private var currentVariant = 0
    private var sinceMs = 0L
    private var lastSeenMs = 0L
    private var lastTip: CoachTip? = null
    private val nextVariant = HashMap<String, Int>()

    fun update(candidates: List<Advice>, habits: List<HabitCount>, now: Long): CoachTip? {
        val counts = habits.associateBy { it.habit }
        fun score(a: Advice) = a.priority + if (a.habit != null && a.habit in counts) HABIT_BOOST else 0f

        val best = candidates.maxByOrNull { score(it) }
        val current = candidates.firstOrNull { it.id == currentId }
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
            else -> best
        }
        if (chosen == null) {
            currentId = null
            lastTip = null
            return null
        }
        if (chosen.id != currentId) {
            currentId = chosen.id
            sinceMs = now
            lastSeenMs = now
            currentVariant = nextVariant[chosen.id] ?: 0
            nextVariant[chosen.id] = currentVariant + 1
        }
        currentPriority = score(chosen)
        val base = chosen.variants[currentVariant % chosen.variants.size]
        val habit = chosen.habit?.let { counts[it] }
        val text = if (habit != null) "${habit.habit.reminder} (${habit.text}). $base" else base
        return CoachTip(chosen.id, text, chosen.action, habit).also { lastTip = it }
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

        /** Frases para cuando el encuadre ya está bien. */
        val GOOD_FRAMING = listOf("Buen encuadre: ¡dispara!", "¡Así está bien! Dispara", "Encuadre listo: dispara cuando quieras")
    }
}
