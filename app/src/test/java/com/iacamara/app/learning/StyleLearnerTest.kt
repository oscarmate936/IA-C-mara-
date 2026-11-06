package com.iacamara.app.learning

import com.iacamara.app.imaging.Recipe
import com.iacamara.app.imaging.StyleAdjust
import com.iacamara.app.perception.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class StyleLearnerTest {

    @Test
    fun cadaPasoEsPequenoYSeAcumulaConTope() {
        val r = Recipe.forScene(SceneType.RETRATO)
        assertEquals(r, StyleAdjust.NONE.applyTo(r))
        val warm = StyleAdjust(warmth = 1).applyTo(r)
        assertEquals(r.warmth + StyleAdjust.WARMTH_STEP, warm.warmth, 1e-6f)
        val soft = StyleAdjust(contrast = -3).applyTo(r)
        assertTrue(soft.contrast < r.contrast && soft.contrast >= 0f)
        assertEquals(StyleAdjust(warmth = 3), StyleAdjust(warmth = 2) + StyleAdjust(warmth = 2))
        assertEquals("más cálidas, con bastante más contraste", StyleAdjust(warmth = 1, contrast = 2).describe())
        assertEquals("", StyleAdjust.NONE.describe())
    }

    @Test
    fun sinAprendizajeNoSeVaria() {
        val l = StyleLearner(Random(1))
        repeat(50) { assertEquals(Variant.BASE, l.choose(SceneGroup.PERSONAS, explore = false)) }
    }

    @Test
    fun pruebaVersionesEnUnaParteDeLasFotos() {
        val l = StyleLearner(Random(2))
        var tried = 0
        repeat(300) {
            val v = l.choose(SceneGroup.PAISAJES)
            if (v != Variant.BASE) tried++
            l.feedback(SceneGroup.PAISAJES, v, liked = true) // siempre contesta (y le da igual)
        }
        assertTrue("$tried", tried in 75..135) // ≈ 35 %
    }

    @Test
    fun siNoValorasDejaDeProbar() {
        val l = StyleLearner(Random(3))
        val versions = (0 until 200).map { l.choose(SceneGroup.COMIDA) }
        assertEquals(StyleLearner.MAX_UNANSWERED, versions.count { it != Variant.BASE })
        assertTrue(!l.shouldAsk() || (200 % StyleLearner.ASK_EVERY == 0))
        // Una valoración (o una copia borrada) lo reactiva.
        l.feedback(SceneGroup.COMIDA, Variant.BASE, liked = true)
        assertTrue(l.shouldAsk())
    }

    @Test
    fun unaVersionClaramenteMejorPasaASerTuEstilo() {
        val l = StyleLearner(Random(4))
        repeat(6) { l.feedback(SceneGroup.PERSONAS, Variant.BASE, liked = it == 0) } // tu estilo actual: 1 de 6
        assertNull(l.feedback(SceneGroup.PERSONAS, Variant.CALIDA, liked = true))
        assertNull(l.feedback(SceneGroup.PERSONAS, Variant.CALIDA, liked = true))
        val learned = l.feedback(SceneGroup.PERSONAS, Variant.CALIDA, liked = true)
        assertNotNull(learned)
        assertEquals(StyleAdjust(warmth = 1), l.style(SceneGroup.PERSONAS))
        assertEquals("Aprendido: tus fotos de personas, más cálidas", learned!!.text)
        // Los demás grupos no cambian.
        assertEquals(StyleAdjust.NONE, l.style(SceneGroup.PAISAJES))
        // La versión "más cálida" ahora parte del nuevo estilo.
        assertEquals(StyleAdjust(warmth = 2), l.adjust(SceneGroup.PERSONAS, Variant.CALIDA))
    }

    @Test
    fun siTeDaIgualTuEstiloNoSeMueve() {
        // A un usuario al que todo le gusta igual (la mitad de las veces) no se le cambia el estilo a ciegas.
        val moves = (1..20).sumOf { seed ->
            val l = StyleLearner(Random(seed))
            val rnd = Random(1000 + seed)
            repeat(80) {
                val v = l.choose(SceneGroup.CIUDAD)
                l.feedback(SceneGroup.CIUDAD, v, liked = rnd.nextFloat() < 0.5f)
            }
            val s = l.style(SceneGroup.CIUDAD)
            abs(s.warmth) + abs(s.vivid) + abs(s.contrast) + abs(s.bright)
        }
        assertTrue("pasos medios ${moves / 20f}", moves / 20f <= 1f)
    }

    /** Distancia en pasos entre dos estilos. */
    private fun distance(a: StyleAdjust, b: StyleAdjust) =
        abs(a.warmth - b.warmth) + abs(a.vivid - b.vivid) + abs(a.contrast - b.contrast) + abs(a.bright - b.bright)

    @Test
    fun conValoracionesSueltasAvanzaDespacioPeroSinDesviarse() {
        // Simulación: te gustan los retratos bastante más cálidos y con algo más de contraste. Cada foto te
        // gusta más cuanto más se acerca a eso, y valoras 7 de cada 10.
        val pref = StyleAdjust(warmth = 2, contrast = 1)
        var closer = 0
        var wrong = 0
        for (seed in 1..20) {
            val l = StyleLearner(Random(seed))
            val user = Random(500 + seed)
            repeat(150) {
                val v = l.choose(SceneGroup.PERSONAS)
                val pLike = (0.9f - 0.22f * distance(l.adjust(SceneGroup.PERSONAS, v), pref)).coerceIn(0.05f, 0.95f)
                if (user.nextFloat() < 0.7f) l.feedback(SceneGroup.PERSONAS, v, liked = user.nextFloat() < pLike)
            }
            val s = l.style(SceneGroup.PERSONAS)
            if (distance(s, pref) < 3) closer++
            if (s.warmth < 0 || s.contrast < 0 || abs(s.vivid) > 1 || abs(s.bright) > 1) wrong++
        }
        assertTrue("se acercan $closer de 20", closer >= 10)
        assertTrue("se desvían $wrong de 20", wrong <= 1)
    }

    /** Simula la prueba de gusto: eliges la versión más cercana a tu gusto 8 de cada 10 veces. */
    private fun tasteTest(pref: StyleAdjust, rounds: Int, seed: Int): StyleAdjust {
        val l = StyleLearner(Random(seed))
        val user = Random(900 + seed)
        repeat(rounds) {
            val dim = l.tasteDimension(SceneGroup.PAISAJES)
            val options = l.tasteOptions(SceneGroup.PAISAJES, dim)
            val best = options.minOf { distance(it.second, pref) }
            val bestDirs = options.filter { distance(it.second, pref) == best }.map { it.first }
            val pick: Int? = when {
                user.nextFloat() >= 0.8f -> options.random(user).first // se equivoca
                bestDirs.size > 1 -> if (user.nextBoolean()) null else bestDirs.random(user)
                else -> bestDirs.single()
            }
            l.tasteVote(SceneGroup.PAISAJES, dim, pick)
        }
        return l.style(SceneGroup.PAISAJES)
    }

    @Test
    fun laPruebaDeGustoLlegaATuGustoEnUnasVeinteElecciones() {
        val prefs = listOf(StyleAdjust(warmth = 2, contrast = 1), StyleAdjust(warmth = -1, vivid = 1, bright = 1), StyleAdjust(vivid = -1, contrast = -1, warmth = 1))
        for (pref in prefs) {
            val mean = (1..30).map { distance(tasteTest(pref, 20, it), pref) }.average()
            assertTrue("$pref: a ${"%.2f".format(mean)} pasos (empezaba a ${distance(StyleAdjust.NONE, pref)})", mean <= 0.6)
        }
        // Si te gusta la receta tal cual, no se mueve.
        val still = (1..30).map { distance(tasteTest(StyleAdjust.NONE, 20, it), StyleAdjust.NONE) }.average()
        assertTrue("se mueve ${"%.2f".format(still)}", still <= 0.15)
    }

    @Test
    fun laPruebaDeGustoNoRepiteVersionesEnElTope() {
        val l = StyleLearner(Random(6))
        repeat(12) { l.tasteVote(SceneGroup.COMIDA, Dimension.CALIDEZ, 1) }
        assertEquals(3, l.style(SceneGroup.COMIDA).warmth)
        assertEquals(listOf(-1, 0), l.tasteOptions(SceneGroup.COMIDA, Dimension.CALIDEZ).map { it.first })
        // "Me da igual" no mueve nada.
        repeat(10) { assertNull(l.tasteVote(SceneGroup.NOCHE, Dimension.LUZ, null)) }
        assertEquals(StyleAdjust.NONE, l.style(SceneGroup.NOCHE))
    }

    @Test
    fun alDarUnPasoNoSeTiraLoSabido() {
        val l = StyleLearner(Random(7))
        repeat(8) { l.feedback(SceneGroup.PERSONAS, Variant.BASE, liked = false) }
        repeat(3) { l.feedback(SceneGroup.PERSONAS, Variant.CALIDA, liked = true) }
        assertEquals(StyleAdjust(warmth = 1), l.style(SceneGroup.PERSONAS))
        val g = l.summary().single().second
        // La versión "más cálida" (3 👍) es ahora tu estilo, y el estilo de antes (8 👎) es el paso de vuelta.
        assertEquals(1f, g.arms.getValue(Variant.BASE).mean, 0.21f)
        assertEquals(0f, g.arms.getValue(Variant.FRIA).mean, 0.11f)
        assertEquals(0f, g.arms.getValue(Variant.CALIDA).trials, 1e-6f)
    }

    @Test
    fun seGuardaYSeRecupera() {
        val l = StyleLearner(Random(5))
        repeat(6) { l.feedback(SceneGroup.NOCHE, Variant.BASE, liked = false) }
        repeat(4) { l.feedback(SceneGroup.NOCHE, Variant.CLARA, liked = true) }
        l.choose(SceneGroup.NOCHE)
        val copy = StyleLearner(Random(5)).apply { decode(l.encode()) }
        assertEquals(l.style(SceneGroup.NOCHE), copy.style(SceneGroup.NOCHE))
        assertEquals(l.encode(), copy.encode())
        assertEquals(StyleAdjust(bright = 1), copy.style(SceneGroup.NOCHE))
        // Un texto roto no rompe nada.
        StyleLearner().decode("basura;;\nPERSONAS;x;y")
    }

    @Test
    fun deLoQueBorrasTambienSeAprende() {
        val j = ShotJournal()
        val t0 = 1_000_000L
        j.add(ShotJournal.Entry(t0, SceneGroup.PERSONAS, Variant.CALIDA, "o1", "m1", bothKept = true))
        j.add(ShotJournal.Entry(t0, SceneGroup.PAISAJES, Variant.BASE, "o2", "m2", bothKept = true))
        j.add(ShotJournal.Entry(t0, SceneGroup.COMIDA, Variant.VIVA, "o3", "m3", bothKept = true))
        j.add(ShotJournal.Entry(t0, SceneGroup.NOCHE, Variant.BASE, "o4", "m4", bothKept = true))
        j.markRated("m4")
        val gone = setOf("o1", "m2", "o3", "m3")
        // Demasiado pronto: aún no se sabe qué vas a hacer.
        assertTrue(j.check(t0 + 60_000) { it !in gone }.isEmpty())
        val signals = j.check(t0 + ShotJournal.MIN_AGE_MS + 1) { it !in gone }
        assertEquals(2, signals.size)
        assertTrue(signals.first { it.entry.group == SceneGroup.PERSONAS }.liked) // borraste la original
        assertTrue(!signals.first { it.entry.group == SceneGroup.PAISAJES }.liked) // borraste la mejorada
        assertEquals(0, j.size()) // la de comida (borradas las dos) y la valorada ya no esperan nada
        // Si no se puede saber si existe, se espera.
        j.add(ShotJournal.Entry(t0, SceneGroup.PERSONAS, Variant.BASE, "o5", "m5", bothKept = true))
        assertTrue(j.check(t0 + ShotJournal.MIN_AGE_MS + 1) { null }.isEmpty())
        assertEquals(1, j.size())
        val copy = ShotJournal().apply { decode(j.encode()) }
        assertEquals(j.encode(), copy.encode())
    }

    @Test
    fun cadaEscenaEnSuGrupo() {
        SceneType.entries.forEach { assertNotNull(SceneGroup.of(it)) }
        assertEquals(SceneGroup.PERSONAS, SceneGroup.of(SceneType.SELFIE))
        assertEquals(SceneGroup.OTRAS, SceneGroup.of(SceneType.GENERAL))
        // Cada escena está en un solo grupo.
        assertEquals(SceneType.entries.size, SceneGroup.entries.sumOf { it.scenes.size })
    }

    @Test
    fun muestreoBetaRazonable() {
        val rnd = Random(9)
        val a = Arm(9f, 1f)
        val mean = (1..4000).map { a.sample(rnd) }.average()
        assertEquals(0.9, mean, 0.02)
        val u = Arm()
        val m2 = (1..4000).map { u.sample(rnd) }.average()
        assertEquals(0.5, m2, 0.03)
    }
}
