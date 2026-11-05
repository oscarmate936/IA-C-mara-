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
}

data class CapturePlan(val strategy: CaptureStrategy, val frames: Int) {
    val description: String
        get() = when (strategy) {
            CaptureStrategy.NIGHT -> "${strategy.label} · $frames fotos"
            CaptureStrategy.HDR -> "${strategy.label} · 3 exposiciones"
            else -> strategy.label
        }
}

/**
 * El Director IA decide, en el momento de disparar, qué técnica usar:
 * - Poca luz → modo noche (apilar varias fotos para quitar ruido).
 * - Contraluz o mucho contraste → HDR (horquillado de exposiciones).
 * - Personas o movimiento → ráfaga y quedarse con la más nítida y con los ojos abiertos.
 * - Si no, una foto normal (la más rápida).
 */
object CaptureDirector {

    fun plan(mode: PhotoMode, perception: Perception?, iaEnabled: Boolean, flashOn: Boolean): CapturePlan {
        val single = CapturePlan(CaptureStrategy.SINGLE, 1)
        return when (mode) {
            PhotoMode.NOCHE_IA -> CapturePlan(CaptureStrategy.NIGHT, nightFrames(perception))
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
                    perception.faces.isNotEmpty() || Condition.MOVIMIENTO in c ->
                        CapturePlan(CaptureStrategy.BEST_OF_BURST, 4)
                    else -> single
                }
            }
            // Modos del fabricante y PRO: una sola foto (el procesado es del fabricante o manual).
            else -> single
        }
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
