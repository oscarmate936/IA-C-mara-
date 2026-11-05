package com.iacamara.app.composition

import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SaliencyMap
import com.iacamara.app.perception.SceneStructure
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.Segment
import com.iacamara.app.perception.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidesTest {

    private fun input(
        scene: SceneType = SceneType.GENERAL,
        kind: SubjectKind? = SubjectKind.OBJETO,
        box: NRect? = null,
        horizonY: Float? = null,
        saliency: SaliencyMap? = null,
        structure: SceneStructure? = null,
        style: GuideStyle? = null,
        degrees: Int = 0,
    ) = CompositionInput(
        scene = scene,
        subjectKind = kind,
        subjectBox = box,
        faces = emptyList(),
        gazeX = null,
        saliency = saliency,
        rollDegrees = 0f,
        horizonY = horizonY,
        interestingSky = false,
        highlightClip = 0f,
        shadowClip = 0f,
        blurRisk = false,
        front = false,
        deviceDegrees = degrees,
        structure = structure,
        style = style,
    )

    /** Saliencia solo dentro de la caja: todo lo demás es espacio libre. */
    private fun saliencyIn(box: NRect, w: Int = 24, h: Int = 32) = SaliencyMap(
        w, h,
        FloatArray(w * h) { k ->
            val x = (k % w + 0.5f) / w
            val y = (k / w + 0.5f) / h
            if (x in box.left..box.right && y in box.top..box.bottom) 1f else 0f
        },
    )

    private fun structure(
        lines: List<Segment> = emptyList(),
        vp: Pair<Float, Float>? = null,
        symmetryX: Float? = null,
        reflectionY: Float? = null,
    ) = SceneStructure(
        leadingLines = lines,
        vanishingPoint = vp,
        symmetryAxis = symmetryX?.let { Segment(it, 0f, it, 1f) },
        reflectionAxis = reflectionY?.let { Segment(0f, it, 1f, it) },
    )

    @Test
    fun laEspiralAcabaEnSuOjo() {
        for (portrait in listOf(true, false)) for (right in listOf(true, false)) for (bottom in listOf(true, false)) {
            val points = GoldenRatio.spiral(portrait, right, bottom)
            val (ex, ey) = GoldenRatio.eye(right, bottom)
            val last = points.last()
            assertEquals(ex, last.first, 0.02f)
            assertEquals(ey, last.second, 0.02f)
            assertTrue(points.all { it.first in -0.001f..1.001f && it.second in -0.001f..1.001f })
            // La espiral empieza en una esquina del encuadre (la opuesta al ojo en horizontal).
            val first = points.first()
            assertTrue(first.first < 0.01f || first.first > 0.99f || first.second < 0.01f || first.second > 0.99f)
        }
    }

    @Test
    fun sujetoPequenoConEspacioLibreUsaLaEspiral() {
        val box = NRect(0.6f, 0.62f, 0.72f, 0.74f)
        val g = CompositionEngine.evaluate(input(box = box, saliency = saliencyIn(box)))
        assertEquals(GuideStyle.ESPIRAL, g.style)
        assertTrue(g.shapes.any { it.kind == ShapeKind.SPIRAL })
        // El sujeto (abajo a la derecha) va al ojo de la espiral de esa esquina.
        assertEquals(GoldenRatio.EYE, g.target!!.first, 0.01f)
        assertEquals(GoldenRatio.EYE, g.target!!.second, 0.01f)
    }

    @Test
    fun sujetoMedianoUsaLaProporcionAurea() {
        val g = CompositionEngine.evaluate(input(box = NRect(0.2f, 0.25f, 0.56f, 0.55f)))
        assertEquals(GuideStyle.AUREA, g.style)
        assertEquals(GoldenRatio.MINOR, g.target!!.first, 0.01f)
        assertEquals(4, g.shapes.count { it.kind == ShapeKind.GRID })
    }

    @Test
    fun sujetoGrandeVaAlCentro() {
        val g = CompositionEngine.evaluate(input(box = NRect(0.1f, 0.15f, 0.9f, 0.85f)))
        assertEquals(GuideStyle.CENTRO, g.style)
        assertTrue(g.shapes.any { it.kind == ShapeKind.CENTER })
    }

    @Test
    fun escenaSimetricaPideCentrarElEje() {
        val g = CompositionEngine.evaluate(input(kind = null, structure = structure(symmetryX = 0.42f)))
        assertEquals(GuideStyle.SIMETRIA, g.style)
        // El eje está a la izquierda: para llevarlo al centro hay que girar a la izquierda.
        assertEquals(Move.IZQUIERDA, g.move)
        assertTrue(g.message!!, g.message!!.contains("simétrica"))
        assertTrue(g.shapes.any { it.kind == ShapeKind.AXIS })
    }

    @Test
    fun sujetoEnElEjeDeUnaEscenaSimetrica() {
        val g = CompositionEngine.evaluate(
            input(box = NRect(0.45f, 0.5f, 0.55f, 0.7f), structure = structure(symmetryX = 0.5f)),
        )
        assertEquals(GuideStyle.SIMETRIA, g.style)
        assertEquals(0.5f, g.target!!.first, 0.001f)
        assertTrue("puntuación ${g.score}", g.isGood)
    }

    @Test
    fun reflejoEnElCentroEstaBien() {
        // En un reflejo, el horizonte en el medio es lo correcto: no hay que avisar de que corta la foto.
        val g = CompositionEngine.evaluate(
            input(scene = SceneType.PAISAJE, kind = null, horizonY = 0.5f, structure = structure(reflectionY = 0.5f)),
        )
        assertEquals(GuideStyle.SIMETRIA, g.style)
        assertNull(g.message)
        assertTrue("puntuación ${g.score}", g.isGood)
    }

    @Test
    fun puntoDeFugaAlCentro() {
        // Camino: dos líneas que llegan por los dos lados a un punto de fuga demasiado alto.
        val vp = 0.5f to 0.15f
        val lines = listOf(Segment(0.1f, 1f, 0.5f, 0.15f), Segment(0.9f, 1f, 0.5f, 0.15f))
        val g = CompositionEngine.evaluate(input(kind = null, structure = structure(lines, vp)))
        assertEquals(GuideStyle.LINEAS, g.style)
        assertEquals(0.5f, g.target!!.first, 0.001f)
        assertEquals(1f / 3f, g.target!!.second, 0.001f)
        // Para bajar el punto de fuga en la imagen hay que inclinar hacia arriba.
        assertEquals(Move.ARRIBA, g.move)
        assertTrue(g.message!!.contains("se juntan"))
        assertEquals(2, g.shapes.count { it.kind == ShapeKind.LEADING_LINE })
    }

    @Test
    fun lineasQueLlevanAlSujeto() {
        val box = NRect(0.6f, 0.3f, 0.72f, 0.42f)
        val lines = listOf(Segment(0.05f, 1f, 0.62f, 0.4f), Segment(0.5f, 1f, 0.66f, 0.42f))
        val g = CompositionEngine.evaluate(input(box = box, structure = structure(lines, 0.66f to 0.38f)))
        assertEquals(GuideStyle.LINEAS, g.style)
        assertEquals(1f, g.criteria["líneas"]!!, 0.001f)
    }

    @Test
    fun unaVallaQuePasaPorDetrasNoLlevaAlSujeto() {
        val box = NRect(0.4f, 0.4f, 0.55f, 0.8f)
        val fence = listOf(Segment(0f, 0.5f, 1f, 0.75f))
        val g = CompositionEngine.evaluate(input(box = box, structure = structure(fence)))
        assertFalse(g.style == GuideStyle.LINEAS)
    }

    @Test
    fun laGuiaElegidaPorElUsuarioManda() {
        val g = CompositionEngine.evaluate(
            input(kind = null, structure = structure(symmetryX = 0.5f), style = GuideStyle.TERCIOS),
        )
        assertEquals(GuideStyle.TERCIOS, g.style)
        assertEquals(GuideStyle.SIMETRIA, CompositionEngine.chooseStyle(input(kind = null, structure = structure(symmetryX = 0.5f))))
    }

    @Test
    fun fotoDesequilibrada() {
        // Todo lo que llama la atención está en la parte izquierda (sin tocar el borde).
        val map = saliencyIn(NRect(0.13f, 0.3f, 0.25f, 0.7f))
        val g = CompositionEngine.evaluate(input(kind = null, saliency = map))
        assertTrue(g.criteria["equilibrio"]!! < 0.6f)
        assertTrue(g.message!!, g.message!!.contains("izquierda"))
    }

    @Test
    fun lasGuiasSiguenAlMovilEnHorizontal() {
        // Con el móvil en horizontal, la línea vertical de la rejilla (vista) es horizontal en P.
        val g = CompositionEngine.evaluate(input(kind = null, style = GuideStyle.TERCIOS, degrees = 90))
        val first = g.shapes.first { it.kind == ShapeKind.GRID }
        assertEquals(first.points[0].second, first.points[1].second, 0.001f)
    }

    @Test
    fun laGuiaAutomaticaNoParpadea() {
        val s = GuideStyleStabilizer(needed = 3)
        assertEquals(GuideStyle.TERCIOS, s.update(GuideStyle.TERCIOS))
        assertEquals(GuideStyle.TERCIOS, s.update(GuideStyle.AUREA))
        assertEquals(GuideStyle.TERCIOS, s.update(GuideStyle.TERCIOS))
        assertEquals(GuideStyle.TERCIOS, s.update(GuideStyle.AUREA))
        assertEquals(GuideStyle.TERCIOS, s.update(GuideStyle.AUREA))
        assertEquals(GuideStyle.AUREA, s.update(GuideStyle.AUREA))
    }
}
