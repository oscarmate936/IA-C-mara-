package com.iacamara.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lectura del nivel del móvil a partir del sensor de gravedad.
 *
 * nx, ny, nz: vector "hacia arriba" normalizado en coordenadas del dispositivo
 * (x a la derecha, y hacia la parte superior, z saliendo de la pantalla).
 */
data class LevelReading(val nx: Float, val ny: Float, val nz: Float) {

    /** Ángulo del horizonte real en pantalla (grados, sentido horario). 0 = móvil vertical recto. */
    val rollDegrees: Float
        get() = Math.toDegrees(atan2(nx.toDouble(), ny.toDouble())).toFloat()

    /** Orientación del encuadre más cercana: 0, 90, 180 o 270 (-90). */
    val snappedDegrees: Float
        get() = (rollDegrees / 90f).roundToInt() * 90f

    /** Desviación respecto al horizonte del encuadre actual, en grados. */
    val deviationDegrees: Float
        get() = rollDegrees - snappedDegrees

    /** El móvil está casi plano (foto cenital hacia abajo o hacia el cielo). */
    val isFlat: Boolean
        get() = abs(nz) > 0.9f

    val isLevel: Boolean
        get() = if (isFlat) {
            abs(nx) < FLAT_TOLERANCE && abs(ny) < FLAT_TOLERANCE
        } else {
            abs(deviationDegrees) < 1f
        }

    private companion object {
        /** ~1 grado. */
        const val FLAT_TOLERANCE = 0.0175f
    }
}

class LevelSensor(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    fun readings(): Flow<LevelReading> = callbackFlow {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            private var fx = 0f
            private var fy = 0f
            private var fz = 0f
            private var initialized = false

            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                if (!initialized) {
                    fx = x; fy = y; fz = z; initialized = true
                } else {
                    // Filtro paso bajo para que la línea no tiemble.
                    fx += SMOOTHING * (x - fx)
                    fy += SMOOTHING * (y - fy)
                    fz += SMOOTHING * (z - fz)
                }
                val g = sqrt(fx * fx + fy * fy + fz * fz)
                if (g > 0.1f) trySend(LevelReading(fx / g, fy / g, fz / g))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        const val SMOOTHING = 0.2f
    }
}
