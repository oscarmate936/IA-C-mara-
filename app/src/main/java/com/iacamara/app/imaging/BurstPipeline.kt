package com.iacamara.app.imaging

/**
 * Las tres técnicas de fotografía computacional de la Fase 4, listas para usar con una ráfaga:
 * mejor foto, noche (apilado) y HDR (horquillado).
 */
object BurstPipeline {

    /** Escala de la luminancia usada para alinear (1/2 de la resolución completa). */
    private const val ALIGN_SCALE = 2

    /**
     * Elige la mejor foto de una ráfaga: la más nítida, más una puntuación extra opcional
     * (p. ej. todos con los ojos abiertos).
     */
    fun bestFrame(frames: List<FrameSource>, extraScore: (Int) -> Float = { 0f }): Int {
        val sharpness = frames.map { ImageStats.sharpness(it.luma(4)) }
        val maxSharpness = sharpness.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return frames.indices.maxByOrNull { sharpness[it] / maxSharpness + extraScore(it) } ?: 0
    }

    /** Resultado de un apilado: píxeles ARGB del tamaño del fotograma de referencia. */
    class Merged(val pixels: IntArray, val width: Int, val height: Int, val reference: Int, val details: String)

    /** Modo noche: alinea la ráfaga con la foto más nítida, la promedia con rechazo de movimiento y aclara. */
    fun night(frames: List<FrameSource>, onProgress: (Float) -> Unit = {}): Merged {
        require(frames.isNotEmpty())
        val lumas = Parallel.map(frames.size) { frames[it].luma(ALIGN_SCALE) }
        val reference = lumas.indices.maxByOrNull { ImageStats.sharpness(lumas[it]) } ?: 0
        val ref = frames[reference]
        val flows = Parallel.map(frames.size) { k ->
            if (k == reference) {
                Flow.zero(ref.width, ref.height)
            } else {
                Aligner.align(lumas[reference], lumas[k], ALIGN_SCALE, ref.width, ref.height, AlignMode.SSD)
            }
        }
        onProgress(0.3f)
        // A media resolución el ruido se reduce a la mitad: se estima el de la resolución completa.
        val sigma = ImageStats.noiseSigma(lumas[reference]) * 2f
        val out = IntArray(ref.width * ref.height)
        Fusion.night(frames, flows, reference, sigma, out) { onProgress(0.3f + 0.65f * it) }
        ToneCurve.autoBrighten(out)
        ToneCurve.sharpen(out, ref.width, ref.height)
        onProgress(1f)
        return Merged(out, ref.width, ref.height, reference, "Noche IA · ${frames.size} fotos")
    }

    /** HDR: alinea el horquillado con la foto de exposición normal y fusiona las zonas bien expuestas. */
    fun hdr(frames: List<FrameSource>, evs: FloatArray, onProgress: (Float) -> Unit = {}): Merged {
        require(frames.size == evs.size && frames.isNotEmpty())
        val reference = evs.indices.minByOrNull { kotlin.math.abs(evs[it]) } ?: 0
        val ref = frames[reference]
        val lumas = Parallel.map(frames.size) { frames[it].luma(ALIGN_SCALE) }
        val flows = Parallel.map(frames.size) { k ->
            if (k == reference) {
                Flow.zero(ref.width, ref.height)
            } else {
                // Exposiciones distintas: se alinea con mapas de bits por la mediana.
                Aligner.align(lumas[reference], lumas[k], ALIGN_SCALE, ref.width, ref.height, AlignMode.MTB)
            }
        }
        onProgress(0.3f)
        val out = IntArray(ref.width * ref.height)
        Fusion.hdr(frames, flows, reference, evs, out) { onProgress(0.3f + 0.7f * it) }
        onProgress(1f)
        return Merged(out, ref.width, ref.height, reference, "HDR IA · ${frames.size} exposiciones")
    }
}
