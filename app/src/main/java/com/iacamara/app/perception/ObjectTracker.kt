package com.iacamara.app.perception

/**
 * Filtro de confianza por tipo de objeto. El detector solo conoce 80 clases (COCO) y,
 * ante algo que no conoce, elige la más parecida (p. ej. una estructura metálica → "jirafa").
 * Por eso a las clases improbables se les exige mucha más seguridad.
 */
object DetectionFilter {
    private val UNLIKELY = setOf("giraffe", "zebra", "elephant", "bear")
    private val RURAL = setOf("cow", "horse", "sheep")

    fun minScore(label: String): Float = when (label) {
        in UNLIKELY -> 0.70f
        in RURAL -> 0.55f
        "person" -> 0.40f
        else -> 0.45f
    }

    fun accept(d: DetectedObject): Boolean = d.score >= minScore(d.label)
}

/**
 * Seguimiento de objetos entre fotogramas: un objeto solo se da por bueno cuando aparece
 * varias veces seguidas en el mismo sitio. Así un error de un solo fotograma no se muestra
 * ni mueve el enfoque.
 */
class ObjectTracker(
    private val confirmHits: Int = 3,
    private val maxMisses: Int = 2,
) {
    private class Track(val label: String, var box: NRect, var score: Float, var hits: Int, var misses: Int)

    private val tracks = mutableListOf<Track>()

    fun update(detections: List<DetectedObject>): List<DetectedObject> {
        val unmatched = tracks.toMutableSet()
        for (d in detections.sortedByDescending { it.score }) {
            val match = unmatched
                .filter { it.label == d.label }
                .maxByOrNull { it.box.iou(d.box) }
                ?.takeIf { it.box.iou(d.box) > MATCH_IOU }
            if (match != null) {
                match.box = smooth(match.box, d.box)
                match.score = d.score
                match.hits++
                match.misses = 0
                unmatched -= match
            } else {
                tracks += Track(d.label, d.box, d.score, hits = 1, misses = 0)
            }
        }
        for (t in unmatched) t.misses++
        tracks.removeAll { it.misses > maxMisses }
        return tracks
            .filter { it.hits >= confirmHits && it.misses <= 1 }
            .map { DetectedObject(it.label, it.box, it.score) }
    }

    fun clear() = tracks.clear()

    private fun smooth(old: NRect, new: NRect): NRect {
        val a = 0.6f
        return NRect(
            old.left + a * (new.left - old.left),
            old.top + a * (new.top - old.top),
            old.right + a * (new.right - old.right),
            old.bottom + a * (new.bottom - old.bottom),
        )
    }

    private companion object {
        const val MATCH_IOU = 0.3f
    }
}
