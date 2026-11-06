package com.iacamara.app.learning

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lo que se sabe de una opción (una versión de la mejora, una forma de decir un consejo): cuántas veces
 * gustó o funcionó y cuántas no, empezando con una opinión neutra (1 y 1).
 */
class Arm(var good: Float = PRIOR, var bad: Float = PRIOR) {
    /** Probabilidad estimada de que guste. */
    val mean: Float get() = good / (good + bad)

    /** Valoraciones reales (sin contar la opinión neutra del principio). */
    val trials: Float get() = max(0f, good + bad - 2 * PRIOR)

    fun add(success: Boolean, weight: Float = 1f) {
        if (success) good += weight else bad += weight
    }

    /** Olvida una parte (tras aprender algo nuevo, lo de antes cuenta menos), sin bajar de la opinión neutra. */
    fun decay(keep: Float) {
        good = PRIOR + (good - PRIOR) * keep
        bad = PRIOR + (bad - PRIOR) * keep
    }

    /** Incertidumbre de [mean] (varianza de la Beta). */
    val variance: Float get() {
        val n = good + bad
        return good * bad / (n * n * (n + 1f))
    }

    /** Cuánto mejor que [other], en unidades de incertidumbre (≥ 1,65 ≈ 95 % de seguridad). */
    fun advantageOver(other: Arm): Float = (mean - other.mean) / sqrt(variance + other.variance)

    /** Muestreo de Thompson: un valor plausible de la probabilidad de que guste (Beta(good, bad)). */
    fun sample(random: Random): Float = Thompson.beta(good, bad, random)

    fun encode(): String = "${"%.3f".format(java.util.Locale.ROOT, good)}:${"%.3f".format(java.util.Locale.ROOT, bad)}"

    companion object {
        const val PRIOR = 1f

        fun decode(text: String): Arm? {
            val parts = text.split(':')
            val g = parts.getOrNull(0)?.toFloatOrNull() ?: return null
            val b = parts.getOrNull(1)?.toFloatOrNull() ?: return null
            return Arm(max(PRIOR, g), max(PRIOR, b))
        }
    }
}

/** Muestreo de la distribución Beta (para el muestreo de Thompson), sin librerías. */
object Thompson {

    fun beta(a: Float, b: Float, random: Random): Float {
        val x = gamma(a.toDouble(), random)
        val y = gamma(b.toDouble(), random)
        return if (x + y <= 0.0) 0.5f else (x / (x + y)).toFloat()
    }

    /** Gamma(k, 1) por el método de Marsaglia y Tsang (k ≥ 1; para k < 1, con el truco de k + 1). */
    private fun gamma(k: Double, random: Random): Double {
        if (k < 1.0) return gamma(k + 1.0, random) * random.nextDouble().coerceAtLeast(1e-12).pow(1.0 / k)
        val d = k - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = normal(random)
                v = 1.0 + c * x
            } while (v <= 0.0)
            v *= v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun normal(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
