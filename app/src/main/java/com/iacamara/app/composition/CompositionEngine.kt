package com.iacamara.app.composition

import com.iacamara.app.perception.DetectedObject
import com.iacamara.app.perception.FrameMath
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SaliencyMap
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.SubjectKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Todo lo que el motor de composición necesita, en coordenadas del encuadre vertical de la
 * pantalla (P, sin espejo), como el resto de la percepción.
 */
data class CompositionInput(
    val scene: SceneType,
    val subjectKind: SubjectKind?,
    val subjectBox: NRect?,
    val faces: List<NRect>,
    /** Hacia dónde mira la cara principal, visto por el usuario: −1 izquierda … +1 derecha. */
    val gazeX: Float?,
    val saliency: SaliencyMap?,
    /** Desviación del horizonte en grados (sensor de gravedad); null con el móvil plano. */
    val rollDegrees: Float?,
    /** Altura estimada del horizonte en la imagen (0 arriba … 1 abajo, vista del usuario). */
    val horizonY: Float?,
    /** El cielo es interesante (azul o de atardecer): conviene darle más espacio. */
    val interestingSky: Boolean,
    val highlightClip: Float,
    val shadowClip: Float,
    val blurRisk: Boolean,
    val front: Boolean,
    /** Orientación del móvil: 0, 90, 180 o 270. */
    val deviceDegrees: Int,
    /** Objetos con nombre (vocabulario abierto) para explicar qué distrae. */
    val namedObjects: List<DetectedObject> = emptyList(),
)

enum class GuideStyle { TERCIOS, CENTRO }

/** Dirección en la que mover el móvil, desde el punto de vista del usuario. */
enum class Move(val text: String) {
    IZQUIERDA("Gira un poco a la izquierda"),
    DERECHA("Gira un poco a la derecha"),
    ARRIBA("Inclina un poco hacia arriba"),
    ABAJO("Inclina un poco hacia abajo"),
}

data class Guidance(
    /** Puntuación del encuadre, 0–100. */
    val score: Int,
    /** Centro del sujeto y punto donde debería estar (coordenadas P, para dibujar). */
    val subjectCenter: Pair<Float, Float>?,
    val target: Pair<Float, Float>?,
    /** Línea objetivo del horizonte en coordenadas P (dos extremos), si aplica. */
    val horizonTarget: Pair<Pair<Float, Float>, Pair<Float, Float>>?,
    val move: Move?,
    /** El consejo más útil para mejorar el encuadre, o null si ya está bien. */
    val message: String?,
    val style: GuideStyle,
    /** Detalle de cada criterio (0–1), para depurar y para el aprendizaje futuro. */
    val criteria: Map<String, Float>,
) {
    val isGood: Boolean get() = score >= GOOD_SCORE

    companion object {
        const val GOOD_SCORE = 80
    }
}

/**
 * Coach de composición: decide dónde debería estar el sujeto (tercios, espacio hacia donde mira,
 * aire sobre la cabeza, horizonte), puntúa el encuadre y dice cómo mejorarlo, como lo haría un
 * fotógrafo con experiencia.
 */
object CompositionEngine {

    private const val THIRD = 1f / 3f

    fun evaluate(input: CompositionInput): Guidance {
        val toView = { x: Float, y: Float -> toView(x, y, input) }
        val toScreen = { x: Float, y: Float -> fromView(x, y, input) }

        val criteria = linkedMapOf<String, Float>()
        val problems = mutableListOf<Problem>()

        // 1) Horizonte recto.
        input.rollDegrees?.let { roll ->
            val c = 1f - min(1f, abs(roll) / 5f)
            criteria["nivel"] = c
            if (abs(roll) >= 1.5f) {
                // roll > 0: el móvil está girado a la izquierda (antihorario); se corrige girando a la derecha.
                val side = if (roll > 0) "derecha" else "izquierda"
                problems += Problem(c, 1.2f, "Endereza el móvil: gíralo ${"%.0f".format(abs(roll))}° hacia la $side")
            }
        }

        // 2) Exposición y nitidez.
        val exposure = 1f - min(1f, input.highlightClip * 5f + input.shadowClip * 2f)
        criteria["exposición"] = exposure
        if (input.highlightClip > 0.08f) problems += Problem(exposure, 0.8f, "Hay luces quemadas: toca la zona más clara para bajar la exposición")
        val sharp = if (input.blurRisk) 0.4f else 1f
        criteria["nitidez"] = sharp
        if (input.blurRisk) problems += Problem(sharp, 1.1f, "Mantén el móvil quieto para que la foto salga nítida")

        // 3) Sujeto: posición, aire, cortes.
        var target: Pair<Float, Float>? = null
        var subjectCenterView: Pair<Float, Float>? = null
        var move: Move? = null
        var style = GuideStyle.TERCIOS
        val box = input.subjectBox?.let { toViewRect(it, input) }
        if (box != null) {
            subjectCenterView = box.centerX to box.centerY
            val placement = subjectTarget(box, input)
            style = placement.style
            target = placement.target
            val distance = hypot(placement.target.first - box.centerX, placement.target.second - box.centerY)
            val c = 1f - min(1f, distance / 0.25f)
            criteria["posición"] = c
            if (distance > 0.07f) {
                move = moveFor(placement.target.first - box.centerX, placement.target.second - box.centerY)
                problems += Problem(c, 1.0f, placement.reason ?: move.text)
            }
            // Aire sobre la cabeza en retratos.
            if (input.subjectKind == SubjectKind.CARA || input.subjectKind == SubjectKind.PERSONA) {
                val head = headroom(box, input.subjectKind)
                criteria["aire"] = head.first
                head.second?.let { problems += Problem(head.first, 0.9f, it) }
            }
            // Sujeto cortado por el borde.
            val cut = cutPenalty(box)
            criteria["sin cortes"] = 1f - cut
            if (cut > 0.3f) problems += Problem(1f - cut, 1.0f, "Estás cortando al sujeto: aléjate un poco o haz zoom hacia fuera")
            // Sujeto demasiado pequeño.
            if (input.subjectKind == SubjectKind.CARA && box.area < 0.006f) {
                problems += Problem(0.5f, 0.7f, "Acércate: la cara ocupa muy poco en la foto")
            }
        }

        // 4) Paisaje: horizonte en un tercio.
        val horizonTargetView: Float? = if (box == null && input.horizonY != null) {
            if (input.interestingSky) 2 * THIRD else THIRD
        } else {
            null
        }
        if (horizonTargetView != null && input.horizonY != null) {
            val d = abs(input.horizonY - horizonTargetView)
            val c = 1f - min(1f, d / 0.2f)
            criteria["horizonte"] = c
            if (d > 0.08f) {
                val text = if (input.horizonY < horizonTargetView) {
                    if (input.interestingSky) "El cielo es bonito: inclina hacia arriba para darle más espacio"
                    else "Inclina hacia arriba: pon el horizonte en la línea del tercio superior"
                } else {
                    if (input.interestingSky) "Inclina hacia abajo: pon el horizonte en la línea del tercio inferior"
                    else "El cielo no aporta mucho: inclina hacia abajo para mostrar más tierra"
                }
                // Subir el horizonte en la imagen = inclinar hacia abajo, y al revés.
                move = if (input.horizonY < horizonTargetView) Move.ARRIBA else Move.ABAJO
                problems += Problem(c, 0.9f, text)
            }
            if (abs(input.horizonY - 0.5f) < 0.06f) {
                problems += Problem(0.4f, 0.8f, "El horizonte corta la foto por la mitad: súbelo o bájalo a un tercio")
            }
        }

        // 5) Elementos que distraen en los bordes (saliencia fuera del sujeto).
        input.saliency?.let { map ->
            val edges = edgeDistraction(map, input, box)
            if (edges != null) {
                val c = 1f - min(1f, edges.mass / 0.25f)
                criteria["bordes limpios"] = c
                if (edges.mass > 0.08f) {
                    val name = namedInBorder(edges.sideIndex, input, box)
                    val what = if (name != null) "«$name»" else "Algo"
                    problems += Problem(c, 0.85f, "$what distrae en el borde ${edges.side}: encuadra para dejarlo fuera")
                }
            }
        }

        val score = weightedScore(criteria, input.scene, box != null)
        val worst = problems.minByOrNull { it.value / it.weight }
        val message = if (score >= Guidance.GOOD_SCORE && (worst == null || worst.value > 0.6f)) null else worst?.text

        val horizonLine = horizonTargetView?.let { y -> toScreen(0f, y) to toScreen(1f, y) }
        return Guidance(
            score = score,
            subjectCenter = subjectCenterView?.let { toScreen(it.first, it.second) },
            target = target?.let { toScreen(it.first, it.second) },
            horizonTarget = horizonLine,
            move = move,
            message = message,
            style = style,
            criteria = criteria,
        )
    }

    private data class Problem(val value: Float, val weight: Float, val text: String)

    private data class Placement(val target: Pair<Float, Float>, val style: GuideStyle, val reason: String?)

    /** Dónde debería estar el sujeto según el tipo de foto. */
    private fun subjectTarget(box: NRect, input: CompositionInput): Placement {
        val cx = box.centerX
        val cy = box.centerY
        val big = box.width > 0.55f || box.area > 0.3f
        return when (input.subjectKind) {
            SubjectKind.CARA, SubjectKind.PERSONA -> {
                // Primer plano muy cerrado o selfie: centrado está bien.
                if (big || input.scene == SceneType.SELFIE) {
                    return Placement(0.5f to targetEyesY(box, input.subjectKind), GuideStyle.CENTRO, null)
                }
                val gaze = input.gazeX
                val x = when {
                    // Espacio hacia donde mira: si mira a la derecha, el sujeto va a la izquierda.
                    gaze != null && gaze > 0.15f -> THIRD
                    gaze != null && gaze < -0.15f -> 2 * THIRD
                    else -> if (cx < 0.5f) THIRD else 2 * THIRD
                }
                val reason = when {
                    gaze != null && abs(gaze) > 0.15f && abs(x - cx) > 0.07f ->
                        "Deja espacio hacia donde mira: ${moveFor(x - cx, 0f).text.lowercase()}"
                    else -> null
                }
                Placement(x to targetEyesY(box, input.subjectKind), GuideStyle.TERCIOS, reason)
            }
            SubjectKind.COMIDA -> {
                if (big) Placement(0.5f to 0.5f, GuideStyle.CENTRO, null)
                else Placement(nearestThird(cx) to nearestThird(cy), GuideStyle.TERCIOS, null)
            }
            else -> {
                if (big) Placement(0.5f to 0.5f, GuideStyle.CENTRO, null)
                else Placement(nearestThird(cx) to nearestThird(cy), GuideStyle.TERCIOS, null)
            }
        }
    }

    /** Altura objetivo del centro del sujeto para que los ojos queden en el tercio superior. */
    private fun targetEyesY(box: NRect, kind: SubjectKind?): Float = when (kind) {
        // Los ojos están algo por encima del centro de la cara.
        SubjectKind.CARA -> THIRD + box.height * 0.1f
        // Persona entera o medio cuerpo: la cabeza cerca del tercio superior.
        else -> THIRD + box.height * 0.4f
    }.coerceIn(0.2f, 0.7f)

    private fun nearestThird(v: Float): Float = if (v < 0.5f) THIRD else 2 * THIRD

    private fun headroom(box: NRect, kind: SubjectKind?): Pair<Float, String?> {
        // Para una cara, la parte de arriba de la cabeza está un poco por encima de la caja.
        val headTop = if (kind == SubjectKind.CARA) box.top - box.height * 0.25f else box.top
        return when {
            headTop < 0.02f -> 0.3f to "No cortes la cabeza: deja un poco de aire arriba"
            headTop > 0.4f && kind == SubjectKind.PERSONA -> 0.6f to "Demasiado espacio sobre la cabeza: inclina un poco hacia abajo"
            else -> 1f to null
        }
    }

    /** 0 = el sujeto no toca los bordes, 1 = está muy cortado. Un sujeto enorme a propósito no penaliza. */
    private fun cutPenalty(box: NRect): Float {
        if (box.width > 0.85f && box.height > 0.85f) return 0f
        val margin = 0.01f
        var touching = 0
        if (box.left < margin) touching++
        if (box.right > 1f - margin) touching++
        if (box.top < margin) touching++
        if (box.bottom > 1f - margin) touching++
        return min(1f, touching * 0.35f)
    }

    private data class EdgeDistraction(val mass: Float, val side: String, val sideIndex: Int)

    /** Nombre del objeto (vocabulario abierto) que está en ese borde, si se conoce. */
    private fun namedInBorder(sideIndex: Int, input: CompositionInput, subject: NRect?): String? {
        val band = 0.15f
        return input.namedObjects
            .filter { it.score >= 0.3f }
            .map { it to toViewRect(it.box, input) }
            .filter { (_, r) -> subject == null || r.iou(subject) < 0.3f }
            .filter { (_, r) ->
                when (sideIndex) {
                    0 -> r.left < band
                    1 -> r.right > 1f - band
                    2 -> r.top < band
                    else -> r.bottom > 1f - band
                }
            }
            .maxByOrNull { (o, _) -> o.score }
            ?.first?.label
    }

    /** Cuánta "atención" se va a los bordes fuera del sujeto, y en qué borde. */
    private fun edgeDistraction(map: SaliencyMap, input: CompositionInput, subject: NRect?): EdgeDistraction? {
        var total = 0f
        val sides = FloatArray(4) // izquierda, derecha, arriba, abajo (vista del usuario)
        val band = 0.12f
        for (j in 0 until map.height) {
            for (i in 0 until map.width) {
                val v = map.value(i, j)
                if (v <= 0.3f) continue
                total += v
                val (x, y) = toView((i + 0.5f) / map.width, (j + 0.5f) / map.height, input)
                if (subject != null && x in subject.left..subject.right && y in subject.top..subject.bottom) continue
                if (x < band) sides[0] += v
                if (x > 1f - band) sides[1] += v
                if (y < band) sides[2] += v
                if (y > 1f - band) sides[3] += v
            }
        }
        if (total <= 0f) return null
        val index = sides.indices.maxByOrNull { sides[it] } ?: return null
        val name = listOf("izquierdo", "derecho", "de arriba", "de abajo")[index]
        return EdgeDistraction(sides[index] / total, name, index)
    }

    private fun weightedScore(criteria: Map<String, Float>, scene: SceneType, hasSubject: Boolean): Int {
        val weights: Map<String, Float> = when {
            scene == SceneType.RETRATO || scene == SceneType.SELFIE || scene == SceneType.GRUPO -> mapOf(
                "posición" to 0.25f, "aire" to 0.15f, "sin cortes" to 0.15f, "nivel" to 0.1f,
                "bordes limpios" to 0.15f, "exposición" to 0.1f, "nitidez" to 0.1f,
            )
            !hasSubject -> mapOf(
                "horizonte" to 0.25f, "nivel" to 0.2f, "bordes limpios" to 0.2f, "exposición" to 0.2f, "nitidez" to 0.15f,
            )
            else -> mapOf(
                "posición" to 0.3f, "sin cortes" to 0.15f, "nivel" to 0.15f, "bordes limpios" to 0.15f,
                "exposición" to 0.15f, "nitidez" to 0.1f,
            )
        }
        var sum = 0f
        var weightSum = 0f
        for ((name, weight) in weights) {
            val value = criteria[name] ?: continue
            sum += value * weight
            weightSum += weight
        }
        if (weightSum <= 0f) return 70
        return (sum / weightSum * 100f).toInt().coerceIn(0, 100)
    }

    /** Para llevar el sujeto hacia el objetivo hay que girar el móvil en sentido contrario. */
    private fun moveFor(dx: Float, dy: Float): Move =
        if (abs(dx) >= abs(dy)) {
            if (dx < 0) Move.DERECHA else Move.IZQUIERDA
        } else {
            if (dy < 0) Move.ABAJO else Move.ARRIBA
        }

    // --- Coordenadas: P (pantalla vertical, sin espejo) ↔ V (lo que ve el usuario, derecho) ---

    fun toView(x: Float, y: Float, input: CompositionInput): Pair<Float, Float> {
        val dx = if (input.front) 1f - x else x
        return FrameMath.rotate(dx, y, input.deviceDegrees)
    }

    fun fromView(x: Float, y: Float, input: CompositionInput): Pair<Float, Float> {
        val (dx, dy) = FrameMath.unrotate(x, y, input.deviceDegrees)
        return (if (input.front) 1f - dx else dx) to dy
    }

    private fun toViewRect(r: NRect, input: CompositionInput): NRect {
        val (x1, y1) = toView(r.left, r.top, input)
        val (x2, y2) = toView(r.right, r.bottom, input)
        return NRect.of(x1, y1, x2, y2)
    }
}
