package com.iacamara.app.composition

import com.iacamara.app.perception.BodyPose
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCompositionTest {

    /** Persona de pie centrada en x = cx, con la cabeza arriba en `top` y los pies abajo en `bottom`. */
    private fun standing(cx: Float, top: Float, bottom: Float, armsOut: Boolean = true): BodyPose {
        val xs = FloatArray(BodyPose.COUNT) { cx }
        val ys = FloatArray(BodyPose.COUNT)
        val v = FloatArray(BodyPose.COUNT) { 0.95f }
        val h = bottom - top
        fun set(i: Int, dx: Float, fy: Float) {
            xs[i] = cx + dx * h
            ys[i] = top + fy * h
        }
        set(BodyPose.NOSE, 0f, 0.06f)
        set(BodyPose.LEFT_EYE, 0.02f, 0.05f)
        set(BodyPose.RIGHT_EYE, -0.02f, 0.05f)
        set(BodyPose.LEFT_SHOULDER, 0.1f, 0.18f)
        set(BodyPose.RIGHT_SHOULDER, -0.1f, 0.18f)
        val elbow = if (armsOut) 0.2f else 0.1f
        set(BodyPose.LEFT_ELBOW, elbow, 0.33f)
        set(BodyPose.RIGHT_ELBOW, -elbow, 0.33f)
        set(BodyPose.LEFT_WRIST, elbow + 0.02f, 0.46f)
        set(BodyPose.RIGHT_WRIST, -elbow - 0.02f, 0.46f)
        set(BodyPose.LEFT_HIP, 0.06f, 0.52f)
        set(BodyPose.RIGHT_HIP, -0.06f, 0.52f)
        set(BodyPose.LEFT_KNEE, 0.06f, 0.74f)
        set(BodyPose.RIGHT_KNEE, -0.06f, 0.74f)
        set(BodyPose.LEFT_ANKLE, 0.06f, 0.95f)
        set(BodyPose.RIGHT_ANKLE, -0.06f, 0.95f)
        set(BodyPose.LEFT_FOOT, 0.08f, 1f)
        set(BodyPose.RIGHT_FOOT, -0.08f, 1f)
        // Lo que queda fuera del encuadre se ve poco.
        for (i in 0 until BodyPose.COUNT) if (ys[i] > 1f || ys[i] < 0f) v[i] = 0.1f
        return BodyPose(xs, ys, v)
    }

    private fun input(pose: BodyPose, box: NRect, scene: SceneType = SceneType.RETRATO, aesthetic: Float? = null) =
        CompositionInput(
            scene = scene,
            subjectKind = SubjectKind.PERSONA,
            subjectBox = box,
            faces = emptyList(),
            gazeX = null,
            saliency = null,
            rollDegrees = 0f,
            horizonY = null,
            interestingSky = false,
            highlightClip = 0f,
            shadowClip = 0f,
            blurRisk = false,
            front = false,
            deviceDegrees = 0,
            pose = pose,
            aesthetic = aesthetic,
        )

    @Test
    fun cortarPorLasRodillas() {
        // Bien colocada (ojos en la línea áurea), pero el borde de abajo cae justo en las rodillas.
        val pose = standing(GoldenRatio.MINOR, 0.335f, 1.234f)
        val g = CompositionEngine.evaluate(input(pose, NRect(0.30f, 0.335f, 0.46f, 1f)))
        assertTrue(g.message!!, g.message!!.contains("rodillas"))
        assertTrue(g.criteria["sin cortes"]!! < 0.5f)
    }

    @Test
    fun cortarLosPies() {
        // Bien colocada, pero con las puntas de los pies justo en el borde de abajo.
        val pose = standing(GoldenRatio.MINOR, 0.3f, 1.0f)
        val g = CompositionEngine.evaluate(input(pose, NRect(0.30f, 0.3f, 0.46f, 1f)))
        assertTrue(g.message!!, g.message!!.contains("pies"))
    }

    @Test
    fun cuerpoEnteroConAireNoSeQueja() {
        val pose = standing(0.33f, 0.15f, 0.85f)
        val g = CompositionEngine.evaluate(input(pose, NRect(0.25f, 0.15f, 0.41f, 0.85f)))
        assertTrue("puntuación ${g.score}: ${g.message}", g.message == null || !g.message!!.contains("cortes"))
    }

    @Test
    fun losOjosVanALaLineaDeLaGuia() {
        // Persona en el tercio izquierdo: con los ojos medidos, el objetivo deja los ojos en el tercio superior.
        val pose = standing(0.33f, 0.15f, 0.85f)
        val box = NRect(0.25f, 0.15f, 0.41f, 0.85f)
        val g = CompositionEngine.evaluate(input(pose, box, scene = SceneType.GENERAL))
        val eyes = (pose.ys[BodyPose.LEFT_EYE] + pose.ys[BodyPose.RIGHT_EYE]) / 2f
        val targetEyes = g.target!!.second - (box.centerY - eyes)
        val line = if (g.style == GuideStyle.AUREA) GoldenRatio.MINOR else 1f / 3f
        assertEquals(line, targetEyes, 0.01f)
    }

    @Test
    fun brazosPegadosAlCuerpo() {
        val pose = standing(0.33f, 0.15f, 0.85f, armsOut = false)
        // Encuadre algo flojo para que salga el consejo de menor prioridad.
        val g = CompositionEngine.evaluate(input(pose, NRect(0.25f, 0.15f, 0.41f, 0.85f), aesthetic = 3.4f))
        val armsTip = "separe un poco los brazos"
        val withArms = g.message?.contains(armsTip) == true
        val normal = CompositionEngine.evaluate(input(standing(0.33f, 0.15f, 0.85f), NRect(0.25f, 0.15f, 0.41f, 0.85f), aesthetic = 3.4f))
        assertTrue("mensaje: ${g.message}", withArms || g.score < Guidance.GOOD_SCORE)
        assertTrue(normal.message?.contains(armsTip) != true)
    }

    @Test
    fun laEsteticaCuentaEnLaPuntuacion() {
        val pose = standing(0.33f, 0.15f, 0.85f)
        val box = NRect(0.25f, 0.15f, 0.41f, 0.85f)
        val bonita = CompositionEngine.evaluate(input(pose, box, aesthetic = 5.2f))
        val fea = CompositionEngine.evaluate(input(pose, box, aesthetic = 3.2f))
        assertEquals(1f, bonita.criteria["estética"]!!, 0.001f)
        assertEquals(0f, fea.criteria["estética"]!!, 0.001f)
        assertTrue(bonita.score > fea.score)
        assertNull(CompositionEngine.evaluate(input(pose, box)).criteria["estética"])
    }
}
