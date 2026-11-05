package com.iacamara.app.composition

import com.iacamara.app.perception.BodyPose
import com.iacamara.app.perception.DetectedObject
import com.iacamara.app.perception.FrameMath
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SaliencyMap
import com.iacamara.app.perception.SceneStructure
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.Segment
import com.iacamara.app.perception.SubjectKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
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
    /** Líneas guía, punto de fuga, simetría y reflejos de la escena (coordenadas P). */
    val structure: SceneStructure? = null,
    /** Guía que se usa; null = la elige la IA según la escena. */
    val style: GuideStyle? = null,
    /** Puntos del cuerpo de la persona principal (coordenadas P). */
    val pose: BodyPose? = null,
    /** Nota de estética 1–10 (NIMA). */
    val aesthetic: Float? = null,
)

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
    /** Trazos de la guía elegida (coordenadas P): rejilla, espiral, eje, líneas guía... */
    val shapes: List<GuideShape> = emptyList(),
) {
    val isGood: Boolean get() = score >= GOOD_SCORE

    companion object {
        const val GOOD_SCORE = 80
    }
}

/**
 * Coach de composición: elige la guía que mejor encaja con la escena (tercios, proporción áurea,
 * espiral áurea, centro, simetría o líneas guía), decide dónde debería estar el sujeto, puntúa el
 * encuadre y dice cómo mejorarlo, como lo haría un fotógrafo con experiencia.
 */
object CompositionEngine {

    private const val THIRD = 1f / 3f

    /** La guía que elegiría la IA para esta escena. */
    fun chooseStyle(input: CompositionInput): GuideStyle =
        autoStyle(input, input.subjectBox?.let { toViewRect(it, input) }, viewStructure(input))

    fun evaluate(input: CompositionInput): Guidance {
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

        // 3) Guía: la elegida por el usuario o la que mejor encaja con la escena.
        val box = input.subjectBox?.let { toViewRect(it, input) }
        val structure = viewStructure(input)
        val pose = input.pose?.map { x, y -> toView(x, y, input) }
        val style = input.style ?: autoStyle(input, box, structure)
        val portrait = input.deviceDegrees % 180 == 0
        val shapes = mutableListOf<GuideShape>()
        when (style) {
            GuideStyle.TERCIOS -> shapes += grid(THIRD, 2 * THIRD)
            GuideStyle.AUREA -> shapes += grid(GoldenRatio.MINOR, GoldenRatio.MAJOR)
            GuideStyle.CENTRO -> shapes += GuideShape(ShapeKind.CENTER, listOf(0.5f to 0.5f))
            else -> Unit
        }

        // 4) Sujeto: posición según la guía, aire, cortes.
        var target: Pair<Float, Float>? = null
        var subjectCenterView: Pair<Float, Float>? = null
        var move: Move? = null
        if (box != null) {
            subjectCenterView = box.centerX to box.centerY
            val placement = subjectTarget(box, input, style, portrait, pose?.let { eyesY(it, box) })
            target = placement.target
            placement.spiral?.let { shapes += GuideShape(ShapeKind.SPIRAL, it) }
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
            // Con los puntos del cuerpo: no cortar por las articulaciones (rodillas, tobillos, codos, manos).
            val people = input.subjectKind == SubjectKind.CARA || input.subjectKind == SubjectKind.PERSONA
            if (people && pose != null) {
                jointCut(pose)?.let { (value, text) ->
                    criteria["sin cortes"] = min(criteria["sin cortes"] ?: 1f, value)
                    problems += Problem(value, 1.05f, text)
                }
                if (input.scene == SceneType.RETRATO && input.faces.size <= 1 && armsAgainstBody(pose)) {
                    problems += Problem(0.75f, 0.45f, "Pide que separe un poco los brazos del cuerpo: la figura queda más estilizada")
                }
            }
            // Sujeto demasiado pequeño.
            if (input.subjectKind == SubjectKind.CARA && box.area < 0.006f) {
                problems += Problem(0.5f, 0.7f, "Acércate: la cara ocupa muy poco en la foto")
            }
        } else if (style == GuideStyle.ESPIRAL) {
            // Sin sujeto: el ojo de la espiral hacia donde está lo que más llama la atención.
            val focus = input.saliency?.let { saliencyCentroid(it, input) }
            val eyeRight = (focus?.first ?: 0.6f) >= 0.5f
            val eyeBottom = (focus?.second ?: 0.4f) >= 0.5f
            shapes += GuideShape(ShapeKind.SPIRAL, GoldenRatio.spiral(portrait, eyeRight, eyeBottom))
        }

        // 5) Simetría: el eje de la escena (o el reflejo) en el centro.
        var horizonTargetView: Float? = null
        var reflectionHandled = false
        if (style == GuideStyle.SIMETRIA) {
            val axis = structure?.symmetryAxis
            val reflection = structure?.reflectionAxis
            shapes += GuideShape(ShapeKind.GRID, listOf(0.5f to 0f, 0.5f to 1f))
            if (axis != null) {
                val ax = axis.midX
                shapes += GuideShape(ShapeKind.AXIS, listOf(ax to 0f, ax to 1f))
                val d = 0.5f - ax
                val c = 1f - min(1f, abs(d) / 0.12f)
                criteria["simetría"] = c
                if (abs(d) > 0.03f) {
                    val m = moveFor(d, 0f)
                    problems += Problem(c, 1.1f, "Escena simétrica: lleva el eje al centro (${m.text.lowercase()})")
                    if (box == null) {
                        subjectCenterView = ax to 0.5f
                        target = 0.5f to 0.5f
                        move = m
                    }
                }
            } else if (reflection != null) {
                val ry = reflection.midY
                reflectionHandled = true
                shapes.clear()
                shapes += GuideShape(ShapeKind.GRID, listOf(0f to 0.5f, 1f to 0.5f))
                shapes += GuideShape(ShapeKind.AXIS, listOf(0f to ry, 1f to ry))
                horizonTargetView = 0.5f
                val d = 0.5f - ry
                val c = 1f - min(1f, abs(d) / 0.12f)
                criteria["simetría"] = c
                if (abs(d) > 0.03f) {
                    move = moveFor(0f, d)
                    problems += Problem(c, 1.1f, "Reflejo: pon la línea del agua en el centro (${move.text.lowercase()})")
                }
            }
        }

        // 6) Paisaje: horizonte en la línea que toca según la guía.
        if (box == null && input.horizonY != null && !reflectionHandled) {
            val horizonTarget = when (style) {
                GuideStyle.CENTRO -> 0.5f
                GuideStyle.AUREA -> if (input.interestingSky) GoldenRatio.MAJOR else GoldenRatio.MINOR
                else -> if (input.interestingSky) 2 * THIRD else THIRD
            }
            horizonTargetView = horizonTarget
            val d = abs(input.horizonY - horizonTarget)
            val c = 1f - min(1f, d / 0.2f)
            criteria["horizonte"] = c
            if (d > 0.08f) {
                val text = when {
                    style == GuideStyle.CENTRO -> "Composición central: pon el horizonte en el medio"
                    input.horizonY < horizonTarget ->
                        if (input.interestingSky) "El cielo es bonito: inclina hacia arriba para darle más espacio"
                        else "Inclina hacia arriba: pon el horizonte en la línea superior de la guía"
                    else ->
                        if (input.interestingSky) "Inclina hacia abajo: pon el horizonte en la línea inferior de la guía"
                        else "El cielo no aporta mucho: inclina hacia abajo para mostrar más tierra"
                }
                // Subir el horizonte en la imagen = inclinar hacia abajo, y al revés.
                if (move == null) move = if (input.horizonY < horizonTarget) Move.ARRIBA else Move.ABAJO
                problems += Problem(c, 0.9f, text)
            }
            if (style != GuideStyle.CENTRO && abs(input.horizonY - 0.5f) < 0.06f) {
                problems += Problem(0.4f, 0.8f, "El horizonte corta la foto por la mitad: súbelo o bájalo a un tercio")
            }
        }

        // 7) Líneas guía: que lleven la mirada al sujeto, o que su punto de fuga caiga en un punto fuerte.
        if (style == GuideStyle.LINEAS) {
            val lines = structure?.leadingLines.orEmpty()
            if (lines.isEmpty()) {
                shapes += grid(THIRD, 2 * THIRD)
            } else {
                lines.forEach { shapes += GuideShape(ShapeKind.LEADING_LINE, listOf(it.x1 to it.y1, it.x2 to it.y2)) }
                val vp = structure?.vanishingPoint
                vp?.let { shapes += GuideShape(ShapeKind.VANISHING_POINT, listOf(it)) }
                if (box != null) {
                    val leads = linesLeadTo(structure!!, box)
                    criteria["líneas"] = if (leads) 1f else 0.5f
                    if (!leads) problems += Problem(0.5f, 0.6f, "Usa las líneas de la escena: colócate para que lleven la mirada al sujeto")
                } else if (vp != null && vp.first in 0f..1f && vp.second in 0f..1f) {
                    val goal = vanishingTarget(vp, lines)
                    subjectCenterView = vp
                    target = goal
                    val d = hypot(goal.first - vp.first, goal.second - vp.second)
                    val c = 1f - min(1f, d / 0.25f)
                    criteria["líneas"] = c
                    if (d > 0.07f) {
                        val m = moveFor(goal.first - vp.first, goal.second - vp.second)
                        move = m
                        val where = if (goal.first == 0.5f) "al centro" else "a un punto fuerte"
                        problems += Problem(c, 1.0f, "Lleva el punto donde se juntan las líneas $where: ${m.text.lowercase()}")
                    }
                }
            }
        }

        // 8) Elementos que distraen en los bordes (saliencia fuera del sujeto).
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

        // 9) Equilibrio visual: que el "peso" de la foto no se vaya todo a un lado.
        input.saliency?.let { map ->
            val centroid = saliencyCentroid(map, input) ?: return@let
            // Un sujeto descentrado a propósito (tercios, espiral) se equilibra con el espacio libre.
            val allowed = max(0.15f, (target?.let { abs(it.first - 0.5f) } ?: 0f) + 0.06f)
            val excess = abs(centroid.first - 0.5f) - allowed
            val c = 1f - (excess / 0.2f).coerceIn(0f, 1f)
            criteria["equilibrio"] = c
            if (c < 0.6f) {
                val side = if (centroid.first < 0.5f) "izquierda" else "derecha"
                problems += Problem(c, 0.75f, "La foto pesa mucho a la $side: gira un poco hacia la $side para equilibrarla")
            }
        }

        // 10) Estética aprendida (NIMA): apoya a las reglas, sin consejo propio.
        input.aesthetic?.let { criteria["estética"] = ((it - 3.3f) / 1.2f).coerceIn(0f, 1f) }

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
            shapes = shapes.map { s -> GuideShape(s.kind, s.points.map { toScreen(it.first, it.second) }) },
        )
    }

    // ---------------------------------------------------------------- elección de la guía

    /**
     * La guía que usaría un fotógrafo para esta escena:
     * - Paisaje con líneas que se juntan dentro de la foto (camino, vías, pasillo) → líneas guía.
     * - Escena simétrica o con reflejo → simetría.
     * - Líneas que llevan al sujeto → líneas guía.
     * - Sujeto grande, primer plano o selfie → centro.
     * - Sujeto pequeño con mucho espacio libre alrededor → espiral áurea.
     * - Sujeto mediano → proporción áurea (los tercios lo pegarían demasiado al borde).
     * - Lo demás → tercios.
     */
    private fun autoStyle(input: CompositionInput, box: NRect?, structure: SceneStructure?): GuideStyle {
        val symmetryX = structure?.symmetryAxis?.midX
        val reflection = structure?.reflectionAxis != null
        val lines = structure?.leadingLines.orEmpty()
        if (box == null) {
            // Un camino o unas vías que se pierden dentro de la foto mandan sobre la simetría
            // (un camino recto también es simétrico); el tejado de una fachada, no.
            val vp = structure?.vanishingPoint
            val vanishingInside = vp != null && vp.first in 0.08f..0.92f && vp.second in 0.08f..0.92f
            return when {
                vanishingInside && lines.size >= 2 -> GuideStyle.LINEAS
                symmetryX != null || reflection -> GuideStyle.SIMETRIA
                lines.isNotEmpty() -> GuideStyle.LINEAS
                else -> GuideStyle.TERCIOS
            }
        }
        val big = box.width > 0.55f || box.area > 0.3f
        return when {
            symmetryX != null && abs(box.centerX - symmetryX) < 0.1f -> GuideStyle.SIMETRIA
            big || input.scene == SceneType.SELFIE -> GuideStyle.CENTRO
            lines.isNotEmpty() && linesLeadTo(structure!!, box) -> GuideStyle.LINEAS
            box.area < 0.12f && input.scene != SceneType.GRUPO && hasNegativeSpace(input, box) -> GuideStyle.ESPIRAL
            box.width > 0.3f || box.area > 0.1f -> GuideStyle.AUREA
            else -> GuideStyle.TERCIOS
        }
    }

    /** Casi todo lo que llama la atención está en el sujeto: el resto de la foto es espacio libre. */
    private fun hasNegativeSpace(input: CompositionInput, box: NRect): Boolean {
        val map = input.saliency ?: return false
        var total = 0f
        var outside = 0f
        val m = 0.05f
        for (j in 0 until map.height) for (i in 0 until map.width) {
            val v = map.value(i, j)
            if (v <= 0.25f) continue
            total += v
            val (x, y) = toView((i + 0.5f) / map.width, (j + 0.5f) / map.height, input)
            if (x < box.left - m || x > box.right + m || y < box.top - m || y > box.bottom + m) outside += v
        }
        return total > 0f && outside / total < 0.3f
    }

    /** Las líneas apuntan al sujeto: su punto de fuga está en él, o el sujeto queda al final de una línea. */
    private fun linesLeadTo(structure: SceneStructure, box: NRect): Boolean {
        val m = 0.08f
        structure.vanishingPoint?.let { (x, y) ->
            if (x in box.left - m..box.right + m && y in box.top - m..box.bottom + m) return true
        }
        val cx = box.centerX
        val cy = box.centerY
        val reach = max(box.width, box.height) / 2f + 0.04f
        return structure.leadingLines.any { s ->
            if (s.distanceToLine(cx, cy) > reach) return@any false
            // Posición del sujeto a lo largo de la línea: tiene que estar en un extremo o más allá,
            // no en el medio (una valla que pasa por detrás del sujeto no "lleva" hacia él).
            val dx = s.x2 - s.x1
            val dy = s.y2 - s.y1
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6f) return@any false
            val t = ((cx - s.x1) * dx + (cy - s.y1) * dy) / len2
            t <= 0.2f || t >= 0.8f
        }
    }

    /** Punto fuerte para el punto de fuga: el centro si las líneas llegan por los dos lados, si no un tercio. */
    private fun vanishingTarget(vp: Pair<Float, Float>, lines: List<Segment>): Pair<Float, Float> {
        val left = lines.any { it.midX < vp.first - 0.05f }
        val right = lines.any { it.midX > vp.first + 0.05f }
        val x = if (left && right && abs(vp.first - 0.5f) < 0.2f) 0.5f else nearestThird(vp.first)
        return x to nearestThird(vp.second)
    }

    // ---------------------------------------------------------------- posición del sujeto

    private data class Placement(
        val target: Pair<Float, Float>,
        val reason: String?,
        val spiral: List<Pair<Float, Float>>? = null,
    )

    /** Dónde debería estar el sujeto según la guía y el tipo de foto. */
    private fun subjectTarget(
        box: NRect,
        input: CompositionInput,
        style: GuideStyle,
        portrait: Boolean,
        eyesY: Float?,
    ): Placement {
        val cx = box.centerX
        val cy = box.centerY
        val kind = input.subjectKind
        // Con los ojos medidos (pose), se colocan exactamente en la línea; si no, se estiman con la caja.
        fun eyesAt(line: Float): Float =
            if (eyesY != null) (line + (cy - eyesY)).coerceIn(0.15f, 0.85f) else targetEyesY(line, box, kind)
        val people = kind == SubjectKind.CARA || kind == SubjectKind.PERSONA
        val gaze = input.gazeX?.takeIf { people }
        // Espacio hacia donde mira: si mira a la derecha, el sujeto va a la izquierda.
        val onLeft = when {
            gaze != null && gaze > 0.15f -> true
            gaze != null && gaze < -0.15f -> false
            else -> cx < 0.5f
        }
        var spiral: List<Pair<Float, Float>>? = null
        val target: Pair<Float, Float> = when (style) {
            GuideStyle.CENTRO -> 0.5f to (if (people) eyesAt(THIRD) else 0.5f)
            GuideStyle.SIMETRIA -> 0.5f to (if (people) eyesAt(THIRD) else cy)
            GuideStyle.AUREA -> {
                val x = if (onLeft) GoldenRatio.MINOR else GoldenRatio.MAJOR
                val y = if (people) eyesAt(GoldenRatio.MINOR)
                else if (cy < 0.5f) GoldenRatio.MINOR else GoldenRatio.MAJOR
                x to y
            }
            GuideStyle.ESPIRAL -> {
                // Las personas, con la cabeza arriba; el resto, en el ojo más cercano.
                val eyeBottom = !people && cy >= 0.5f
                val (ex, ey) = GoldenRatio.eye(eyeRight = !onLeft, eyeBottom = eyeBottom)
                spiral = GoldenRatio.spiral(portrait, eyeRight = !onLeft, eyeBottom = eyeBottom)
                ex to (if (people) eyesAt(ey) else ey)
            }
            GuideStyle.TERCIOS, GuideStyle.LINEAS -> {
                val x = if (onLeft) THIRD else 2 * THIRD
                x to (if (people) eyesAt(THIRD) else nearestThird(cy))
            }
        }
        val reason = when {
            gaze != null && abs(gaze) > 0.15f && abs(target.first - cx) > 0.07f ->
                "Deja espacio hacia donde mira: ${moveFor(target.first - cx, 0f).text.lowercase()}"
            style == GuideStyle.ESPIRAL && hypot(target.first - cx, target.second - cy) > 0.07f ->
                "Espiral áurea: lleva el sujeto al centro de la espiral (${moveFor(target.first - cx, target.second - cy).text.lowercase()})"
            else -> null
        }
        return Placement(target, reason, spiral)
    }

    /** Altura objetivo del centro del sujeto para que los ojos queden en la línea `line`. */
    private fun targetEyesY(line: Float, box: NRect, kind: SubjectKind?): Float = when (kind) {
        // Los ojos están algo por encima del centro de la cara.
        SubjectKind.CARA -> line + box.height * 0.1f
        // Persona entera o medio cuerpo: la cabeza cerca de la línea.
        else -> line + box.height * 0.4f
    }.coerceIn(0.2f, 0.7f)

    private fun nearestThird(v: Float): Float = if (v < 0.5f) THIRD else 2 * THIRD

    // ---------------------------------------------------------------- cuerpo (pose)

    /** Altura de los ojos (vista del usuario) si se ven y son de la persona del sujeto. */
    private fun eyesY(p: BodyPose, box: NRect): Float? {
        if (!p.visible(BodyPose.LEFT_EYE) || !p.visible(BodyPose.RIGHT_EYE)) return null
        val x = (p.xs[BodyPose.LEFT_EYE] + p.xs[BodyPose.RIGHT_EYE]) / 2f
        val y = (p.ys[BodyPose.LEFT_EYE] + p.ys[BodyPose.RIGHT_EYE]) / 2f
        val m = 0.05f
        return if (x in box.left - m..box.right + m && y in box.top - m..box.bottom + m) y else null
    }

    /**
     * El borde de la foto pasa justo por una articulación (rodillas, tobillos, codos o muñecas): es uno
     * de los cortes que más afean un retrato. Devuelve (valor 0–1, consejo).
     */
    private fun jointCut(p: BodyPose): Pair<Float, String>? {
        val hips = p.visible(BodyPose.LEFT_HIP) || p.visible(BodyPose.RIGHT_HIP)
        if (hips) {
            for (k in intArrayOf(BodyPose.LEFT_KNEE, BodyPose.RIGHT_KNEE)) {
                if (abs(p.ys[k] - 1f) <= 0.04f) return 0.35f to "No cortes por las rodillas: aléjate un poco o encuadra a medio muslo"
            }
        }
        for ((ankle, foot) in listOf(BodyPose.LEFT_ANKLE to BodyPose.LEFT_FOOT, BodyPose.RIGHT_ANKLE to BodyPose.RIGHT_FOOT)) {
            if (p.visible(ankle) && p.ys[ankle] < 0.98f && p.ys[foot] > 0.99f) {
                return 0.5f to "No cortes los pies: deja un poco de espacio abajo"
            }
        }
        val knees = p.visible(BodyPose.LEFT_KNEE, 0.4f) || p.visible(BodyPose.RIGHT_KNEE, 0.4f)
        if (knees) {
            for (a in intArrayOf(BodyPose.LEFT_ANKLE, BodyPose.RIGHT_ANKLE)) {
                if (abs(p.ys[a] - 1f) <= 0.03f) return 0.4f to "No cortes por los tobillos: incluye los pies o encuadra por encima de las rodillas"
            }
        }
        if (p.visible(BodyPose.LEFT_SHOULDER) || p.visible(BodyPose.RIGHT_SHOULDER)) {
            for (j in intArrayOf(BodyPose.LEFT_ELBOW, BodyPose.RIGHT_ELBOW, BodyPose.LEFT_WRIST, BodyPose.RIGHT_WRIST)) {
                val x = p.xs[j]
                val atSide = abs(x) <= 0.03f || abs(x - 1f) <= 0.03f
                if (atSide && p.ys[j] in 0f..1f) {
                    val what = if (j == BodyPose.LEFT_ELBOW || j == BodyPose.RIGHT_ELBOW) "el codo" else "la mano"
                    return 0.5f to "No cortes $what en el borde: incluye el brazo entero o déjalo fuera"
                }
            }
        }
        return null
    }

    /** Los dos brazos caen pegados al cuerpo (consejo clásico de pose: separarlos un poco). */
    private fun armsAgainstBody(p: BodyPose): Boolean {
        val needed = intArrayOf(
            BodyPose.LEFT_SHOULDER, BodyPose.RIGHT_SHOULDER, BodyPose.LEFT_ELBOW, BodyPose.RIGHT_ELBOW,
            BodyPose.LEFT_HIP, BodyPose.RIGHT_HIP,
        )
        if (needed.any { !p.visible(it, 0.6f) }) return false
        val shoulders = abs(p.xs[BodyPose.LEFT_SHOULDER] - p.xs[BodyPose.RIGHT_SHOULDER])
        if (shoulders < 0.05f) return false // de perfil
        val torso = intArrayOf(BodyPose.LEFT_SHOULDER, BodyPose.RIGHT_SHOULDER, BodyPose.LEFT_HIP, BodyPose.RIGHT_HIP)
        val left = torso.minOf { p.xs[it] }
        val right = torso.maxOf { p.xs[it] }
        val shoulderY = min(p.ys[BodyPose.LEFT_SHOULDER], p.ys[BodyPose.RIGHT_SHOULDER])
        return intArrayOf(BodyPose.LEFT_ELBOW, BodyPose.RIGHT_ELBOW).all { e ->
            val x = p.xs[e]
            val armDown = p.ys[e] > shoulderY + 0.3f * shoulders
            armDown && x > left - 0.1f * shoulders && x < right + 0.1f * shoulders
        }
    }

    private fun grid(a: Float, b: Float): List<GuideShape> = listOf(
        GuideShape(ShapeKind.GRID, listOf(a to 0f, a to 1f)),
        GuideShape(ShapeKind.GRID, listOf(b to 0f, b to 1f)),
        GuideShape(ShapeKind.GRID, listOf(0f to a, 1f to a)),
        GuideShape(ShapeKind.GRID, listOf(0f to b, 1f to b)),
    )

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

    // ---------------------------------------------------------------- saliencia

    /** Centro de "peso visual" de la imagen (vista del usuario), o null si casi nada llama la atención. */
    private fun saliencyCentroid(map: SaliencyMap, input: CompositionInput): Pair<Float, Float>? {
        var mass = 0f
        var sx = 0f
        var sy = 0f
        for (j in 0 until map.height) for (i in 0 until map.width) {
            val v = map.value(i, j)
            if (v <= 0.25f) continue
            val (x, y) = toView((i + 0.5f) / map.width, (j + 0.5f) / map.height, input)
            mass += v
            sx += v * x
            sy += v * y
        }
        if (mass < 3f) return null
        return sx / mass to sy / mass
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

    // ---------------------------------------------------------------- puntuación

    private data class Problem(val value: Float, val weight: Float, val text: String)

    private fun weightedScore(criteria: Map<String, Float>, scene: SceneType, hasSubject: Boolean): Int {
        val weights: Map<String, Float> = when {
            scene == SceneType.RETRATO || scene == SceneType.SELFIE || scene == SceneType.GRUPO -> mapOf(
                "posición" to 0.25f, "aire" to 0.15f, "sin cortes" to 0.15f, "nivel" to 0.1f,
                "bordes limpios" to 0.15f, "exposición" to 0.1f, "nitidez" to 0.1f,
                "equilibrio" to 0.05f, "simetría" to 0.2f, "líneas" to 0.1f, "estética" to 0.08f,
            )
            !hasSubject -> mapOf(
                "horizonte" to 0.25f, "nivel" to 0.2f, "bordes limpios" to 0.2f, "exposición" to 0.2f, "nitidez" to 0.15f,
                "equilibrio" to 0.1f, "simetría" to 0.3f, "líneas" to 0.25f, "estética" to 0.08f,
            )
            else -> mapOf(
                "posición" to 0.3f, "sin cortes" to 0.15f, "nivel" to 0.15f, "bordes limpios" to 0.15f,
                "exposición" to 0.15f, "nitidez" to 0.1f, "equilibrio" to 0.1f, "simetría" to 0.25f, "líneas" to 0.15f,
                "estética" to 0.08f,
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

    private fun viewStructure(input: CompositionInput): SceneStructure? =
        input.structure?.takeIf { !it.isEmpty }?.map { x, y -> toView(x, y, input) }
}
