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
