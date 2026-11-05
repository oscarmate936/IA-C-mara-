package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectTrackerTest {

    private val box = NRect(0.4f, 0.4f, 0.6f, 0.7f)

    @Test
    fun jirafaDudosaSeDescarta() {
        // Lo que pasó en el S22+: una estructura metálica detectada como jirafa con poca seguridad.
        assertFalse(DetectionFilter.accept(DetectedObject("giraffe", box, 0.5f)))
        assertTrue(DetectionFilter.accept(DetectedObject("giraffe", box, 0.8f)))
        assertTrue(DetectionFilter.accept(DetectedObject("person", box, 0.45f)))
        assertTrue(DetectionFilter.accept(DetectedObject("cow", box, 0.6f)))
    }

    @Test
    fun unFalloDeUnFotogramaNoCuenta() {
        val tracker = ObjectTracker(confirmHits = 3)
        assertEquals(0, tracker.update(listOf(DetectedObject("dog", box, 0.9f))).size)
        assertEquals(0, tracker.update(emptyList()).size)
        assertEquals(0, tracker.update(emptyList()).size)
        assertEquals(0, tracker.update(emptyList()).size)
    }

    @Test
    fun objetoRepetidoSeConfirma() {
        val tracker = ObjectTracker(confirmHits = 3)
        tracker.update(listOf(DetectedObject("dog", box, 0.9f)))
        tracker.update(listOf(DetectedObject("dog", NRect(0.41f, 0.4f, 0.61f, 0.7f), 0.9f)))
        val confirmed = tracker.update(listOf(DetectedObject("dog", NRect(0.42f, 0.41f, 0.62f, 0.71f), 0.9f)))
        assertEquals(1, confirmed.size)
        assertEquals("dog", confirmed.first().label)
    }

    @Test
    fun aguantaUnFotogramaPerdido() {
        val tracker = ObjectTracker(confirmHits = 2, maxMisses = 2)
        tracker.update(listOf(DetectedObject("cat", box, 0.9f)))
        tracker.update(listOf(DetectedObject("cat", box, 0.9f)))
        assertEquals(1, tracker.update(emptyList()).size)
        assertEquals(0, tracker.update(emptyList()).size)
    }

    @Test
    fun iou() {
        assertEquals(1f, box.iou(box), 1e-5f)
        assertEquals(0f, box.iou(NRect(0f, 0f, 0.1f, 0.1f)), 1e-5f)
    }
}
