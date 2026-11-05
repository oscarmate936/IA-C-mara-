package com.iacamara.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import com.iacamara.app.BuildConfig
import com.iacamara.app.perception.onnx.AcceleratorStore
import com.iacamara.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Genera un informe de lo que el móvil permite hacer a una app de cámara:
 * lentes, resolución real, control manual, RAW, extensiones del fabricante, Ultra HDR…
 * Sirve para adaptar la IA a cada móvil (Fase 1 del plan).
 */
object DeviceDiagnostics {

    suspend fun collect(context: Context): String {
        val device = withContext(Dispatchers.Default) { deviceSection(context) }
        val camera2 = withContext(Dispatchers.Default) { camera2Section(context) }
        val cameraX = cameraXSection(context)
        return buildString {
            append("IA Cámara ").append(BuildConfig.VERSION_NAME).append(" · Informe de diagnóstico\n\n")
            append(device).append('\n')
            append(cameraX).append('\n')
            append(camera2).append('\n')
            append(runCatching { AcceleratorStore.get(context).report() }.getOrDefault(""))
        }
    }

    private fun deviceSection(context: Context): String = buildString {
        append("== DISPOSITIVO ==\n")
        line("Modelo", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("Android", "${Build.VERSION.RELEASE} (nivel ${Build.VERSION.SDK_INT})")
        line("Chip", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        val am = context.getSystemService(ActivityManager::class.java)
        if (am != null) {
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            line("RAM", String.format(Locale.US, "%.1f GB", mem.totalMem / 1_073_741_824.0))
        }
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null) line("Temperatura", thermalName(pm.currentThermalStatus))
    }

    private suspend fun cameraXSection(context: Context): String {
        val sb = StringBuilder("== CAMERAX (lo que usa la app) ==\n")
        val provider = runCatching { ProcessCameraProvider.getInstance(context).await() }.getOrNull()
        if (provider == null) {
            sb.line("Estado", "no se pudo iniciar CameraX")
            return sb.toString()
        }
        val extensions = runCatching {
            withTimeoutOrNull(4000L) { ExtensionsManager.getInstanceAsync(context, provider).await() }
        }.getOrNull()

        val lenses = listOf(
            "Trasera" to CameraSelector.DEFAULT_BACK_CAMERA,
            "Frontal" to CameraSelector.DEFAULT_FRONT_CAMERA,
        )
        for ((name, selector) in lenses) {
            val info: CameraInfo? = runCatching { selector.filter(provider.availableCameraInfos).firstOrNull() }.getOrNull()
            sb.append("-- Cámara ").append(name.lowercase(Locale.ROOT)).append(" --\n")
            if (info == null) {
                sb.line("Estado", "no disponible")
                continue
            }
            sb.line("Flash", if (info.hasFlashUnit()) "sí" else "no")

            val exposure = info.exposureState
            if (exposure.isExposureCompensationSupported) {
                val step = exposure.exposureCompensationStep.toFloat()
                val range = exposure.exposureCompensationRange
                sb.line(
                    "Compensación de exposición",
                    String.format(Locale.US, "%.1f a +%.1f EV", range.lower * step, range.upper * step),
                )
            } else {
                sb.line("Compensación de exposición", "no")
            }

            val ultraHdr = runCatching {
                ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
                    .contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            }.getOrDefault(false)
            sb.line("Ultra HDR (JPEG_R)", if (ultraHdr) "sí" else "no")

            if (extensions == null) {
                sb.line("Extensiones del fabricante", "no se pudieron consultar")
            } else {
                val modes = listOf(
                    "Auto" to ExtensionMode.AUTO,
                    "HDR" to ExtensionMode.HDR,
                    "Noche" to ExtensionMode.NIGHT,
                    "Retrato (bokeh)" to ExtensionMode.BOKEH,
                    "Retoque facial" to ExtensionMode.FACE_RETOUCH,
                )
                val summary = modes.joinToString("  ") { (label, mode) ->
                    val ok = runCatching { extensions.isExtensionAvailable(selector, mode) }.getOrDefault(false)
                    "$label ${if (ok) "✓" else "✗"}"
                }
                sb.line("Extensiones del fabricante", summary)
            }
        }
        return sb.toString()
    }

    private fun camera2Section(context: Context): String = buildString {
        append("== CAMERA2 (detalle del hardware) ==\n")
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            line("Estado", "no disponible")
            return@buildString
        }
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        line("Cámaras visibles para apps", if (ids.isEmpty()) "ninguna" else ids.joinToString())
        val described = mutableSetOf<String>()
        for (id in ids) {
            describeCamera(this, manager, id, physical = false)
            described += id
            val physicalIds = runCatching {
                manager.getCameraCharacteristics(id).physicalCameraIds.toList()
            }.getOrDefault(emptyList())
            for (pid in physicalIds) {
                if (pid in described) continue
                describeCamera(this, manager, pid, physical = true)
                described += pid
            }
        }
    }

    private fun describeCamera(sb: StringBuilder, manager: CameraManager, id: String, physical: Boolean) {
        val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        sb.append(if (physical) "  -- Sensor físico $id --\n" else "-- Cámara $id --\n")
        if (c == null) {
            sb.line("Estado", "no se pudo leer")
            return
        }
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> "trasera"
            CameraMetadata.LENS_FACING_FRONT -> "frontal"
            CameraMetadata.LENS_FACING_EXTERNAL -> "externa"
            else -> "?"
        }
        sb.line("Posición", facing)
        sb.line("Nivel de hardware", hardwareLevelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        sb.line("Capacidades", caps.joinToString { capabilityName(it) })
        sb.line("Control manual ISO/obturación", yesNo(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps))
        sb.line("RAW", yesNo(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps))

        val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (sensorSize != null) {
            sb.line("Tamaño del sensor", String.format(Locale.US, "%.2f × %.2f mm", sensorSize.width, sensorSize.height))
        }
        if (pixelArray != null) sb.line("Píxeles del sensor", "${pixelArray.width}×${pixelArray.height} (${mp(pixelArray)})")

        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        if (focals.isNotEmpty()) {
            val diag = sensorSize?.let { sqrt(it.width * it.width + it.height * it.height) }
            sb.line("Focal", focals.joinToString { f ->
                val eq = if (diag != null && diag > 0f) " (≈${(f * 43.27f / diag).roundToInt()} mm equiv.)" else ""
                String.format(Locale.US, "%.2f mm", f) + eq
            })
        }
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let { ap ->
            sb.line("Apertura", ap.joinToString { String.format(Locale.US, "f/%.1f", it) })
        }
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.let { ois ->
            sb.line("Estabilizador óptico (OIS)", yesNo(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in ois))
        }
        c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { iso ->
            sb.line("ISO", "${iso.lower}–${iso.upper}")
        }
        c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { t ->
            sb.line("Velocidad de obturación", "${exposureText(t.lower)} – ${exposureText(t.upper)}")
        }
        c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { d ->
            sb.line("Enfoque mínimo", if (d > 0f) String.format(Locale.US, "%.1f cm", 100f / d) else "foco fijo")
        }
        c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { z ->
            sb.line("Zoom", String.format(Locale.US, "%.2fx – %.1fx", z.lower, z.upper))
        }

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            sb.line("JPEG máximo (normal)", sizeText(map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() }))
            sb.line("JPEG alta resolución", sizeText(map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() }))
            sb.line("RAW máximo", sizeText(map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.area() }))
            val jpegR = runCatching { map.getOutputSizes(ImageFormat.JPEG_R) }.getOrNull()
            sb.line("JPEG_R nativo (Ultra HDR)", yesNo(!jpegR.isNullOrEmpty()))
        }
        val maxResMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        if (maxResMap != null) {
            sb.line("JPEG modo máxima resolución", sizeText(maxResMap.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() }))
        }
        val physicalIds = c.physicalCameraIds
        if (physicalIds.isNotEmpty()) sb.line("Sensores que agrupa", physicalIds.joinToString())
    }

    private fun Size.area(): Long = width.toLong() * height.toLong()

    private fun mp(size: Size): String = String.format(Locale.US, "%.1f MP", size.area() / 1_000_000f)

    private fun sizeText(size: Size?): String = if (size == null) "no" else "${size.width}×${size.height} (${mp(size)})"

    private fun yesNo(value: Boolean) = if (value) "sí" else "no"

    private fun exposureText(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds < 1.0) "1/${(1.0 / seconds).roundToInt()} s" else String.format(Locale.US, "%.1f s", seconds)
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (básico)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED (limitado)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (completo)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (avanzado)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private fun capabilityName(cap: Int): String = when (cap) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "básica"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "sensor manual"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "posprocesado manual"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "reprocesado privado"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "lectura de ajustes"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "ráfaga"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "reprocesado YUV"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "profundidad"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "alta velocidad"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "seguimiento de movimiento"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "multicámara lógica"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "monocromo"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "datos seguros"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "cámara de sistema"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "procesado diferido"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ultra alta resolución"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "remosaico"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "10 bits"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "casos de uso"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "perfiles de color"
        else -> "cap$cap"
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "templado"
        PowerManager.THERMAL_STATUS_MODERATE -> "caliente"
        PowerManager.THERMAL_STATUS_SEVERE -> "muy caliente"
        PowerManager.THERMAL_STATUS_CRITICAL -> "crítico"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergencia"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "apagado inminente"
        else -> "?"
    }

    private fun StringBuilder.line(label: String, value: String) {
        append("  ").append(label).append(": ").append(value).append('\n')
    }
}
