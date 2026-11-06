package com.iacamara.app.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.iacamara.app.learning.Learned
import com.iacamara.app.learning.LearningLog
import com.iacamara.app.learning.ShotJournal
import com.iacamara.app.learning.StyleLearner
import com.iacamara.app.learning.TipLearner

/**
 * Todo lo que la app aprende de ti (Fase 7), guardado solo en este móvil. Con la firma fija del APK se
 * conserva al instalar versiones nuevas. Hay uno por proceso: lo comparten la cámara y los ajustes.
 */
class Learning internal constructor(context: Context) {
    private val sp = context.getSharedPreferences("aprendizaje", Context.MODE_PRIVATE)

    val style = StyleLearner()
    val journal = ShotJournal()
    val tips = TipLearner()
    val log = LearningLog()

    init {
        style.decode(sp.getString(K_STYLE, null))
        journal.decode(sp.getString(K_JOURNAL, null))
        tips.decode(sp.getString(K_TIPS, null))
        log.decode(sp.getString(K_LOG, null))
        tips.onChange = { saveTips() }
    }

    fun save() {
        sp.edit()
            .putString(K_STYLE, style.encode())
            .putString(K_JOURNAL, journal.encode())
            .putString(K_LOG, log.encode())
            .apply()
    }

    private fun saveTips() {
        sp.edit().putString(K_TIPS, tips.encode()).apply()
    }

    /** Anota algo aprendido en el registro. */
    fun note(text: String) {
        log.add(System.currentTimeMillis(), text)
    }

    /**
     * Mira qué copias de las fotos recientes has borrado (de las que se guardaron las dos) y aprende de
     * ello. @return lo aprendido, si tu estilo cambió.
     */
    fun checkDeleted(resolver: ContentResolver): List<Learned> {
        val signals = journal.check(System.currentTimeMillis()) { exists(resolver, it) }
        if (signals.isEmpty()) return emptyList()
        val learned = signals.mapNotNull { s ->
            val what = s.entry.group.label.lowercase()
            note(
                if (s.liked) "Borraste la original de una foto de $what y te quedaste con la mejorada"
                else "Borraste la versión mejorada de una foto de $what: preferías la original",
            )
            style.feedback(s.entry.group, s.entry.variant, s.liked, IMPLICIT_WEIGHT)
        }
        learned.forEach { note(it.text) }
        save()
        return learned
    }

    /** Olvida todo lo aprendido (Ajustes → Tu estilo). */
    fun forget() {
        style.clear()
        journal.clear()
        tips.clear()
        log.clear()
        save()
        saveTips()
    }

    /** Si una foto sigue en la galería (las borradas o en la papelera no aparecen). null = no se sabe. */
    private fun exists(resolver: ContentResolver, uri: String): Boolean? = try {
        resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val K_STYLE = "estilo"
        const val K_JOURNAL = "fotos"
        const val K_TIPS = "consejos"
        const val K_LOG = "registro"

        /** Lo que se deduce de lo que borras cuenta como una valoración tuya. */
        const val IMPLICIT_WEIGHT = 1f
    }
}

object LearningBook {
    @Volatile private var learning: Learning? = null

    fun get(context: Context): Learning =
        learning ?: synchronized(this) {
            learning ?: Learning(context.applicationContext).also { learning = it }
        }
}
