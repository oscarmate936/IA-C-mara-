package com.iacamara.app.perception

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SaliencySubjectTest {

    private val w = 24
    private val h = 32

    /** Mapa con un rectángulo de valor `v` (en celdas) sobre un fondo casi nulo. */
    private fun map(x0: Int, y0: Int, x1: Int, y1: Int, v: Float, background: Float = 0.02f) =
        FloatArray(w * h) { i -> if (i % w in x0 until x1 && i / w in y0 until y1) v else background }

    @Test
    fun unObjetoClaroEsUnSujeto() {
        assertNotNull(SaliencyMath.salientSubject(map(8, 10, 16, 22, 0.9f), w, h, sceneLike = false))
    }

    @Test
    fun algoQueApenasDestacaNoLoEs() {
        // El modelo marca "lo menos aburrido" con poca seguridad (0,4): no es un sujeto.
        assertNull(SaliencyMath.salientSubject(map(8, 10, 16, 22, 0.4f), w, h, sceneLike = false))
    }

    @Test
    fun elCieloOElSueloDesdeLosBordesNoSonSujetos() {
        // Cielo claro arriba a la izquierda (toca dos bordes).
        assertNull(SaliencyMath.salientSubject(map(0, 0, 14, 12, 0.95f), w, h, sceneLike = false))
        // Una carretera que entra por la izquierda y llega abajo.
        assertNull(SaliencyMath.salientSubject(map(0, 18, 12, 32, 0.9f), w, h, sceneLike = false))
    }

    @Test
    fun enArquitecturaOPaisajeSeExigeMas() {
        // En una calle, una zona grande o pegada a un borde no es un sujeto…
        assertNull(SaliencyMath.salientSubject(map(0, 14, 15, 22, 0.85f), w, h, sceneLike = true))
        assertNull(SaliencyMath.salientSubject(map(4, 6, 20, 26, 0.9f), w, h, sceneLike = true))
        // …pero un objeto pequeño, claro y dentro de la foto (una estatua, una farola) sí.
        assertNotNull(SaliencyMath.salientSubject(map(9, 12, 14, 20, 0.9f), w, h, sceneLike = true))
    }
}
