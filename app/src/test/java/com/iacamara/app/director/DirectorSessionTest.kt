package com.iacamara.app.director

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.Subject
import com.iacamara.app.perception.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorSessionTest {

    private val caps = DirectorCaps(
        front = false,
        mode = PhotoMode.FOTO,
        availableModes = listOf(PhotoMode.FOTO, PhotoMode.AUTO, PhotoMode.RETRATO),
        zoomRatio = 1f,
        maxZoom = 10f,
        hasOpticalTele = true,
        minEv = -2f,
        maxEv = 2f,
    )

    private fun perception(
        scene: SceneType,
        conditions: Set<Condition> = emptySet(),
        subject: Subject? = null,
        t: Long = 0L,
    ) = Perception(scene, conditions, subject, emptyList(), null, null, emptyList(), 10L, t)

    @Test
    fun exposicionCambiaSoloSiSeMantiene() {
        val session = DirectorSession()
        var last = 0f
        repeat(5) { last = session.update(perception(SceneType.NIEVE, t = it * 100L), caps).exposureEv }
        assertEquals("todavía no ha cambiado", 0f, last, 0.001f)
        repeat(3) { last = session.update(perception(SceneType.NIEVE, t = 600L + it * 100L), caps).exposureEv }
        assertEquals(0.7f, last, 0.001f)
    }

    @Test
    fun contraluzSinCaraSubeExposicion() {
        val (ev, reason) = DirectorSession.exposureFor(perception(SceneType.GENERAL, setOf(Condition.CONTRALUZ)))
        assertEquals(1f, ev, 0.001f)
        assertNotNull(reason)
    }

    @Test
    fun retratoProponeModoRetrato() {
        val face = Subject(SubjectKind.CARA, "Cara", NRect(0.4f, 0.3f, 0.6f, 0.5f), 1f)
        val tip = DirectorSession.tipFor(perception(SceneType.RETRATO, subject = face), caps)
        assertTrue(tip?.action is TipAction.SwitchMode)
    }

    @Test
    fun temblorTienePrioridad() {
        val tip = DirectorSession.tipFor(perception(SceneType.PAISAJE, setOf(Condition.TEMBLOR)), caps)
        assertTrue(tip!!.text.contains("quieto"))
    }

    @Test
    fun formatoEv() {
        assertEquals("+0,7 EV", DirectorSession.formatEv(0.7f))
        assertEquals("−1,0 EV", DirectorSession.formatEv(-1f))
    }
}
