package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleCheckTest {

    private val pants = DetectedObject("person", NRect(0.1f, 0.4f, 0.2f, 0.8f), 0.55f)
    private val clearPerson = DetectedObject("person", NRect(0.4f, 0.2f, 0.6f, 0.9f), 0.85f)

    private fun pose(box: NRect, visible: Boolean = true): BodyPose {
        val xs = FloatArray(BodyPose.COUNT) { box.left + box.width * (it % 5) / 4f }
        val ys = FloatArray(BodyPose.COUNT) { box.top + box.height * (it / 7) / 4f }
        return BodyPose(xs, ys, FloatArray(BodyPose.COUNT) { if (visible) 0.9f else 0.1f })
    }

    @Test
    fun deNocheLaRopaTendidaNoEsUnaPersona() {
        // Unos pantalones colgados con una puntuación justa, sin cara ni cuerpo que lo confirmen.
        val kept = PeopleCheck.people(listOf(pants, clearPerson), faces = emptyList(), pose = null, lowLight = true)
        assertEquals(listOf(clearPerson), kept)
        // Lo mismo para el detector de vocabulario abierto (el "«Persona» distrae en el borde").
        val named = PeopleCheck.named(listOf(pants.copy(label = "person")), kept, emptyList(), null, lowLight = true)
        assertTrue(named.isEmpty())
    }

    @Test
    fun unaPersonaDudosaConfirmadaPorElCuerpoOLaCaraSiCuenta() {
        val byPose = PeopleCheck.people(listOf(pants), faces = emptyList(), pose = pose(pants.box), lowLight = true)
        assertEquals(1, byPose.size)
        val face = NRect(0.12f, 0.42f, 0.17f, 0.48f)
        val byFace = PeopleCheck.people(listOf(pants), faces = listOf(face), pose = null, lowLight = true)
        assertEquals(1, byFace.size)
        // Un cuerpo con casi nada visible no confirma nada.
        assertTrue(PeopleCheck.people(listOf(pants), emptyList(), pose(pants.box, visible = false), lowLight = true).isEmpty())
    }

    @Test
    fun deDiaBastaMenosSeguridad() {
        assertEquals(1, PeopleCheck.people(listOf(pants), emptyList(), null, lowLight = false).size)
        // Los demás objetos no se tocan.
        val dog = DetectedObject("dog", NRect(0f, 0f, 0.2f, 0.2f), 0.45f)
        assertEquals(listOf(dog), PeopleCheck.people(listOf(dog), emptyList(), null, lowLight = true))
    }

    @Test
    fun unaCaraDudosaNecesitaLosPuntosDeLaCara() {
        val box = NRect(0.4f, 0.3f, 0.5f, 0.42f)
        val weak = PeopleCheck.RawFace(box, 0.62f)
        // Sin los 478 puntos que la confirmen, no cuenta (ni de día ni de noche).
        assertTrue(PeopleCheck.faces(listOf(weak), emptyList(), lowLight = false).isEmpty())
        assertTrue(PeopleCheck.faces(listOf(weak), emptyList(), lowLight = true).isEmpty())
        // Con los puntos de la cara en el mismo sitio, sí.
        val landmarks = NRect(0.41f, 0.29f, 0.51f, 0.43f)
        assertEquals(1, PeopleCheck.faces(listOf(weak), listOf(landmarks), lowLight = true).size)
        // Una cara muy clara cuenta sola de día, pero de noche hace falta más.
        val strong = PeopleCheck.RawFace(box, 0.78f)
        assertEquals(1, PeopleCheck.faces(listOf(strong), emptyList(), lowLight = false).size)
        assertTrue(PeopleCheck.faces(listOf(strong), emptyList(), lowLight = true).isEmpty())
        assertEquals(1, PeopleCheck.faces(listOf(strong.copy(score = 0.86f)), emptyList(), lowLight = true).size)
    }

    @Test
    fun enUnGrupoCadaCaraEstaEnSuPersona() {
        // Caras pequeñas (los 478 puntos no las ven) con confianza media, cada una en la cabeza de una persona.
        val person = DetectedObject("person", NRect(0.40f, 0.30f, 0.46f, 0.70f), 0.8f)
        val face = PeopleCheck.RawFace(NRect(0.420f, 0.31f, 0.440f, 0.335f), 0.6f)
        assertEquals(1, PeopleCheck.faces(listOf(face), emptyList(), lowLight = false, objects = listOf(person)).size)
        // A la altura de las rodillas no es su cara.
        val knee = face.copy(box = NRect(0.420f, 0.60f, 0.440f, 0.625f))
        assertTrue(PeopleCheck.faces(listOf(knee), emptyList(), lowLight = false, objects = listOf(person)).isEmpty())
        // Una persona dudosa no confirma caras (son las caras las que la confirman a ella).
        val unsure = person.copy(score = 0.4f)
        assertTrue(PeopleCheck.faces(listOf(face), emptyList(), lowLight = false, objects = listOf(unsure)).isEmpty())
        // De noche hace falta algo más.
        assertTrue(PeopleCheck.faces(listOf(face), emptyList(), lowLight = true, objects = listOf(person)).isEmpty())
    }

    @Test
    fun laCaraDeUnPerroNoEsUnaCara() {
        val dog = DetectedObject("dog", NRect(0.1f, 0.5f, 0.4f, 0.95f), 0.7f)
        val dogFace = PeopleCheck.RawFace(NRect(0.2f, 0.55f, 0.28f, 0.63f), 0.76f)
        assertTrue(PeopleCheck.faces(listOf(dogFace), emptyList(), lowLight = false, objects = listOf(dog)).isEmpty())
        // Sin el perro detectado, una cara así de clara sí cuenta.
        assertEquals(1, PeopleCheck.faces(listOf(dogFace), emptyList(), lowLight = false).size)
        // Una persona con el perro en brazos: su cara está en la persona y cuenta.
        val owner = DetectedObject("person", NRect(0.12f, 0.45f, 0.38f, 1f), 0.85f)
        val ownerFace = PeopleCheck.RawFace(NRect(0.2f, 0.47f, 0.28f, 0.55f), 0.8f)
        assertEquals(1, PeopleCheck.faces(listOf(ownerFace), emptyList(), lowLight = false, objects = listOf(dog, owner)).size)
    }
}
