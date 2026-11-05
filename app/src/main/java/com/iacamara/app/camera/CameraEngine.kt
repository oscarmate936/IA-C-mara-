package com.iacamara.app.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.iacamara.app.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Lens { BACK, FRONT }

/**
 * Modos de foto. Los modos distintos de FOTO usan las extensiones del fabricante
 * (procesado propio de Samsung/Honor) cuando el móvil las ofrece a otras apps.
 */
enum class PhotoMode(val label: String, val extensionMode: Int) {
    FOTO("FOTO", ExtensionMode.NONE),
    NOCHE("NOCHE", ExtensionMode.NIGHT),
    HDR("HDR", ExtensionMode.HDR),
    RETRATO("RETRATO", ExtensionMode.BOKEH),
}

enum class FlashSetting(val cameraXMode: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF),
    AUTO(ImageCapture.FLASH_MODE_AUTO),
    ON(ImageCapture.FLASH_MODE_ON);

    fun next(): FlashSetting = entries[(ordinal + 1) % entries.size]
}

data class CameraUiState(
    val ready: Boolean = false,
    val lens: Lens = Lens.BACK,
    val hasFrontCamera: Boolean = false,
    val mode: PhotoMode = PhotoMode.FOTO,
    val availableModes: List<PhotoMode> = listOf(PhotoMode.FOTO),
    val flash: FlashSetting = FlashSetting.OFF,
    val hasFlash: Boolean = false,
    val highRes: Boolean = false,
    val captureMegapixels: Float? = null,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val capturing: Boolean = false,
    val lastPhotoUri: Uri? = null,
    val message: String? = null,
)

/**
 * Motor de cámara: abre la cámara con CameraX, aplica los ajustes y guarda las fotos.
 * Más adelante el Director IA controlará este motor de forma automática.
 */
class CameraEngine(private val context: Context, initialHighRes: Boolean) {

    private val _state = MutableStateFlow(CameraUiState(highRes = initialHighRes))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var targetRotation: Int = Surface.ROTATION_0
    private var targetZoom: Float = 1f

    suspend fun start(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        lifecycleOwner = owner
        this.surfaceProvider = surfaceProvider
        val cameraProvider = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
        if (extensions == null) {
            extensions = runCatching {
                withTimeoutOrNull(EXTENSIONS_TIMEOUT_MS) {
                    ExtensionsManager.getInstanceAsync(context, cameraProvider).await()
                }
            }.getOrNull()
        }
        val hasFront = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        _state.update { it.copy(hasFrontCamera = hasFront) }
        bind()
        refreshLastPhoto()
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
        _state.update { it.copy(ready = false, capturing = false) }
    }

    private fun baseSelector(lens: Lens): CameraSelector =
        if (lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun availableModes(lens: Lens): List<PhotoMode> {
        val ext = extensions ?: return listOf(PhotoMode.FOTO)
        val base = baseSelector(lens)
        return PhotoMode.entries.filter { mode ->
            mode == PhotoMode.FOTO ||
                runCatching { ext.isExtensionAvailable(base, mode.extensionMode) }.getOrDefault(false)
        }
    }

    private fun previewResolution(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    private fun captureResolution(highRes: Boolean): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        if (highRes) {
            // Permite los modos de máxima resolución del sensor (50/108 MP) si el fabricante los ofrece.
            builder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
        }
        return builder.build()
    }

    private fun buildCapture(highRes: Boolean, flash: FlashSetting): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(JPEG_QUALITY)
        .setFlashMode(flash.cameraXMode)
        .setTargetRotation(targetRotation)
        .setResolutionSelector(captureResolution(highRes))
        .build()

    private fun bind() {
        val cameraProvider = provider ?: return
        val owner = lifecycleOwner ?: return
        val sp = surfaceProvider ?: return
        val current = _state.value

        val modes = availableModes(current.lens)
        val mode = if (current.mode in modes) current.mode else PhotoMode.FOTO
        val base = baseSelector(current.lens)
        val ext = extensions
        val selector = if (mode == PhotoMode.FOTO || ext == null) {
            base
        } else {
            ext.getExtensionEnabledCameraSelector(base, mode.extensionMode)
        }

        val preview = Preview.Builder()
            .setResolutionSelector(previewResolution())
            .build()
        preview.setSurfaceProvider(sp)

        val wantHighRes = current.highRes && mode == PhotoMode.FOTO
        var capture = buildCapture(wantHighRes, current.flash)
        cameraProvider.unbindAll()
        val boundCamera = try {
            cameraProvider.bindToLifecycle(owner, selector, preview, capture)
        } catch (first: Exception) {
            // Algunos móviles no aceptan la máxima resolución con esta combinación: se reintenta en normal.
            cameraProvider.unbindAll()
            capture = buildCapture(false, current.flash)
            try {
                cameraProvider.bindToLifecycle(owner, selector, preview, capture)
            } catch (second: Exception) {
                _state.update { it.copy(ready = false, message = "No se pudo abrir la cámara: ${second.message}") }
                return
            }
        }

        camera = boundCamera
        imageCapture = capture
        targetZoom = 1f
        observeZoom(boundCamera, owner)

        val size = capture.resolutionInfo?.resolution
        _state.update {
            it.copy(
                ready = true,
                mode = mode,
                availableModes = modes,
                hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                captureMegapixels = size?.let { s -> s.width * s.height / 1_000_000f },
            )
        }
    }

    private var observedCamera: Camera? = null

    private fun observeZoom(cam: Camera, owner: LifecycleOwner) {
        observedCamera?.cameraInfo?.zoomState?.removeObservers(owner)
        observedCamera = cam
        cam.cameraInfo.zoomState.observe(owner) { zoom ->
            _state.update {
                it.copy(zoomRatio = zoom.zoomRatio, minZoom = zoom.minZoomRatio, maxZoom = zoom.maxZoomRatio)
            }
        }
    }

    fun setMode(mode: PhotoMode) {
        if (mode == _state.value.mode) return
        _state.update { it.copy(mode = mode) }
        bind()
    }

    fun switchLens() {
        if (!_state.value.hasFrontCamera) return
        _state.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK) }
        bind()
    }

    fun setHighRes(enabled: Boolean) {
        _state.update { it.copy(highRes = enabled) }
        bind()
    }

    fun cycleFlash() {
        val next = _state.value.flash.next()
        _state.update { it.copy(flash = next) }
        imageCapture?.flashMode = next.cameraXMode
    }

    fun setZoom(ratio: Float) {
        val s = _state.value
        targetZoom = ratio.coerceIn(s.minZoom, s.maxZoom)
        camera?.cameraControl?.setZoomRatio(targetZoom)
    }

    fun zoomBy(factor: Float) = setZoom(targetZoom * factor)

    fun focus(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (_state.value.capturing) return
        _state.update { it.copy(capturing = true) }

        val name = "IA_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                _state.update { it.copy(capturing = false, lastPhotoUri = output.savedUri ?: it.lastPhotoUri) }
            }

            override fun onError(exception: ImageCaptureException) {
                _state.update { it.copy(capturing = false, message = "No se pudo guardar la foto: ${exception.message}") }
            }
        })
    }

    /** Busca la última foto hecha con la app para mostrarla en la miniatura. */
    suspend fun refreshLastPhoto() {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$PHOTO_DIR%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
                }
            }.getOrNull()
        }
        if (uri != null) _state.update { it.copy(lastPhotoUri = it.lastPhotoUri ?: uri) }
    }

    companion object {
        const val PHOTO_DIR = "DCIM/IA Camara"
        private const val JPEG_QUALITY = 95
        private const val FOCUS_HOLD_SECONDS = 4L
        private const val EXTENSIONS_TIMEOUT_MS = 4000L
    }
}
