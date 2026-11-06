package com.iacamara.app.coach

/**
 * Errores que se repiten de una foto a otra. Se detectan en el propio móvil, sin enviar nada a ningún
 * sitio: la app solo recuerda, de cada una de tus últimas fotos, qué fallos tenía.
 *
 * @param reminder frase para recordártelo en el visor justo cuando vuelve a pasar.
 * @param composition temas de los consejos de encuadre que corresponden a este hábito.
 */
enum class Habit(val label: String, val reminder: String, val composition: Set<String>) {
    TORCIDO("Horizonte torcido", "Sueles torcer el horizonte", setOf("nivel")),
    CENTRADO("Sujeto siempre en el centro", "Sueles dejar el sujeto en el centro", setOf("posición")),
    MOVIDA("Fotos movidas", "Te salen a menudo fotos movidas", setOf("nitidez")),
    HORIZONTE_MEDIO("Horizonte por la mitad", "Sueles poner el horizonte en el medio", setOf("horizonte.medio", "horizonte")),
    CORTES("Sujeto cortado por el borde", "Sueles cortar al sujeto por el borde", setOf("cortes")),
    ;

    companion object {
        fun forComposition(key: String?): Habit? = key?.let { k -> entries.firstOrNull { k in it.composition } }
    }
}

/** Dónde se guarda el historial (en el móvil: SharedPreferences; en las pruebas: memoria). */
interface HabitStore {
    fun load(): List<Set<Habit>>
    fun save(history: List<Set<Habit>>)
}

/** Un hábito detectado: cuántas de las últimas fotos lo tenían. */
data class HabitCount(val habit: Habit, val times: Int, val of: Int) {
    /** "4 de tus últimas 10 fotos". */
    val text: String get() = "$times de tus últimas $of fotos"
}

/**
 * Lleva la cuenta de los fallos de las últimas fotos. Un fallo se convierte en hábito cuando aparece en
 * al menos [MIN_TIMES] de las últimas [WINDOW] fotos (y hay al menos [MIN_SHOTS] fotos para juzgar).
 */
class HabitTracker(private val store: HabitStore) {

    private var history: MutableList<Set<Habit>> = store.load().takeLast(KEEP).toMutableList()

    /** Anota los fallos de una foto nueva. */
    @Synchronized
    fun record(flags: Set<Habit>) {
        history.add(flags)
        while (history.size > KEEP) history.removeAt(0)
        store.save(history)
    }

    /** Los hábitos que se repiten ahora mismo, del más frecuente al menos. */
    @Synchronized
    fun active(): List<HabitCount> {
        val recent = history.takeLast(WINDOW)
        if (recent.size < MIN_SHOTS) return emptyList()
        return Habit.entries
            .map { h -> HabitCount(h, recent.count { h in it }, recent.size) }
            .filter { it.times >= MIN_TIMES }
            .sortedByDescending { it.times }
    }

    fun activeSet(): Set<Habit> = active().map { it.habit }.toSet()

    /** Olvida todo lo aprendido (ajustes → "Olvidar mis hábitos"). */
    @Synchronized
    fun clear() {
        history.clear()
        store.save(history)
    }

    val shots: Int get() = history.size

    companion object {
        const val WINDOW = 10
        const val MIN_TIMES = 4
        const val MIN_SHOTS = 5
        private const val KEEP = 20

        /** "TORCIDO,MOVIDA;;CENTRADO" ⇄ lista de fallos por foto (una foto por bloque). */
        fun encode(history: List<Set<Habit>>): String = history.joinToString(";") { set -> set.joinToString(",") { it.name } }

        fun decode(text: String?): List<Set<Habit>> {
            if (text.isNullOrEmpty()) return emptyList()
            return text.split(";").map { block ->
                block.split(",").mapNotNull { name -> Habit.entries.firstOrNull { it.name == name } }.toSet()
            }
        }
    }
}

/**
 * Fallos de una foto al dispararla, a partir de lo que medía el coach en ese momento.
 *
 * @param criteria criterios del encuadre (0–1) en el disparo.
 * @param subjectCenterX centro del sujeto (0..1, vista del usuario) y si la guía pedía otro sitio.
 */
object HabitFlags {
    fun of(
        criteria: Map<String, Float>,
        subjectCenterX: Float?,
        targetX: Float?,
        centeredStyle: Boolean,
        shaky: Boolean,
        blurry: Boolean,
        horizonY: Float?,
        hasSubject: Boolean,
    ): Set<Habit> = buildSet {
        criteria["nivel"]?.let { if (it < 0.7f) add(Habit.TORCIDO) }
        if (subjectCenterX != null && targetX != null && !centeredStyle &&
            kotlin.math.abs(subjectCenterX - 0.5f) < 0.08f && kotlin.math.abs(targetX - 0.5f) > 0.1f
        ) {
            add(Habit.CENTRADO)
        }
        if (shaky || blurry) add(Habit.MOVIDA)
        if (!hasSubject && horizonY != null && kotlin.math.abs(horizonY - 0.5f) < 0.06f) add(Habit.HORIZONTE_MEDIO)
        criteria["sin cortes"]?.let { if (it < 0.6f) add(Habit.CORTES) }
    }
}
