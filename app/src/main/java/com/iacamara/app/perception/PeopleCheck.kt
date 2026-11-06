package com.iacamara.app.perception

import kotlin.math.max
import kotlin.math.min

/**
 * Confirmación cruzada de caras y personas. Un solo modelo se equivoca a veces, sobre todo de noche:
 * con poca luz y mucho ruido, unos pantalones tendidos parecen una persona y una sombra parece una cara.
 * Una cara o una persona solo cuenta si la ve con mucha seguridad un modelo, o si otro modelo distinto
 * la confirma (los 478 puntos de la cara, o los puntos del cuerpo). Con poca luz se exige más seguridad.
 */
object PeopleCheck {

    /** Cara del detector rápido (BlazeFace) con su confianza. */
    data class RawFace(val box: NRect, val score: Float)

    /** Etiquetas de personas del detector de vocabulario abierto (YOLOE). */
    val HUMAN_LABELS = setOf("person", "child", "baby")

    /**
     * @param landmarkFaces caras en las que el modelo de 478 puntos encontró una cara de verdad.
     */
    fun faces(raw: List<RawFace>, landmarkFaces: List<NRect>, lowLight: Boolean): List<NRect> =
        raw.filter { f ->
            val sure = if (lowLight) FACE_SURE_LOW_LIGHT else FACE_SURE
            val confirmed = landmarkFaces.any { overlaps(it, f.box) }
            f.score >= sure || (confirmed && (!lowLight || f.score >= FACE_MIN_CONFIRMED_LOW_LIGHT))
        }.map { it.box }

    /**
     * Objetos del detector (YOLO11) con las personas dudosas quitadas.
     * @param faces caras ya confirmadas; @param pose cuerpo de la persona principal, si se detectó.
     */
    fun people(objects: List<DetectedObject>, faces: List<NRect>, pose: BodyPose?, lowLight: Boolean): List<DetectedObject> {
        val body = pose?.let { bodyBox(it) }
        return objects.filter { o ->
            o.label != "person" || o.score >= personSure(lowLight) || corroborated(o.box, faces, body)
        }
    }

    /**
     * Objetos con nombre (YOLOE): una "persona" solo cuenta si coincide con una persona o una cara
     * confirmadas, o si es muy segura.
     */
    fun named(
        named: List<DetectedObject>,
        people: List<DetectedObject>,
        faces: List<NRect>,
        pose: BodyPose?,
        lowLight: Boolean,
    ): List<DetectedObject> {
        val body = pose?.let { bodyBox(it) }
        val persons = people.filter { it.label == "person" }.map { it.box }
        return named.filter { o ->
            o.label !in HUMAN_LABELS || o.score >= personSure(lowLight) ||
                persons.any { it.iou(o.box) >= 0.3f } || corroborated(o.box, faces, body)
        }
    }

    /** Caja de los puntos del cuerpo bien visibles, si hay los suficientes para fiarse (hombros o caderas). */
    fun bodyBox(pose: BodyPose): NRect? {
        val visible = (0 until BodyPose.COUNT).filter { pose.visible(it, 0.5f) }
        val torso = listOf(BodyPose.LEFT_SHOULDER, BodyPose.RIGHT_SHOULDER, BodyPose.LEFT_HIP, BodyPose.RIGHT_HIP)
            .count { pose.visible(it, 0.5f) }
        if (visible.size < 6 || torso < 2) return null
        return NRect(
            visible.minOf { pose.xs[it] },
            visible.minOf { pose.ys[it] },
            visible.maxOf { pose.xs[it] },
            visible.maxOf { pose.ys[it] },
        )
    }

    private fun personSure(lowLight: Boolean) = if (lowLight) PERSON_SURE_LOW_LIGHT else PERSON_SURE

    /** La caja tiene dentro una cara confirmada, o coincide con el cuerpo detectado. */
    private fun corroborated(box: NRect, faces: List<NRect>, body: NRect?): Boolean =
        faces.any { f -> f.centerX in box.left..box.right && f.centerY in box.top..box.bottom } ||
            (body != null && (body.iou(box) >= 0.3f || (body.centerX in box.left..box.right && body.centerY in box.top..box.bottom)))

    /** Dos cajas de la misma cara: se solapan bastante o el centro de una cae dentro de la otra. */
    private fun overlaps(a: NRect, b: NRect): Boolean {
        if (a.iou(b) >= 0.2f) return true
        val inside = { r: NRect, x: Float, y: Float -> x in r.left..r.right && y in r.top..r.bottom }
        return inside(a, b.centerX, b.centerY) && min(a.area, b.area) / max(1e-6f, max(a.area, b.area)) > 0.15f
    }

    /** Confianza del detector de caras con la que no hace falta confirmar (de día / con poca luz). */
    const val FACE_SURE = 0.75f
    const val FACE_SURE_LOW_LIGHT = 0.9f

    /** Con poca luz, una cara confirmada por los 478 puntos necesita al menos esta confianza. */
    const val FACE_MIN_CONFIRMED_LOW_LIGHT = 0.6f

    /** Confianza del detector de objetos con la que una persona no necesita confirmación. */
    const val PERSON_SURE = 0.5f
    const val PERSON_SURE_LOW_LIGHT = 0.75f
}
