package com.iacamara.app.director

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.coach.Habit
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.Perception
import kotlin.math.roundToInt

/** Técnica con la que se hace la foto (Capa 3 del plan: fotografía computacional). */
enum class CaptureStrategy(val label: String) {
    SINGLE("Foto"),
    BEST_OF_BURST("Mejor de 4"),
    NIGHT("Noche IA"),
    HDR("HDR IA"),
    REMOVE_MOVING("Sin gente"),
    LONG_EXPOSURE("Larga exposición"),
    ZOOM_DETAIL("Zoom con detalle"),
}

/**
 * @param intervalMs pausa entre fotos (para que la gente que pasa cambie de sitio).
 * @param lighten larga exposición de noche: estelas de luz en vez de promedio.
 * @param reason por qué el Director eligió esta técnica, explicado como lo diría un fotógrafo.
 */
data class CapturePlan(
    val strategy: CaptureStrategy,
    val frames: Int,
    val intervalMs: Long = 0L,
    val lighten: Boolean = false,
    val reason: String = "",
) {
    val description: String
        get() = when (strategy) {
            CaptureStrategy.NIGHT -> "${strategy.label} · $frames fotos"
            CaptureStrategy.HDR -> "${strategy.label} · 3 exposiciones"
            CaptureStrategy.REMOVE_MOVING -> "${strategy.label} · $frames fotos"
            CaptureStrategy.ZOOM_DETAIL -> "${strategy.label} · $frames fotos"
            CaptureStrategy.LONG_EXPOSURE -> if (lighten) "Estelas de luz · $frames fotos" else "Efecto seda · $frames fotos"
            else -> strategy.label
        }
}

/**
 * El Director IA decide, en el momento de disparar, qué técnica usar:
 * - Poca luz → modo noche (apilar varias fotos para quitar ruido).
 * - Contraluz o mucho contraste → HDR (horquillado de exposiciones).
 * - Zoom digital → varias fotos juntas (menos ruido y bordes más limpios).
 * - Personas o movimiento → ráfaga y quedarse con la más nítida y con los ojos abiertos.
 * - Si no, una foto normal (la más rápida).
 *
 * Con el flash activado (encendido o automático) manda el flash: una sola foto con flash, también en
 * los modos NOCHE y HDR (en una ráfaga unas fotos saldrían con flash y otras sin él). SIN GENTE y
 * LARGA EXP. no tienen sentido con flash: siguen siendo ráfagas, sin flash.
 */
object CaptureDirector {

    /**
     * @param flashOn flash encendido o automático.
     * @param zoomRatio zoom actual; con zoom digital se juntan varias fotos para sacar más detalle.
     * @param hasOpticalTele el móvil tiene teleobjetivo de 3x (entre 3x y 3,5x el zoom es óptico).
     * @param habits tus hábitos: si sueles hacer fotos movidas, se hace una ráfaga y se elige la más nítida.
     */
    fun plan(
        mode: PhotoMode,
        perception: Perception?,
        iaEnabled: Boolean,
        flashOn: Boolean,
        zoomRatio: Float = 1f,
        hasOpticalTele: Boolean = false,
        habits: Set<Habit> = emptySet(),
    ): CapturePlan {
        val flash = CapturePlan(CaptureStrategy.SINGLE, 1, reason = "Flash activado: una sola foto con flash")
        val shaky = perception != null && Condition.TEMBLOR in perception.conditions
        val steady = if (shaky) "mantén el móvil muy quieto" else "el móvil está estable"
        return when (mode) {
            PhotoMode.NOCHE_IA -> if (flashOn) {
                flash
            } else {
                val n = nightFrames(perception)
                CapturePlan(CaptureStrategy.NIGHT, n, reason = "Modo noche: junto $n fotos para quitar el ruido sin flash ($steady)")
            }
            // Unas 10 fotos en ~5 s: la gente que pasa cambia de sitio y la mediana la borra.
            PhotoMode.SIN_GENTE -> CapturePlan(
                CaptureStrategy.REMOVE_MOVING, 10, intervalMs = 450L,
                reason = "Sin gente: 10 fotos en unos 5 segundos; lo que se mueve no está en la mayoría y desaparece",
            )
            // De noche, estelas de las luces; de día, agua y nubes sedosas.
            PhotoMode.LARGA_EXP -> {
                val dark = perception?.scene == com.iacamara.app.perception.SceneType.NOCHE ||
                    (perception?.meta?.ev100 ?: 10f) < 5f
                CapturePlan(
                    CaptureStrategy.LONG_EXPOSURE, 16, lighten = dark,
                    reason = if (dark) "Es de noche: junto 16 fotos quedándome con lo más luminoso, así las luces dejan estela"
                    else "Es de día: promedio 16 fotos para que el agua y las nubes salgan sedosas",
                )
            }
            PhotoMode.HDR_IA -> if (flashOn) {
                flash
            } else {
                CapturePlan(CaptureStrategy.HDR, 3, reason = "HDR: 3 exposiciones (−2, 0 y +2) para recuperar el cielo y las sombras")
            }
            PhotoMode.FOTO -> {
                if (flashOn) return flash
                if (!iaEnabled || perception == null) {
                    return CapturePlan(CaptureStrategy.SINGLE, 1, reason = "Una foto normal: la IA está apagada")
                }
                val light = perception.light
                val c = perception.conditions
                val ev = perception.meta?.ev100
                when {
                    Condition.POCA_LUZ in c -> {
                        val n = nightFrames(perception)
                        val how = ev?.let { " (luz ≈ EV ${it.roundToInt()})" } ?: ""
                        CapturePlan(CaptureStrategy.NIGHT, n, reason = "Hay poca luz$how: junto $n fotos para quitar el ruido sin flash ($steady)")
                    }
                    Condition.CONTRALUZ in c ->
                        CapturePlan(CaptureStrategy.HDR, 3, reason = "Contraluz: 3 exposiciones para que ni el cielo se queme ni el sujeto quede oscuro")
                    light != null && light.highlightClip > 0.04f && light.shadowClip > 0.06f ->
                        CapturePlan(CaptureStrategy.HDR, 3, reason = "Mucho contraste (luces quemadas y sombras negras): 3 exposiciones juntas")
                    isDigitalZoom(zoomRatio, hasOpticalTele) -> CapturePlan(
                        CaptureStrategy.ZOOM_DETAIL, 6,
                        reason = "Zoom digital ×${"%.1f".format(zoomRatio).replace('.', ',')}: junto 6 fotos para que haya más detalle y menos ruido",
                    )
                    perception.faces.isNotEmpty() -> CapturePlan(
                        CaptureStrategy.BEST_OF_BURST, 4,
                        reason = "Hay personas: hago 4 fotos y me quedo con la más nítida y con los ojos abiertos",
                    )
                    Condition.MOVIMIENTO in c -> CapturePlan(
                        CaptureStrategy.BEST_OF_BURST, 4,
                        reason = "Algo se mueve: hago 4 fotos y me quedo con la más nítida",
                    )
                    // Hábito: si sueles mover las fotos, la cámara lo compensa con una ráfaga.
                    Habit.MOVIDA in habits -> CapturePlan(
                        CaptureStrategy.BEST_OF_BURST, 4,
                        reason = "Te salen a menudo fotos movidas: hago 4 y me quedo con la más nítida",
                    )
                    else -> CapturePlan(CaptureStrategy.SINGLE, 1, reason = "Buena luz y escena quieta: con una foto basta")
                }
            }
            PhotoMode.PRO -> CapturePlan(CaptureStrategy.SINGLE, 1, reason = "Modo PRO: una foto con tus ajustes, sin procesado de la IA")
            // Modos del fabricante: una sola foto (el procesado es del fabricante).
            else -> CapturePlan(CaptureStrategy.SINGLE, 1, reason = "Modo ${mode.label} del fabricante: el procesado lo hace el móvil")
        }
    }

    /** Modos que siempre son ráfaga y no usan flash. */
    fun ignoresFlash(mode: PhotoMode): Boolean = mode == PhotoMode.SIN_GENTE || mode == PhotoMode.LARGA_EXP

    /** Zoom que se consigue recortando y ampliando (no con un objetivo): ahí ayudan varias fotos. */
    fun isDigitalZoom(zoomRatio: Float, hasOpticalTele: Boolean): Boolean = when {
        zoomRatio < 1.5f -> false
        hasOpticalTele -> zoomRatio < 2.95f || zoomRatio >= 3.5f
        else -> true
    }

    /** Más fotos cuanto más larga es la exposición (más oscuro): más ruido que quitar. */
    fun nightFrames(perception: Perception?): Int {
        val exposureNs = perception?.meta?.exposureNs ?: return 8
        return when {
            exposureNs >= 50_000_000L -> 8 // ≥ 1/20 s
            exposureNs >= 20_000_000L -> 7 // ≥ 1/50 s
            else -> 6
        }
    }
}
