package com.iacamara.app.settings

import android.content.Context
import com.iacamara.app.coach.Habit
import com.iacamara.app.coach.HabitStore
import com.iacamara.app.coach.HabitTracker

/**
 * Tus hábitos de foto, guardados solo en este móvil. Hay un único registro por proceso: lo comparten la
 * cámara (que anota cada foto) y los ajustes (donde se pueden ver y olvidar).
 */
object HabitBook {
    @Volatile private var tracker: HabitTracker? = null

    fun get(context: Context): HabitTracker =
        tracker ?: synchronized(this) {
            tracker ?: HabitTracker(PrefsStore(context.applicationContext)).also { tracker = it }
        }

    private class PrefsStore(context: Context) : HabitStore {
        private val sp = context.getSharedPreferences("habitos", Context.MODE_PRIVATE)
        override fun load(): List<Set<Habit>> = HabitTracker.decode(sp.getString(KEY, null))
        override fun save(history: List<Set<Habit>>) {
            sp.edit().putString(KEY, HabitTracker.encode(history)).apply()
        }
    }

    private const val KEY = "historial"
}
