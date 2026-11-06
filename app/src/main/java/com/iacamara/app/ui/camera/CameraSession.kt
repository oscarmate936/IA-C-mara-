package com.iacamara.app.ui.camera

import android.content.Context
import com.iacamara.app.camera.CameraEngine
import com.iacamara.app.perception.MotionSensor
import com.iacamara.app.perception.PerceptionAnalyzer

/**
 * La cámara y la IA, que viven mientras la app está abierta (no solo mientras se ve el visor): al ir a
 * Ajustes y volver no se recargan los modelos (varios segundos) ni se pierden las fotos que se están
 * procesando o mejorando. Al salir del visor solo se apaga la cámara.
 */
class CameraSession(context: Context) {
    val engine = CameraEngine(context)
    val motionSensor = MotionSensor(context)
    val analyzer = PerceptionAnalyzer(context, engine.captureMetadata, motionSensor)

    /** Al cerrar la app: libera la cámara, los hilos y los modelos. */
    fun release() {
        engine.release()
        analyzer.close()
    }
}
