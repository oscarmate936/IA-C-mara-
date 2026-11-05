package com.iacamara.app.imaging

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Reparte el trabajo entre varios núcleos del procesador (los dos móviles tienen 8). */
object Parallel {

    val threads: Int = max(1, min(4, Runtime.getRuntime().availableProcessors()))

    /** Ejecuta body(0 … count−1) en paralelo y espera a que todo termine. */
    fun forRange(count: Int, body: (Int) -> Unit) {
        if (count <= 1 || threads == 1) {
            for (i in 0 until count) body(i)
            return
        }
        val pool = Executors.newFixedThreadPool(min(threads, count))
        try {
            val futures = (0 until count).map { i -> pool.submit { body(i) } }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    fun <T> map(count: Int, body: (Int) -> T): List<T> {
        val results = arrayOfNulls<Any?>(count)
        forRange(count) { results[it] = body(it) }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }
}
