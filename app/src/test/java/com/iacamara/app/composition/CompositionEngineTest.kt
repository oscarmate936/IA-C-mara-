package com.iacamara.app.composition

import com.iacamara.app.perception.DetectedObject
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SaliencyMap
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionEngineTest {

    private fun input(
        scene: SceneType = SceneType.RETRATO,
        kind: SubjectKind? = SubjectKind.CARA,
        box: NRect? = null,
        gaze: Float? = null,
        roll: Float? = 0f,
        horizonY: Float? = null,
        interestingSky: Boolean = false,
        saliency: SaliencyMap? = null,
        front: Boolean = false,
        degrees: Int = 0,
        named: List<DetectedObject> = emptyList(),
    ) = CompositionInput(
        scene = scene,
        subjectKind = kind,
        subjectBox = box,
        faces = listOfNotNull(box),
        gazeX = gaze,
        saliency = saliency,
        rollDegrees = roll,
        horizonY = horizonY,
        interestingSky = interestingSky,
        highlightClip = 0f,
        shadowClip = 0f,
        blurRisk = false,
        front = front,
        deviceDegrees = degrees,
        namedObjects = named,
    )

    @Test
    fun caraEnElPuntoFuerteEsBuenEncuadre() {
        // Cara centrada en el tercio izquierdo, con los ojos en el tercio superior.
        val g = CompositionEngine.evaluate(input(box = NRect(0.253f, 0.269f, 0.413f, 0.429f)))
        assertTrue("puntuación ${g.score}", g.score >= 95)
        assertNull(g.message)
        assertTrue(g.isGood)
    }

    @Test
    fun caraPegadaAlBordePideGirar() {
        // Cara muy a la izquierda: debe ir al tercio izquierdo → el móvil gira a la izquierda.
        val g = CompositionEngine.evaluate(input(box = NRect(0.02f, 0.3f, 0.12f, 0.4f)))
        assertEquals(Move.IZQUIERDA, g.move)
        assertTrue(g.score < Guidance.GOOD_SCORE)
    }

    @Test
    fun espacioHaciaDondeMira() {
        // Cara a la derecha mirando a la derecha: hay que dejarle espacio delante (tercio izquierdo).
        val g = CompositionEngine.evaluate(input(box = NRect(0.6f, 0.27f, 0.74f, 0.41f), gaze = 0.6f))
        val target = g.target!!
        assertEquals(1f / 3f, target.first, 0.01f)
        assertTrue(g.message!!.contains("mira"))
    }

    @Test
    fun camaraFrontalEnEspejo() {
        // En la frontal, lo que está a la izquierda de la imagen el usuario lo ve a la derecha.
        val g = CompositionEngine.evaluate(input(scene = SceneType.RETRATO, box = NRect(0.02f, 0.3f, 0.12f, 0.4f), front = true))
        assertEquals(Move.DERECHA, g.move)
    }

    @Test
    fun horizonteEnElCentroSeCorrige() {
        val g = CompositionEngine.evaluate(
            input(scene = SceneType.PAISAJE, kind = null, box = null, horizonY = 0.5f, interestingSky = false),
        )
        assertEquals(Move.ABAJO, g.move)
        assertTrue(g.score < Guidance.GOOD_SCORE)
        assertTrue(g.horizonTarget != null)
    }

    @Test
    fun cieloBonitoPideMasCielo() {
        val g = CompositionEngine.evaluate(
            input(scene = SceneType.ATARDECER, kind = null, box = null, horizonY = 0.4f, interestingSky = true),
        )
        // Con cielo bonito el horizonte va al tercio inferior: hay que inclinar hacia arriba.
        assertEquals(Move.ARRIBA, g.move)
    }

    @Test
    fun giroGrandeEsIntencionado() {
        // 35°: plano holandés a propósito. No se pide enderezar ni baja la nota.
        val g = CompositionEngine.evaluate(input(box = NRect(0.253f, 0.269f, 0.413f, 0.429f), roll = 35f))
        assertNull(g.criteria["nivel"])
        assertTrue(g.message == null || !g.message!!.contains("Endereza"))
    }

    @Test
    fun mirandoAlTechoSoloSeCorrigenGirosPequenos() {
        val box = NRect(0.253f, 0.269f, 0.413f, 0.429f)
        val grande = CompositionEngine.evaluate(input(box = box, roll = 15f).copy(steepView = true))
        assertNull(grande.criteria["nivel"])
        val pequeno = CompositionEngine.evaluate(input(box = box, roll = 6f).copy(steepView = true))
        assertTrue(pequeno.message!!.contains("Endereza"))
        // De frente, 15° sí se corrige.
        val frente = CompositionEngine.evaluate(input(box = box, roll = 15f))
        assertTrue(frente.message!!.contains("Endereza"))
    }

    @Test
    fun movilTorcido() {
        val g = CompositionEngine.evaluate(input(box = NRect(0.253f, 0.269f, 0.413f, 0.429f), roll = 4f))
        assertTrue(g.message!!.contains("Endereza"))
        assertTrue(g.message!!.contains("derecha"))
    }

    @Test
    fun distraccionEnElBordeConNombre() {
        // Saliencia solo en la franja de abajo, y YOLOE sabe que ahí hay una pila de troncos.
        val w = 24
        val h = 32
        val values = FloatArray(w * h) { k -> if (k / w >= h - 3 && k % w in 8..15) 1f else 0f }
        val g = CompositionEngine.evaluate(
            input(
                scene = SceneType.PAISAJE,
                kind = null,
                box = null,
                saliency = SaliencyMap(w, h, values),
                named = listOf(DetectedObject("Pila de troncos", NRect(0.3f, 0.9f, 0.7f, 1f), 0.5f)),
            ),
        )
        assertTrue(g.message!!, g.message!!.contains("«Pila de troncos»"))
        assertTrue(g.message!!.contains("abajo"))
    }

    @Test
    fun coordenadasDeVistaIdaYVuelta() {
        for (degrees in listOf(0, 90, 180, 270)) {
            for (front in listOf(false, true)) {
                val i = input(degrees = degrees, front = front)
                val (vx, vy) = CompositionEngine.toView(0.2f, 0.7f, i)
                val (px, py) = CompositionEngine.fromView(vx, vy, i)
                assertEquals(0.2f, px, 1e-5f)
                assertEquals(0.7f, py, 1e-5f)
            }
        }
    }
}
