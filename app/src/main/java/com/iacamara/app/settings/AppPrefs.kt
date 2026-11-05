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

    /** Disparo inteligente: la app dispara sola cuando el encuadre es bueno y todos tienen los ojos abiertos. */
    var smartShutter: Boolean
        get() = sp.getBoolean(KEY_SMART_SHUTTER, false)
        set(value) = sp.edit().putBoolean(KEY_SMART_SHUTTER, value).apply()

    /** Guía de composición fija elegida por el usuario, o null si la elige la IA. */
    var guide: String?
        get() = sp.getString(KEY_GUIDE, null)
        set(value) = sp.edit().putString(KEY_GUIDE, value).apply()

    /** Qué guardar con la mejora automática (Fase 5). */
    var enhance: EnhanceMode
        get() = sp.getString(KEY_ENHANCE, null)?.let { n -> EnhanceMode.entries.firstOrNull { it.name == n } } ?: EnhanceMode.AMBAS
        set(value) = sp.edit().putString(KEY_ENHANCE, value.name).apply()

    /** En la versión mejorada: enderezar, corregir la perspectiva y recortar según la guía. */
    var enhanceGeometry: Boolean
        get() = sp.getBoolean(KEY_ENHANCE_GEOMETRY, true)
        set(value) = sp.edit().putBoolean(KEY_ENHANCE_GEOMETRY, value).apply()

    /** En el modo PRO, guardar también el RAW (DNG) si el móvil lo permite. */
    var raw: Boolean
        get() = sp.getBoolean(KEY_RAW, false)
        set(value) = sp.edit().putBoolean(KEY_RAW, value).apply()

    private companion object {
        const val KEY_GRID = "rejilla"
        const val KEY_LEVEL = "nivel"
        const val KEY_IA = "ia"
        const val KEY_HISTOGRAM = "histograma"
        const val KEY_SMART_SHUTTER = "disparo_inteligente"
        const val KEY_GUIDE = "guia"
        const val KEY_ENHANCE = "mejora"
        const val KEY_ENHANCE_GEOMETRY = "mejora_geometria"
        const val KEY_RAW = "raw"
    }
}

/** Qué se guarda con la mejora automática. */
enum class EnhanceMode(val label: String, val description: String) {
    AMBAS("Guardar las dos", "La foto original y una copia mejorada"),
    SOLO_MEJORADA("Solo la mejorada", "Se guarda la versión mejorada y se borra la original"),
    NO("Desactivada", "Solo la foto original, como sale de la cámara"),
}
