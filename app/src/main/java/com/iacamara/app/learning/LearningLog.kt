package com.iacamara.app.learning

/** Registro de lo que la app ha aprendido de ti (para verlo en Ajustes → Tu estilo). Solo en el móvil. */
class LearningLog {

    data class Event(val time: Long, val text: String)

    private val events = ArrayDeque<Event>()

    @Synchronized
    fun add(time: Long, text: String) {
        events.addLast(Event(time, text.replace('\n', ' ')))
        while (events.size > MAX) events.removeFirst()
    }

    /** Los más recientes primero. */
    @Synchronized
    fun recent(n: Int): List<Event> = events.toList().takeLast(n).reversed()

    @Synchronized
    fun clear() = events.clear()

    @Synchronized
    fun encode(): String = events.joinToString("\n") { "${it.time};${it.text}" }

    @Synchronized
    fun decode(text: String?) {
        events.clear()
        for (line in text.orEmpty().lines()) {
            val time = line.substringBefore(';').toLongOrNull() ?: continue
            events.addLast(Event(time, line.substringAfter(';')))
        }
        while (events.size > MAX) events.removeFirst()
    }

    companion object {
        const val MAX = 40
    }
}
