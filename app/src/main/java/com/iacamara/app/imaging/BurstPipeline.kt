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

    /**
     * Alinea la ráfaga con su foto más nítida (referencia). Las luces muy brillantes se recortan antes
     * de alinear: si no, unos faros que cruzan la escena "arrastrarían" la alineación con ellos.
     */
    private fun alignAll(frames: List<FrameSource>): Pair<Int, List<Flow>> {
        val lumas = Parallel.map(frames.size) { clipBright(frames[it].luma(ALIGN_SCALE)) }
        val reference = lumas.indices.maxByOrNull { ImageStats.sharpness(lumas[it]) } ?: 0
        val ref = frames[reference]
        val flows = Parallel.map(frames.size) { k ->
            if (k == reference) {
                Flow.zero(ref.width, ref.height)
            } else {
                Aligner.align(lumas[reference], lumas[k], ALIGN_SCALE, ref.width, ref.height, AlignMode.SSD)
            }
        }
        return reference to flows
    }

    /**
     * Quitar gente que pasa: de cada píxel se queda la mediana de la ráfaga alineada. Lo que se mueve y
     * no está en la mayoría de las fotos desaparece, sin inventar nada.
     */
    fun removeMoving(frames: List<FrameSource>, onProgress: (Float) -> Unit = {}): Merged {
        require(frames.isNotEmpty())
        val (reference, flows) = alignAll(frames)
        onProgress(0.3f)
        val ref = frames[reference]
        val out = IntArray(ref.width * ref.height)
        Fusion.combine(frames, flows, reference, out, { onProgress(0.3f + 0.65f * it) }) { s, n -> Fusion.medianSample(s, n) }
        ToneCurve.sharpen(out, ref.width, ref.height, amount = 0.3f)
        onProgress(1f)
        return Merged(out, ref.width, ref.height, reference, "Sin gente · ${frames.size} fotos")
    }

    /**
     * Larga exposición sin trípode: la ráfaga alineada se promedia (de día: agua sedosa, nubes que
     * corren) o se aclara (de noche: estelas de los faros y luces).
     */
    fun longExposure(frames: List<FrameSource>, lighten: Boolean, onProgress: (Float) -> Unit = {}): Merged {
        require(frames.isNotEmpty())
        val (reference, flows) = alignAll(frames)
        onProgress(0.3f)
        val ref = frames[reference]
        val out = IntArray(ref.width * ref.height)
        Fusion.combine(frames, flows, reference, out, { onProgress(0.3f + 0.65f * it) }) { s, n ->
            if (lighten) Fusion.lightenSample(s, n) else Fusion.meanSample(s, n)
        }
        onProgress(1f)
        val kind = if (lighten) "estelas de luz" else "efecto seda"
        return Merged(out, ref.width, ref.height, reference, "Larga exposición ($kind) · ${frames.size} fotos")
    }

    /**
     * Zoom con detalle: con zoom digital cada foto es un recorte ampliado (ruidoso y con bordes
     * escalonados). Al juntar varias fotos alineadas baja el ruido y se puede afinar más sin que el
     * grano se note.
     */
    fun zoomDetail(frames: List<FrameSource>, onProgress: (Float) -> Unit = {}): Merged {
        require(frames.isNotEmpty())
        val (reference, flows) = alignAll(frames)
        onProgress(0.3f)
        val ref = frames[reference]
        val lumas = frames[reference].luma(ALIGN_SCALE)
        val sigma = ImageStats.noiseSigma(lumas) * 2f
        val out = IntArray(ref.width * ref.height)
        Fusion.night(frames, flows, reference, sigma, out) { onProgress(0.3f + 0.6f * it) }
        ToneCurve.sharpen(out, ref.width, ref.height, amount = 0.75f, threshold = 2)
        onProgress(1f)
        return Merged(out, ref.width, ref.height, reference, "Zoom con detalle · ${frames.size} fotos")
    }

    /** Luminancia con todo lo que pase del percentil 90 aplanado a ese valor. */
    private fun clipBright(l: LumaPlane): LumaPlane {
        val hist = IntArray(256)
        for (b in l.data) hist[b.toInt() and 0xFF]++
        var acc = 0
        var cap = 255
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= l.data.size * 0.9) {
                cap = v
                break
            }
        }
        val out = ByteArray(l.data.size) { i -> minOf(l.data[i].toInt() and 0xFF, cap).toByte() }
        return LumaPlane(l.width, l.height, out)
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
