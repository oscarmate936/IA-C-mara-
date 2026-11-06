package com.iacamara.app.learning

import com.iacamara.app.coach.Advice
import com.iacamara.app.coach.CoachSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TipLearnerTest {

    private val backlight = Advice("luz.contraluz", 0.75f, listOf("A", "B", "C"))
    private val level = Advice("encuadre.nivel", 0.6f, listOf("Endereza"))

    @Test
    fun unConsejoSilenciadoNoSaleEnUnMes() {
        val t = TipLearner(Random(1))
        var wall = 0L
        val coach = CoachSession(t) { wall }
        assertEquals("luz.contraluz", coach.update(listOf(backlight, level), emptyList(), 0)?.id)
        assertTrue(coach.update(listOf(backlight, level), emptyList(), 100)!!.mutable)
        assertTrue(coach.muteCurrent() != null)
        // Sale el siguiente.
        assertEquals("encuadre.nivel", coach.update(listOf(backlight, level), emptyList(), 200)?.id)
        wall = 29L * 24 * 3_600_000
        assertTrue(t.isMuted("luz.contraluz", wall))
        wall = 31L * 24 * 3_600_000
        assertFalse(t.isMuted("luz.contraluz", wall))
        // Los del Director no se pueden silenciar.
        val director = CoachSession(TipLearner())
        assertFalse(director.update(listOf(Advice("director:x", 0.2f, listOf("x"))), emptyList(), 0)!!.mutable)
        assertNull(director.muteCurrent())
    }

    @Test
    fun aprendeLaFraseQueTeFunciona() {
        val t = TipLearner(Random(2))
        // Con la frase 2 arreglas el problema enseguida; con las otras, no.
        repeat(60) {
            val v = t.chooseVariant("luz.contraluz", 3, avoid = null)
            t.outcome("luz.contraluz", v, 3, followed = v == 2)
        }
        val picks = (1..100).map { t.chooseVariant("luz.contraluz", 3, avoid = null) }
        assertTrue(picks.count { it == 2 } > 80)
        // Pero nunca la misma dos veces seguidas.
        assertNotEquals(2, t.chooseVariant("luz.contraluz", 3, avoid = 2))
    }

    @Test
    fun elCoachMideSiSeguisteElConsejo() {
        val t = TipLearner(Random(3))
        val coach = CoachSession(t) { 0L }
        // Aparece y a los 3 s el problema se ha arreglado: sirvió.
        coach.update(listOf(backlight), emptyList(), 0)
        coach.update(emptyList(), emptyList(), 3000)
        coach.update(emptyList(), emptyList(), 3000 + CoachSession.GRACE_MS + 1)
        // Aparece y disparas sin arreglarlo: no sirvió.
        coach.update(listOf(backlight), emptyList(), 10_000)
        coach.onShutter()
        val encoded = t.encode()
        assertTrue(encoded, encoded.lines().any { it.startsWith("p;luz.contraluz;") })
    }

    @Test
    fun loQueYaDominasBajaDePrioridad() {
        val t = TipLearner(Random(4))
        // Al principio te hacía falta enderezar el móvil…
        repeat(5) { t.recordShot(setOf("encuadre.nivel", "luz.contraluz")) }
        assertTrue(t.mastered().isEmpty())
        // …y en tus últimas 20 fotos ya no.
        repeat(TipLearner.WINDOW) { t.recordShot(setOf("luz.contraluz")) }
        assertEquals(setOf("encuadre.nivel"), t.mastered())
        assertEquals(0.6f - TipLearner.MASTERED_PENALTY, t.priority("encuadre.nivel", 0.6f), 1e-6f)
        // La luz no es una técnica: no se "domina".
        assertEquals(0.75f, t.priority("luz.contraluz", 0.75f), 1e-6f)
        assertEquals("Móvil recto", TipLearner.skillLabel("encuadre.nivel"))
        // Si vuelve a hacer falta a menudo, recupera su prioridad.
        repeat(3) { t.recordShot(setOf("encuadre.nivel")) }
        assertTrue(t.mastered().isEmpty())
    }

    @Test
    fun seGuardaYSeRecupera() {
        val t = TipLearner(Random(5))
        t.mute("luz.cenital", "El sol cae; desde arriba", 1000L)
        repeat(3) { t.outcome("luz.contraluz", 1, 3, followed = true) }
        repeat(25) { t.recordShot(if (it < 4) setOf("encuadre.cortes") else emptySet()) }
        val copy = TipLearner(Random(5)).apply { decode(t.encode()) }
        assertEquals(t.encode(), copy.encode())
        assertEquals(t.mastered(), copy.mastered())
        assertEquals("El sol cae; desde arriba", copy.mutedList(2000L).single().text)
        TipLearner().decode("x;;;\nm;id;no\np;a;b")
    }

    @Test
    fun olvidarLoBorraTodo() {
        val t = TipLearner()
        var saved = 0
        t.onChange = { saved++ }
        t.mute("luz.cenital", "x", 0L)
        t.clear()
        assertTrue(t.mutedList(0L).isEmpty())
        assertEquals("", t.encode())
        assertEquals(2, saved)
    }
}
