package com.iacamara.app.perception

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

/** Recoge ISO, tiempo de exposición y distancia de enfoque de cada fotograma del visor. */
class CaptureMetadata {

    @Volatile
    private var latest: CaptureMeta? = null

    /** Última lectura si es reciente (menos de 1 s). */
    fun current(): CaptureMeta? {
        val meta = latest ?: return null
        return if (SystemClock.elapsedRealtime() - meta.timestampMs < 1000) meta else null
    }

    fun clear() {
        latest = null
    }

    val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val aperture = result.get(CaptureResult.LENS_APERTURE) ?: 1.8f
            val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            latest = CaptureMeta(
                iso = iso,
                exposureNs = exposure,
                aperture = aperture,
                focusDistanceCm = diopters?.takeIf { it > 0.01f }?.let { 100f / it },
                timestampMs = SystemClock.elapsedRealtime(),
            )
        }
    }
}
