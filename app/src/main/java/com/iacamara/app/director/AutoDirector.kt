package com.iacamara.app.director

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.Perception
import com.iacamara.app.perception.SceneType
import com.iacamara.app.perception.SubjectKind
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lo que el Director necesita saber de la cámara actual. */
data class DirectorCaps(
    val front: Boolean,
    val mode: PhotoMode,
    val availableModes: List<PhotoMode>,
    val zoomRatio: Float,
    val maxZoom: Float,
    val hasOpticalTele: Boolean,
    val minEv: Float,
    val maxEv: Float,
)

sealed interface TipAction {
    data class SwitchMode(val mode: PhotoMode) : TipAction
    data class SetZoom(val ratio: Float) : TipAction
}

data class Tip(val text: String, val action: TipAction? = null)

data class Direction(
    /** Zona en la que enfocar y medir la luz (encuadre vertical, sin espejo); null = sin sujeto. */
    val focusTarget: NRect?,
    val exposureEv: Float,
    val tip: Tip?,
    /** Explicación corta de los ajustes automáticos, p. ej. "Contraluz · +1 EV". */
    val reason: String?,
)

/**
 * Director IA (versión de la Fase 2): enfoca y mide la luz en el sujeto, corrige la exposición según
 * la escena y propone un consejo. Mantiene las decisiones estables para que la imagen no "salte".
 */
class DirectorSession {

    private var pendingEv = 0f
    private var pendingCount = 0
    private var stableEv = 0f
    private var currentTip: Tip? = null
    private var tipSinceMs = 0L

    fun update(p: Perception, caps: DirectorCaps): Direction {
        val (targetEv, reason) = exposureFor(p)
        val ev = stabilizeEv(targetEv.coerceIn(caps.minEv, caps.maxEv))
        val tip = stabilizeTip(tipFor(p, caps), p.timestampMs)
        val shownReason = reason?.let { if (abs(ev) >= 0.05f) "$it · ${formatEv(ev)}" else it }
        return Direction(
            focusTarget = p.subject?.box,
            exposureEv = ev,
            tip = tip,
            reason = shownReason,
        )
    }

    private fun stabilizeEv(target: Float): Float {
        val rounded = (target * 10f).roundToInt() / 10f
        if (rounded == stableEv) {
            pendingCount = 0
        } else if (rounded == pendingEv) {
            pendingCount++
            if (pendingCount >= EV_FRAMES) {
                stableEv = rounded
                pendingCount = 0
            }
        } else {
            pendingEv = rounded
            pendingCount = 1
        }
        return stableEv
    }

    private fun stabilizeTip(candidate: Tip?, now: Long): Tip? {
        val current = currentTip
        if (candidate == current) return current
        // Un consejo se mantiene al menos unos segundos para que dé tiempo a leerlo.
        if (current != null && now - tipSinceMs < TIP_MIN_MS) return current
        currentTip = candidate
        tipSinceMs = now
        return candidate
    }

    companion object {
        private const val EV_FRAMES = 6
        private const val TIP_MIN_MS = 3500L

        fun formatEv(ev: Float): String {
            val sign = if (ev > 0) "+" else "−"
            return "$sign${"%.1f".format(abs(ev)).replace('.', ',')} EV"
        }

        /** Corrección de exposición según la escena y la luz, con su motivo. */
        fun exposureFor(p: Perception): Pair<Float, String?> {
            val hasFace = p.subject?.kind == SubjectKind.CARA
            return when {
                Condition.CONTRALUZ in p.conditions && hasFace -> 0.3f to "Contraluz: luz medida en la cara"
                Condition.CONTRALUZ in p.conditions -> 1.0f to "Contraluz"
                p.scene == SceneType.NIEVE || p.scene == SceneType.PLAYA -> 0.7f to "Mucha luz"
                p.scene == SceneType.ATARDECER -> -0.7f to "Atardecer: cielo protegido"
                p.scene == SceneType.NOCHE -> -0.3f to "Noche: luces protegidas"
                Condition.LUCES_QUEMADAS in p.conditions -> -0.3f to "Luces protegidas"
                else -> 0f to null
            }
        }

        /** Un único consejo, el más útil en este momento. */
        fun tipFor(p: Perception, caps: DirectorCaps): Tip? {
            val c = p.conditions
            val retratoAvailable = PhotoMode.RETRATO in caps.availableModes && caps.mode == PhotoMode.FOTO
            return when {
                Condition.POCA_LUZ in c && Condition.TEMBLOR in c ->
                    Tip("Poca luz: apoya el móvil o mantenlo muy quieto")
                Condition.TEMBLOR in c -> Tip("Mantén el móvil quieto para que la foto salga nítida")
                Condition.CONTRALUZ in c && p.subject?.kind == SubjectKind.CARA ->
                    Tip("Contraluz: si puedes, gira para que la luz le dé de lado")
                Condition.CONTRALUZ in c -> Tip("Contraluz: he subido la exposición del sujeto")
                p.scene == SceneType.RETRATO && retratoAvailable ->
                    Tip("Retrato: toca para desenfocar el fondo", TipAction.SwitchMode(PhotoMode.RETRATO))
                p.scene == SceneType.RETRATO && !caps.front && caps.hasOpticalTele && caps.zoomRatio < 1.8f &&
                    (p.subject?.box?.area ?: 1f) < 0.08f ->
                    Tip("Retrato: el 3x favorece la cara. Toca para cambiar", TipAction.SetZoom(3f))
                p.scene == SceneType.RETRATO -> Tip("Retrato: deja aire sobre la cabeza y no cortes por las articulaciones")
                p.scene == SceneType.SELFIE -> Tip("Selfie: sostén el móvil un poco por encima de tus ojos")
                p.scene == SceneType.GRUPO -> Tip("Grupo: que todos estén a la misma distancia de la cámara")
                Condition.MOVIMIENTO in c -> Tip("Sujeto en movimiento: dispara en ráfaga con el volumen")
                p.scene == SceneType.MASCOTA -> Tip("Animal: baja a la altura de sus ojos")
                p.scene == SceneType.COMIDA -> Tip("Comida: prueba desde arriba (cenital) o a 45°, con luz de ventana")
                p.scene == SceneType.NOCHE -> Tip("Noche: mantén el móvil quieto al disparar")
                p.scene == SceneType.ATARDECER -> Tip("Atardecer: pon el sol en un punto fuerte de la rejilla")
                p.scene == SceneType.NIEVE || p.scene == SceneType.PLAYA ->
                    Tip("Mucha luz: he subido la exposición para que no salga gris")
                p.scene == SceneType.PAISAJE -> Tip("Paisaje: busca algo interesante en primer plano")
                p.scene == SceneType.CIUDAD -> Tip("Arquitectura: mantén el móvil recto para que las líneas no se inclinen")
                p.scene == SceneType.FLORES || p.scene == SceneType.MACRO ->
                    Tip("Primer plano: acércate despacio y toca la pantalla para enfocar")
                p.scene == SceneType.OBJETO -> Tip("Objeto: busca un fondo sencillo y prueba un ángulo de 3/4")
                else -> null
            }
        }
    }
}
