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

    /** Cara del detector de caras (YuNet) con su confianza. */
    data class RawFace(val box: NRect, val score: Float)

    /** Etiquetas de personas del detector de vocabulario abierto (YOLOE). */
    val HUMAN_LABELS = setOf("person", "child", "baby")

    /**
     * Una cara cuenta si es muy clara, si la confirman los 478 puntos de la cara o si está en la cabeza de
     * una persona clara del detector de objetos (en una foto de grupo las caras son pequeñas para los 478
     * puntos, pero cada una está en su persona). La "cara" de un perro o un gato (dentro de un animal y de
     * ninguna persona) no cuenta sin los 478 puntos.
     *
     * @param landmarkFaces caras en las que el modelo de 478 puntos encontró una cara de verdad.
     * @param objects objetos del detector del último análisis (personas y animales).
     */
    fun faces(
        raw: List<RawFace>,
        landmarkFaces: List<NRect>,
        lowLight: Boolean,
        objects: List<DetectedObject> = emptyList(),
    ): List<NRect> {
        // Solo personas claras por sí mismas: las dudosas se confirman con las caras (no al revés).
        val persons = objects.filter { it.label == "person" && it.score >= personSure(lowLight) }.map { it.box }
        val animals = objects.filter { it.label in SceneRules.ANIMALS }.map { it.box }
        return raw.filter { f ->
            val confirmed = landmarkFaces.any { overlaps(it, f.box) }
            val inPerson = persons.any { isHeadOf(f.box, it) }
            val inAnimal = animals.any { f.box.centerX in it.left..it.right && f.box.centerY in it.top..it.bottom }
            when {
                confirmed -> !lowLight || f.score >= FACE_MIN_CONFIRMED_LOW_LIGHT
                inAnimal && !inPerson -> false
                f.score >= (if (lowLight) FACE_SURE_LOW_LIGHT else FACE_SURE) -> true
                else -> inPerson && f.score >= (if (lowLight) FACE_WITH_PERSON_LOW_LIGHT else FACE_WITH_PERSON)
            }
        }.map { it.box }
    }

    /** La cara está en la mitad de arriba de la persona y no es más ancha que ella. */
    private fun isHeadOf(face: NRect, person: NRect): Boolean =
        face.centerX in person.left..person.right &&
            face.centerY in person.top..(person.top + person.height * 0.5f) &&
            face.width <= person.width * 1.1f

    /**
     * Objetos del detector (YOLO26n o YOLO11s) con las personas dudosas quitadas.
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

    /*
     * Umbrales de YuNet, medidos con las fotos del usuario (de día y oscurecidas con ruido, como de noche):
     * caras reales claras 0,76–0,91 de día y 0,84–0,89 de noche; caras pequeñas de un grupo 0,5–0,86;
     * falsos: la cara de un perro hasta 0,74 (por eso el veto de los animales), una sombra de noche 0,50 y
     * una mano manchada de barro por debajo de 0,6.
     */

    /** Confianza del detector de caras con la que no hace falta confirmar (de día / con poca luz). */
    const val FACE_SURE = 0.75f
    const val FACE_SURE_LOW_LIGHT = 0.8f

    /** Con poca luz, una cara confirmada por los 478 puntos necesita al menos esta confianza. */
    const val FACE_MIN_CONFIRMED_LOW_LIGHT = 0.6f

    /** Confianza que basta a una cara en la cabeza de una persona clara (de día / con poca luz). */
    const val FACE_WITH_PERSON = 0.55f
    const val FACE_WITH_PERSON_LOW_LIGHT = 0.65f

    /** Confianza del detector de objetos con la que una persona no necesita confirmación. */
    const val PERSON_SURE = 0.5f
    const val PERSON_SURE_LOW_LIGHT = 0.75f
}
