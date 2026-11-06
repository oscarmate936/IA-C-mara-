package com.iacamara.app.coach

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.director.CaptureDirector
import com.iacamara.app.director.CaptureStrategy
import com.iacamara.app.perception.CaptureMeta
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.DetectedObject
import com.iacamara.app.perception.FaceDetail
import com.iacamara.app.perception.FaceLight
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.perception.SceneStructure
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.Segment
import com.iacamara.app.perception.Subject
import com.iacamara.app.perception.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {

    private val face = NRect(0.42f, 0.25f, 0.58f, 0.40f)

    private fun perception(
        scene: SceneType = SceneType.RETRATO,
        conditions: Set<Condition> = emptySet(),
        faces: List<NRect> = listOf(face),
        faceLight: FaceLight? = null,
        structure: SceneStructure? = null,
        details: List<FaceDetail> = emptyList(),
        ev: Boolean = true,
        named: List<DetectedObject> = emptyList(),
        subject: Subject? = Subject(SubjectKind.CARA, "Cara", face, 1f),
    ) = Perception(
        scene = scene,
        conditions = conditions,
        subject = subject,
        faces = faces,
        light = null,
        // EV ≈ 14: exterior de día.
        meta = if (ev) CaptureMeta(iso = 100, exposureNs = 1_000_000L, aperture = 1.8f, focusDistanceCm = null, timestampMs = 0L) else null,
        labels = emptyList(),
        frameMs = 10,
        timestampMs = 0,
        faceDetails = details,
        namedObjects = named,
        structure = structure,
        faceLight = faceLight,
    )

    private fun ids(p: Perception, pitch: Float? = 0f, front: Boolean = false) =
        ExpertRules.evaluate(ExpertInput(p, front, pitch)).map { it.id }

    // ---------------------------------------------------------------- reglas del fotógrafo

    @Test
    fun luzSobreLaCara() {
        assertTrue("luz.contraluz" in ids(perception(conditions = setOf(Condition.CONTRALUZ))))
        // Sol de mediodía: frente y mejillas claras, ojos en sombra.
        val overhead = FaceLight(forehead = 200f, eyes = 110f, cheeks = 190f, left = 170f, right = 165f, mean = 170f, clipped = 0.02f)
        assertTrue("luz.cenital" in ids(perception(faceLight = overhead)))
        // Media cara en sombra.
        val side = FaceLight(forehead = 120f, eyes = 110f, cheeks = 120f, left = 200f, right = 60f, mean = 130f, clipped = 0.01f)
        assertTrue("luz.lateral" in ids(perception(faceLight = side)))
        // Luz pareja: ningún consejo de luz.
        val even = FaceLight(forehead = 150f, eyes = 140f, cheeks = 150f, left = 150f, right = 145f, mean = 148f, clipped = 0f)
        assertTrue(ids(perception(faceLight = even)).none { it.startsWith("luz.") })
    }

    @Test
    fun algoQueSaleDeLaCabeza() {
        // Un poste vertical justo detrás del centro de la cara, que sigue hacia arriba.
        val pole = Segment(0.51f, 0.02f, 0.51f, 0.30f)
        val structure = SceneStructure(leadingLines = emptyList(), vanishingPoint = null, symmetryAxis = null, reflectionAxis = null, verticals = listOf(pole))
        val lamp = DetectedObject("Farola", NRect(0.47f, 0.0f, 0.55f, 0.3f), 0.6f)
        val advice = ExpertRules.evaluate(ExpertInput(perception(structure = structure, named = listOf(lamp)), false, 0f))
            .first { it.id == "fondo.cabeza" }
        assertTrue(advice.variants.first(), advice.variants.first().startsWith("Una farola"))
        // El poste está a la derecha del centro de la cara: el paso es hacia la derecha.
        assertTrue(advice.variants.first().endsWith("derecha"))
        // Un poste lejos de la cabeza, no.
        val far = SceneStructure(emptyList(), null, null, null, verticals = listOf(Segment(0.9f, 0.0f, 0.9f, 0.5f)))
        assertTrue("fondo.cabeza" !in ids(perception(structure = far)))
    }

    @Test
    fun momentoYAngulo() {
        val two = listOf(FaceDetail(face, eyesOpen = true, gazeX = 0f), FaceDetail(face, eyesOpen = false, gazeX = 0f))
        assertTrue("momento.ojos" in ids(perception(scene = SceneType.GRUPO, details = two)))
        // Desde abajo a una persona; con la frontal (selfie) no se dice nada.
        assertTrue("angulo.contrapicado" in ids(perception(), pitch = 25f))
        assertTrue("angulo.contrapicado" !in ids(perception(), pitch = 25f, front = true))
        assertTrue("angulo.mascota" in ids(perception(scene = SceneType.MASCOTA, subject = null, faces = emptyList()), pitch = -40f))
    }

    // ---------------------------------------------------------------- sesión del coach

    private fun advice(id: String, priority: Float, vararg text: String, habit: Habit? = null) =
        Advice(id, priority, text.toList(), habit = habit)

    @Test
    fun unSoloConsejoEstableYSinParpadeos() {
        val s = CoachSession()
        val a = advice("a", 0.5f, "A")
        val b = advice("b", 0.6f, "B")
        assertEquals("a", s.update(listOf(a), emptyList(), 0)?.id)
        // Aparece otro algo más importante: el primero se queda hasta que dé tiempo a leerlo.
        assertEquals("a", s.update(listOf(a, b), emptyList(), 1000)?.id)
        assertEquals("b", s.update(listOf(a, b), emptyList(), 4000)?.id)
        // Algo mucho más importante entra enseguida.
        val urgent = advice("u", 0.95f, "U")
        assertEquals("u", s.update(listOf(b, urgent), emptyList(), 4100)?.id)
        // Si el problema se arregla, desaparece tras un margen corto.
        assertEquals("u", s.update(emptyList(), emptyList(), 4300)?.id)
        assertNull(s.update(emptyList(), emptyList(), 5000))
    }

    @Test
    fun cadaVezLoDiceDeOtraFormaYRecuerdaLosHabitos() {
        val s = CoachSession()
        val tilt = advice("encuadre.nivel", 0.5f, "Endereza el móvil", "Pon el móvil recto", habit = Habit.TORCIDO)
        val first = s.update(listOf(tilt), emptyList(), 0)!!.text
        s.update(emptyList(), emptyList(), 2000)
        val second = s.update(listOf(tilt), emptyList(), 3000)!!.text
        assertNotEquals(first, second)
        // Con el hábito: sube de prioridad y lo recuerda.
        val habit = HabitCount(Habit.TORCIDO, 5, 10)
        val other = advice("otro", 0.6f, "Otro")
        val s2 = CoachSession()
        val tip = s2.update(listOf(tilt, other), listOf(habit), 0)!!
        assertEquals("encuadre.nivel", tip.id)
        assertTrue(tip.text, tip.text.startsWith("Sueles torcer el horizonte (5 de tus últimas 10 fotos)."))
    }

    // ---------------------------------------------------------------- hábitos

    private class MemoryStore : HabitStore {
        var saved: List<Set<Habit>> = emptyList()
        override fun load() = saved
        override fun save(history: List<Set<Habit>>) {
            saved = history.toList()
        }
    }

    @Test
    fun habitosDetectadosSoloCuandoSeRepiten() {
        val store = MemoryStore()
        val t = HabitTracker(store)
        repeat(3) { t.record(setOf(Habit.TORCIDO)) }
        assertTrue("pocas fotos todavía", t.active().isEmpty())
        t.record(setOf(Habit.TORCIDO))
        t.record(emptySet())
        // 4 de 5: ya es un hábito.
        val active = t.active()
        assertEquals(listOf(Habit.TORCIDO), active.map { it.habit })
        assertEquals("4 de tus últimas 5 fotos", active.first().text)
        // Se recuerda entre sesiones.
        assertEquals(listOf(Habit.TORCIDO), HabitTracker(store).active().map { it.habit })
        // Si dejas de hacerlo, deja de serlo (cuentan las 10 últimas).
        repeat(7) { t.record(emptySet()) }
        assertTrue(t.active().isEmpty())
        // Se guarda y se recupera igual, y se puede olvidar.
        assertEquals(store.saved, HabitTracker.decode(HabitTracker.encode(store.saved)))
        t.clear()
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun fallosDeUnaFoto() {
        val flags = HabitFlags.of(
            criteria = mapOf("nivel" to 0.4f, "sin cortes" to 0.3f),
            subjectCenterX = 0.5f, targetX = 0.33f, centeredStyle = false,
            shaky = false, blurry = true, horizonY = null, hasSubject = true,
        )
        assertEquals(setOf(Habit.TORCIDO, Habit.CENTRADO, Habit.MOVIDA, Habit.CORTES), flags)
        // Con la guía de centro, el sujeto centrado no es un fallo.
        val centered = HabitFlags.of(emptyMap(), 0.5f, 0.5f, true, false, false, null, true)
        assertTrue(centered.isEmpty())
    }

    // ---------------------------------------------------------------- crítica

    private fun review(blur: Float? = 1f, score: Int? = 85, lowLight: Boolean = false, backlit: Boolean = false) = ShotReview(
        scene = SceneType.RETRATO, compositionScore = score, compositionTip = "Gira un poco a la derecha",
        criteria = emptyMap(), blurPx = blur, moving = false, lowLight = lowLight, backlit = backlit, faceLight = null,
        technique = "Foto", reason = "Buena luz y escena quieta: con una foto basta", burst = false, flags = emptySet(),
    )

    private val goodStats = PhotoStats(mean = 0.45f, highlightClip = 0f, shadowClip = 0.01f, saturation = 0.3f, amber = 0.02f, magenta = 0f, neutral = 0.2f)

    @Test
    fun criticaDeUnaFotoBuenaYDeUnaConFallos() {
        val good = PhotoCritic.critique(review(), goodStats, listOf(FaceDetail(face, true, 0f)), 5f, emptyList())
        assertTrue("nota ${good.overall}", good.overall >= 85)
        assertNotNull(good.best)
        assertEquals(listOf("Composición", "Luz", "Nitidez", "Momento", "Color"), good.parts.map { it.name })
        assertTrue(good.technique.contains("Buena luz"))

        val burnt = goodStats.copy(highlightClip = 0.06f, magenta = 0.15f)
        val bad = PhotoCritic.critique(review(blur = 8f, score = 55, backlit = true), burnt, listOf(FaceDetail(face, false, 0f)), 3.5f, listOf(HabitCount(Habit.MOVIDA, 5, 10)))
        assertTrue("nota ${bad.overall}", bad.overall < 60)
        // Dos consejos de lo más grave y uno sobre el hábito.
        assertEquals(3, bad.tips.size)
        assertTrue(bad.tips.last(), bad.tips.last().startsWith("Llevas 5 de tus últimas 10 fotos movidas"))
        assertEquals("tono rosado", bad.parts.first { it.name == "Color" }.note)
    }

    @Test
    fun medidasDeLaFoto() {
        // Mitad gris neutro, mitad quemada: luces quemadas y color neutro.
        val px = IntArray(100 * 100) { i -> if (i < 5000) 0xFF808080.toInt() else 0xFFFFFFFF.toInt() }
        val s = PhotoStats.of(px, 100, 100)
        assertTrue(s.highlightClip in 0.45f..0.55f)
        assertTrue(kotlin.math.abs(s.amber) < 0.01f && kotlin.math.abs(s.magenta) < 0.01f)
        // Gris con un tinte rosa: magenta > 0.
        val pink = IntArray(100 * 100) { 0xFF9A8098.toInt() }
        assertTrue(PhotoStats.of(pink, 100, 100).magenta > 0.08f)
    }

    // ---------------------------------------------------------------- explicación del Director

    @Test
    fun elDirectorExplicaSusDecisionesYCompensaLosHabitos() {
        val night = perception(scene = SceneType.NOCHE, conditions = setOf(Condition.POCA_LUZ), faces = emptyList(), subject = null)
        val plan = CaptureDirector.plan(PhotoMode.FOTO, night, iaEnabled = true, flashOn = false)
        assertEquals(CaptureStrategy.NIGHT, plan.strategy)
        assertTrue(plan.reason, plan.reason.startsWith("Hay poca luz"))
        val calm = perception(scene = SceneType.PAISAJE, faces = emptyList(), subject = null)
        val single = CaptureDirector.plan(PhotoMode.FOTO, calm, iaEnabled = true, flashOn = false)
        assertEquals(CaptureStrategy.SINGLE, single.strategy)
        assertTrue(single.reason.isNotBlank())
        // Si sueles hacer fotos movidas, la cámara hace una ráfaga y elige la más nítida.
        val shaky = CaptureDirector.plan(PhotoMode.FOTO, calm, iaEnabled = true, flashOn = false, habits = setOf(Habit.MOVIDA))
        assertEquals(CaptureStrategy.BEST_OF_BURST, shaky.strategy)
        assertTrue(shaky.reason.contains("movidas"))
        assertTrue(CaptureDirector.plan(PhotoMode.NOCHE_IA, night, true, flashOn = true).reason.contains("flash"))
    }
}
