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

    private companion object {
        const val KEY_GRID = "rejilla"
        const val KEY_LEVEL = "nivel"
    }
}
