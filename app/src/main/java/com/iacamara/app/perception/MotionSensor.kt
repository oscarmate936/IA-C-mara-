package com.iacamara.app.perception

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/** Mide cuánto tiembla la mano con el giroscopio (rad/s suavizados). */
class MotionSensor(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    @Volatile
    var shake: Float = 0f
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            shake += 0.15f * (magnitude - shake)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val manager = sensorManager ?: return
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        manager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        shake = 0f
    }
}
