package com.iacamara.app.settings

import android.content.Context

/** Preferencias del usuario guardadas en el propio móvil. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, true)
        set(value) = sp.edit().putBoolean(KEY_GRID, value).apply()

    var level: Boolean
        get() = sp.getBoolean(KEY_LEVEL, true)
        set(value) = sp.edit().putBoolean(KEY_LEVEL, value).apply()

    /** IA activa: analiza la escena y ajusta enfoque y exposición solos. */
    var ia: Boolean
        get() = sp.getBoolean(KEY_IA, true)
        set(value) = sp.edit().putBoolean(KEY_IA, value).apply()

    var histogram: Boolean
        get() = sp.getBoolean(KEY_HISTOGRAM, false)
        set(value) = sp.edit().putBoolean(KEY_HISTOGRAM, value).apply()

    private companion object {
        const val KEY_GRID = "rejilla"
        const val KEY_LEVEL = "nivel"
        const val KEY_IA = "ia"
        const val KEY_HISTOGRAM = "histograma"
    }
}
