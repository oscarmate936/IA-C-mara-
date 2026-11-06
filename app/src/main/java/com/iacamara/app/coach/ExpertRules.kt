package com.iacamara.app.coach

import com.iacamara.app.director.TipAction
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.SubjectKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Un consejo candidato del fotógrafo.
 *
 * @param id tema del consejo (estable mientras dura la situación: así no cambia de frase cada fotograma).
 * @param priority importancia 0–1; se muestra solo el más importante.
 * @param variants formas distintas de decir lo mismo (se van turnando para no sonar repetitivo).
 * @param habit hábito del usuario con el que tiene que ver (si lo tiene, el consejo sube de prioridad).
 */
data class Advice(
    val id: String,
    val priority: Float,
    val variants: List<String>,
    val action: TipAction? = null,
    val habit: Habit? = null,
)

/** Lo que el fotógrafo mira además de la percepción: el ángulo del móvil y la cámara. */
data class ExpertInput(
    val perception: Perception,
    val front: Boolean,
    /** Inclinación de la cámara: positivo = mirando hacia arriba (grados); null con el móvil plano. */
    val pitchDeg: Float?,
)

/**
 * Base de reglas del "fotógrafo veterano": situaciones que un fotógrafo con experiencia corregiría y
 * que el coach de encuadre no cubre (la luz sobre la cara, lo que hay detrás de la cabeza, el momento,
 * el ángulo). Cada regla mira medidas reales del visor; ninguna inventa nada.
 */
object ExpertRules {

    fun evaluate(input: ExpertInput): List<Advice> {
        val p = input.perception
        val out = mutableListOf<Advice>()
        val subject = p.subject
        val people = subject?.kind == SubjectKind.CARA || subject?.kind == SubjectKind.PERSONA
        val face = p.faces.maxByOrNull { it.area }
        val ev = p.meta?.ev100

        // --- Luz sobre la cara.
        if (people && Condition.CONTRALUZ in p.conditions) {
            out += Advice(
                "luz.contraluz", 0.75f,
                listOf(
                    "La luz le viene por detrás y la cara queda oscura: gírala hacia la luz o usa el flash de relleno",
                    "Contraluz en la cara: cámbiate de lado para que la luz le dé de frente o de lado",
                    "Tiene la luz detrás: el pelo brilla bonito, pero la cara se apaga; prueba el flash de relleno",
                ),
            )
        }
        val fl = p.faceLight
        if (face != null && fl != null && Condition.CONTRALUZ !in p.conditions) {
            val daylight = (ev != null && ev >= 11f) || (p.light?.meanLuma ?: 0f) > 120f
            if (daylight && fl.eyeShadow > 0.3f && max(fl.forehead, fl.cheeks) > 120f) {
                out += Advice(
                    "luz.cenital", 0.7f,
                    listOf(
                        "El sol cae desde arriba y deja los ojos en sombra: busca una sombra abierta",
                        "Luz de mediodía: sombras duras bajo las cejas. Ponte a la sombra o usa el flash de relleno",
                        "Ojos en sombra por la luz cenital: que levante un poco la cara o buscad sombra",
                    ),
                )
            }
            if (fl.clipped > 0.12f) {
                out += Advice(
                    "luz.cara.quemada", 0.7f,
                    listOf(
                        "La cara se quema: toca la cara para bajar la exposición",
                        "Demasiada luz en la cara: busca sombra o toca la cara para medir la luz ahí",
                    ),
                )
            }
            if (fl.sideContrast > 0.5f && fl.mean > 50f && fl.clipped <= 0.12f) {
                out += Advice(
                    "luz.lateral", 0.45f,
                    listOf(
                        "Media cara queda en sombra: si no lo buscas, gira a la persona hacia la luz",
                        "Luz muy lateral: queda dramática; para un retrato suave, que mire más hacia la luz",
                    ),
                )
            }
        }

        // --- Fondo: algo vertical que parece salirle de la cabeza.
        val head = headBox(p)
        if (head != null) {
            poleFromHead(head, p)?.let { (side, name) ->
                val what = name ?: "Un poste"
                out += Advice(
                    "fondo.cabeza", 0.8f,
                    listOf(
                        "$what parece salirle de la cabeza: da un paso a la $side",
                        "Cuidado con el fondo: hay algo vertical justo detrás de su cabeza; muévete un poco a la $side",
                        "Algo «le sale» de la cabeza: cambia un poco el ángulo (un paso a la $side)",
                    ),
                )
            }
        }

        // --- El momento: ojos cerrados.
        val closed = p.faceDetails.count { !it.eyesOpen }
        if (closed > 0 && p.faceDetails.isNotEmpty()) {
            out += if (p.faceDetails.size >= 2) {
                Advice(
                    "momento.ojos", 0.68f,
                    listOf(
                        "Alguien tiene los ojos cerrados: espera un instante antes de disparar",
                        "Hay ojos cerrados en el grupo: espera un segundo (la ráfaga también lo arregla)",
                    ),
                )
            } else {
                Advice(
                    "momento.ojos", 0.62f,
                    listOf(
                        "Tiene los ojos cerrados: espera a que los abra",
                        "Ojos cerrados: dispara cuando parpadee y los vuelva a abrir",
                    ),
                )
            }
        }

        // --- El ángulo.
        val pitch = input.pitchDeg
        if (pitch != null && !input.front && people && p.scene != SceneType.SELFIE && pitch > 15f) {
            out += Advice(
                "angulo.contrapicado", 0.55f,
                listOf(
                    "Estás disparando desde abajo: sube el móvil a la altura de sus ojos",
                    "El ángulo desde abajo no favorece: ponte a la altura de su cara",
                ),
            )
        }
        if (pitch != null && !input.front && p.scene == SceneType.MASCOTA && pitch < -25f) {
            out += Advice(
                "angulo.mascota", 0.55f,
                listOf(
                    "Baja a la altura de sus ojos: la foto ganará mucho",
                    "Agáchate: las mascotas se ven mejor a su altura",
                ),
            )
        }

        // --- Sujeto perdido en la foto.
        if (subject?.kind == SubjectKind.PERSONA && subject.box.area < 0.02f &&
            p.scene != SceneType.PAISAJE && p.scene != SceneType.PLAYA && p.scene != SceneType.NIEVE
        ) {
            out += Advice(
                "sujeto.lejos", 0.5f,
                listOf(
                    "La persona se pierde en la foto: acércate o usa el zoom",
                    "Acércate más: el sujeto ocupa muy poco",
                ),
            )
        }
        return out
    }

    /** Zona de la cabeza del sujeto principal (cara, o lo alto del cuerpo). */
    internal fun headBox(p: Perception): NRect? {
        val subject = p.subject ?: return null
        if (subject.kind != SubjectKind.CARA && subject.kind != SubjectKind.PERSONA) return null
        p.faces.maxByOrNull { it.area }?.let { return it }
        val b = subject.box
        val h = b.height * 0.18f
        return NRect(b.centerX - b.width * 0.25f, b.top, b.centerX + b.width * 0.25f, b.top + h)
    }

    /**
     * Línea casi vertical (poste, farola, tronco) que pasa justo por detrás del centro de la cabeza y sigue
     * por encima de ella. @return hacia dónde dar un paso para separarla ("izquierda"/"derecha") y su
     * nombre si el detector lo sabe.
     */
    internal fun poleFromHead(head: NRect, p: Perception): Pair<String, String?>? {
        val verticals = p.structure?.verticals.orEmpty()
        val halfW = head.width / 2f
        val above = max(0.06f, head.height * 0.6f)
        val hit = verticals.firstOrNull { s ->
            val top = min(s.y1, s.y2)
            val bottom = max(s.y1, s.y2)
            // Pasa por la mitad central de la cabeza…
            val x = s.x1 + (s.x2 - s.x1) * ((head.top - s.y1) / ((s.y2 - s.y1).takeIf { abs(it) > 1e-4f } ?: 1f))
            abs(x - head.centerX) < halfW * 0.5f &&
                // …y sigue claramente por encima de ella, empezando en la cabeza o justo encima.
                top < head.top - above && bottom > head.top - 0.02f && bottom < head.bottom + head.height
        } ?: return null
        // Al dar un paso hacia el lado en el que está la línea, el sujeto (más cerca) se desplaza más que
        // el fondo y se separan.
        val lineX = (hit.x1 + hit.x2) / 2f
        val side = if (lineX >= head.centerX) "derecha" else "izquierda"
        val name = p.namedObjects
            .filter { it.label in POLES && it.box.left <= lineX && it.box.right >= lineX && it.box.top < head.top }
            .maxByOrNull { it.score }
            ?.let { POLES[it.label] }
        return side to name
    }

    /** Cosas verticales (nombre del detector de vocabulario abierto) que suelen "salir de la cabeza". */
    private val POLES = mapOf(
        "Farola" to "Una farola",
        "Poste" to "Un poste",
        "Palmera" to "Una palmera",
        "Semáforo" to "Un semáforo",
        "Señal" to "Una señal",
    )
}
