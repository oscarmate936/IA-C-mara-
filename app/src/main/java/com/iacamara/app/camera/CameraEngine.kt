package com.iacamara.app.camera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
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
import com.iacamara.app.perception.CaptureMetadata
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Lens { BACK, FRONT }

/**
 * Modos de foto. Los modos distintos de FOTO usan las extensiones del fabricante
 * (procesado propio de Samsung/Honor) cuando el móvil las ofrece a otras apps.
 */
enum class PhotoMode(val label: String, val extensionMode: Int) {
    FOTO("FOTO", ExtensionMode.NONE),

    /** Procesado automático del fabricante (en el S22+, el de Samsung). */
    AUTO("AUTO", ExtensionMode.AUTO),
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
    val captureMegapixels: Float? = null,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val capturing: Boolean = false,
    val lastPhotoUri: Uri? = null,
    val message: String? = null,
    /** La IA está analizando el visor (algunos modos del fabricante no lo permiten). */
    val analysisActive: Boolean = false,
    val exposureEv: Float = 0f,
    val minEv: Float = 0f,
    val maxEv: Float = 0f,
    /** El móvil tiene teleobjetivo óptico (S22+: 3x). */
    val hasOpticalTele: Boolean = false,
    /** Aumenta cada vez que se vuelve a abrir la cámara (los ajustes automáticos se reinician). */
    val bindGeneration: Int = 0,
)

/**
 * Motor de cámara: abre la cámara con CameraX, aplica los ajustes y guarda las fotos.
 * Más adelante el Director IA controlará este motor de forma automática.
 */
class CameraEngine(private val context: Context) {

    private val _state = MutableStateFlow(CameraUiState())
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

    /** Lecturas del sensor (ISO, exposición, enfoque) del visor, para la IA. */
    val captureMetadata = CaptureMetadata()
    private var analyzer: ImageAnalysis.Analyzer? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** Se debe llamar antes de start(). */
    fun setAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
        this.analyzer = analyzer
    }

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
        val tele = withContext(Dispatchers.Default) { detectOpticalTele() }
        _state.update { it.copy(hasFrontCamera = hasFront, hasOpticalTele = tele) }
        bind()
        refreshLastPhoto()
    }

    fun stop() {
        provider?.unbindAll()
        camera = null
        imageCapture = null
        _state.update { it.copy(ready = false, capturing = false, analysisActive = false) }
    }

    /** Libera el hilo de análisis; el motor ya no se puede volver a usar. */
    fun release() {
        stop()
        analysisExecutor.shutdown()
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

    // Samsung y Honor solo entregan a otras apps la resolución agrupada del sensor
    // (12,5 MP y 12 MP), así que se pide siempre la mayor disponible en 4:3.
    private fun captureResolution(): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .build()

    private fun buildCapture(flash: FlashSetting): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(JPEG_QUALITY)
        .setFlashMode(flash.cameraXMode)
        .setTargetRotation(targetRotation)
        .setResolutionSelector(captureResolution())
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

        val previewBuilder = Preview.Builder().setResolutionSelector(previewResolution())
        if (mode == PhotoMode.FOTO) {
            // Lee ISO, exposición y distancia de enfoque de cada fotograma (la IA las usa para la luz).
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureMetadata.callback)
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(sp)
        captureMetadata.clear()

        val capture = buildCapture(current.flash)
        val analysisAllowed = mode == PhotoMode.FOTO || ext == null ||
            runCatching { ext.isImageAnalysisSupported(base, mode.extensionMode) }.getOrDefault(false)
        val analysis = analyzer?.takeIf { analysisAllowed }?.let { buildAnalysis(it) }

        cameraProvider.unbindAll()
        var analysisActive = analysis != null
        val boundCamera = try {
            if (analysis != null) {
                cameraProvider.bindToLifecycle(owner, selector, preview, capture, analysis)
            } else {
                cameraProvider.bindToLifecycle(owner, selector, preview, capture)
            }
        } catch (e: Exception) {
            // Si el móvil no acepta las tres salidas a la vez, se abre sin análisis.
            cameraProvider.unbindAll()
            analysisActive = false
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
        val exposure = boundCamera.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        val evSupported = exposure.isExposureCompensationSupported && step > 0f
        _state.update {
            it.copy(
                ready = true,
                mode = mode,
                availableModes = modes,
                hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                captureMegapixels = size?.let { s -> s.width * s.height / 1_000_000f },
                analysisActive = analysisActive,
                exposureEv = 0f,
                minEv = if (evSupported) exposure.exposureCompensationRange.lower * step else 0f,
                maxEv = if (evSupported) exposure.exposureCompensationRange.upper * step else 0f,
                bindGeneration = it.bindGeneration + 1,
            )
        }
    }

    private fun buildAnalysis(analyzer: ImageAnalysis.Analyzer): ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .also { it.setAnalyzer(analysisExecutor, analyzer) }

    /** ¿Alguna cámara trasera tiene focal equivalente de 50 mm o más (teleobjetivo óptico)? */
    private fun detectOpticalTele(): Boolean = runCatching {
        val manager = context.getSystemService(CameraManager::class.java) ?: return@runCatching false
        val ids = manager.cameraIdList.flatMap { id ->
            listOf(id) + runCatching { manager.getCameraCharacteristics(id).physicalCameraIds.toList() }
                .getOrDefault(emptyList())
        }.distinct()
        ids.any { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@any false
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@any false
            val diagonal = sqrt(size.width * size.width + size.height * size.height)
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return@any false
            diagonal > 0f && focals.any { it * 43.27f / diagonal >= 50f }
        }
    }.getOrDefault(false)

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

    /** Enfoque y medición tocando la pantalla: se mantiene unos segundos. */
    fun focus(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
        ).setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    /** Enfoque y medición automáticos sobre el sujeto que ha encontrado la IA: se mantienen hasta que cambie. */
    fun focusOnSubject(point: MeteringPoint) {
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).disableAutoCancel().build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun cancelFocus() {
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    /** Compensación de exposición en EV, ajustada a los pasos que permite la cámara. */
    fun setExposureEv(ev: Float) {
        val cam = camera ?: return
        val exposure = cam.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        if (!exposure.isExposureCompensationSupported || step <= 0f) return
        val range = exposure.exposureCompensationRange
        val index = (ev / step).roundToInt().coerceIn(range.lower, range.upper)
        if (index != exposure.exposureCompensationIndex) {
            cam.cameraControl.setExposureCompensationIndex(index)
        }
        _state.update { it.copy(exposureEv = index * step) }
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
