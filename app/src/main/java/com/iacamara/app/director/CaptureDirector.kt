package com.iacamara.app.director

import com.iacamara.app.camera.PhotoMode
import com.iacamara.app.perception.Condition
import com.iacamara.app.perception.Perception

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
 */
data class CapturePlan(
    val strategy: CaptureStrategy,
    val frames: Int,
    val intervalMs: Long = 0L,
    val lighten: Boolean = false,
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
 */
object CaptureDirector {

    /**
     * @param zoomRatio zoom actual; con zoom digital se juntan varias fotos para sacar más detalle.
     * @param hasOpticalTele el móvil tiene teleobjetivo de 3x (entre 3x y 3,5x el zoom es óptico).
     */
    fun plan(
        mode: PhotoMode,
        perception: Perception?,
        iaEnabled: Boolean,
        flashOn: Boolean,
        zoomRatio: Float = 1f,
        hasOpticalTele: Boolean = false,
    ): CapturePlan {
        val single = CapturePlan(CaptureStrategy.SINGLE, 1)
        return when (mode) {
            PhotoMode.NOCHE_IA -> CapturePlan(CaptureStrategy.NIGHT, nightFrames(perception))
            // Unas 10 fotos en ~5 s: la gente que pasa cambia de sitio y la mediana la borra.
            PhotoMode.SIN_GENTE -> CapturePlan(CaptureStrategy.REMOVE_MOVING, 10, intervalMs = 450L)
            // De noche, estelas de las luces; de día, agua y nubes sedosas.
            PhotoMode.LARGA_EXP -> {
                val dark = perception?.scene == com.iacamara.app.perception.SceneType.NOCHE ||
                    (perception?.meta?.ev100 ?: 10f) < 5f
                CapturePlan(CaptureStrategy.LONG_EXPOSURE, 16, lighten = dark)
            }
            PhotoMode.HDR_IA -> CapturePlan(CaptureStrategy.HDR, 3)
            PhotoMode.FOTO -> {
                if (!iaEnabled || flashOn || perception == null) return single
                val light = perception.light
                val c = perception.conditions
                when {
                    Condition.POCA_LUZ in c -> CapturePlan(CaptureStrategy.NIGHT, nightFrames(perception))
                    Condition.CONTRALUZ in c ||
                        (light != null && light.highlightClip > 0.04f && light.shadowClip > 0.06f) ->
                        CapturePlan(CaptureStrategy.HDR, 3)
                    isDigitalZoom(zoomRatio, hasOpticalTele) -> CapturePlan(CaptureStrategy.ZOOM_DETAIL, 6)
                    perception.faces.isNotEmpty() || Condition.MOVIMIENTO in c ->
                        CapturePlan(CaptureStrategy.BEST_OF_BURST, 4)
                    else -> single
                }
            }
            // Modos del fabricante y PRO: una sola foto (el procesado es del fabricante o manual).
            else -> single
        }
    }

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
