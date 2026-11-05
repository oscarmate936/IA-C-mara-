package com.iacamara.app.perception

/** Conversión de coordenadas entre el fotograma que ve el modelo y el encuadre vertical de la pantalla. */
object FrameMath {

    /**
     * Grados (horarios) que hay que girar el encuadre vertical para que el modelo vea la escena derecha
     * cuando el móvil está en horizontal. En la cámara frontal la imagen no está en espejo, así que el giro
     * va en sentido contrario.
     */
    fun modelRotation(deviceDegrees: Int, front: Boolean): Int {
        val d = normalize(deviceDegrees)
        return if (front) normalize(360 - d) else d
    }

    /** Gira un punto normalizado `rotation` grados en sentido horario (como Matrix.postRotate). */
    fun rotate(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (normalize(rotation)) {
        90 -> 1f - y to x
        180 -> 1f - x to 1f - y
        270 -> y to 1f - x
        else -> x to y
    }

    /** Punto (u, v) del fotograma girado `rotation` grados → punto del encuadre vertical. */
    fun unrotate(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (normalize(rotation)) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }

    fun unrotate(rect: NRect, rotation: Int): NRect {
        val (x1, y1) = unrotate(rect.left, rect.top, rotation)
        val (x2, y2) = unrotate(rect.right, rect.bottom, rotation)
        return NRect.of(x1, y1, x2, y2).clamped()
    }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

/**
 * El temblor de la mano solo importa si mueve la imagen durante la exposición:
 * desenfoque ≈ velocidad de giro × tiempo de exposición × focal en píxeles (× zoom).
 * De día, con exposiciones muy cortas, aunque la mano se mueva la foto sale nítida.
 */
object BlurRisk {
    /** Focal aproximada en píxeles de una foto de 12 MP con la lente de ~24 mm. */
    private const val FOCAL_PX = 2800f

    /** Píxeles de desenfoque a partir de los cuales se nota la foto movida. */
    private const val BLUR_PX_LIMIT = 4f

    /** Sin datos del sensor, solo se avisa con un temblor fuerte (rad/s). */
    private const val SHAKE_WITHOUT_META = 0.45f

    fun blurPixels(shakeRadPerS: Float, exposureNs: Long, zoom: Float): Float =
        shakeRadPerS * (exposureNs / 1_000_000_000f) * FOCAL_PX * zoom.coerceAtLeast(1f)

    fun isRisky(shakeRadPerS: Float, meta: CaptureMeta?, zoom: Float): Boolean =
        if (meta == null) shakeRadPerS > SHAKE_WITHOUT_META
        else blurPixels(shakeRadPerS, meta.exposureNs, zoom) > BLUR_PX_LIMIT
}
