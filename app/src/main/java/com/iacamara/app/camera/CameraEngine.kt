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
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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
import com.iacamara.app.capture.CapturedFrame
import com.iacamara.app.capture.PhotoEnhancer
import com.iacamara.app.capture.ShotInfo
import com.iacamara.app.capture.JpegFrameSource
import com.iacamara.app.capture.PhotoSaver
import com.iacamara.app.capture.captureJpeg
import com.iacamara.app.capture.rotated
import com.iacamara.app.director.CapturePlan
import com.iacamara.app.director.CaptureStrategy
import com.iacamara.app.imaging.BurstPipeline
import com.iacamara.app.imaging.FaceMerge
import com.iacamara.app.imaging.ImageStats
import com.iacamara.app.imaging.PixelRect
import com.iacamara.app.perception.FaceDetail
import com.iacamara.app.perception.FrameMath
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.CaptureMetadata
import com.iacamara.app.settings.AppPrefs
import com.iacamara.app.settings.EnhanceMode
import kotlinx.coroutines.asCoroutineDispatcher
import com.iacamara.app.util.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Lens { BACK, FRONT }

/**
 * Modos de foto.
 * - Propios de la app (vendor = false): FOTO, NOCHE y HDR con la fotografía computacional de la app, y PRO.
 * - Del fabricante (vendor = true): usan las extensiones de Samsung/Honor cuando el móvil las ofrece.
 */
enum class PhotoMode(val label: String, val extensionMode: Int, val vendor: Boolean) {
    FOTO("FOTO", ExtensionMode.NONE, false),

    /** Noche propia: ráfaga alineada y apilada (Fase 4). */
    NOCHE_IA("NOCHE", ExtensionMode.NONE, false),

    /** HDR propio: horquillado de exposiciones fusionado (Fase 4). */
    HDR_IA("HDR", ExtensionMode.NONE, false),

    /** Quitar gente que pasa: ráfaga de ~5 s y mediana (Fase 5). */
    SIN_GENTE("SIN GENTE", ExtensionMode.NONE, false),

    /** Larga exposición sin trípode: agua sedosa o estelas de luz (Fase 5). */
    LARGA_EXP("LARGA EXP.", ExtensionMode.NONE, false),

    /** Procesado automático del fabricante (en el S22+, el de Samsung). */
    AUTO("AUTO", ExtensionMode.AUTO, true),
    RETRATO("RETRATO", ExtensionMode.BOKEH, true),
    NOCHE("NOCHE+", ExtensionMode.NIGHT, true),
    HDR("HDR+", ExtensionMode.HDR, true),

    /** Manual: ISO y velocidad de obturación elegidas por el usuario. */
    PRO("PRO", ExtensionMode.NONE, false),
}

/** Límites del sensor para el modo PRO. */
data class ProRanges(val isoMin: Int, val isoMax: Int, val exposureMinNs: Long, val exposureMaxNs: Long)

/** Ajustes manuales del modo PRO y la exposición que había elegido la IA al entrar (para comparar). */
data class ProSettings(val iso: Int, val exposureNs: Long, val aperture: Float, val referenceEv: Float?)

/** Estado de una foto computacional en curso. */
data class Processing(val label: String, val progress: Float, val capturingFrames: Boolean)

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
    /** Foto computacional en curso (ráfaga, noche, HDR) o null. */
    val processing: Processing? = null,
    val proRanges: ProRanges? = null,
    val pro: ProSettings? = null,
    /** Fotos que se están mejorando en segundo plano. */
    val enhancing: Int = 0,
    /** En PRO se guarda también el RAW (DNG). */
    val rawActive: Boolean = false,
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

    /** Corrutinas de la captura computacional (se cancelan al cerrar la cámara). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Caras de una foto reducida y derecha con sus ojos abiertos o cerrados (coordenadas 0..1).
     * Lo proporciona la percepción (Face Landmarker).
     */
    var faceStates: ((Bitmap) -> List<FaceDetail>?)? = null
    /** Nota de estética (NIMA, 1–10) de una foto reducida y derecha, para la ráfaga. */
    var aestheticScorer: ((Bitmap) -> Float?)? = null
    private var analyzer: ImageAnalysis.Analyzer? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /** Mejora automática (Fase 5): una foto cada vez, en segundo plano. */
    val enhancer = PhotoEnhancer(context)
    private val prefs = AppPrefs(context)
    private val enhanceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

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
        scope.cancel()
        analysisExecutor.shutdown()
        enhanceDispatcher.close()
    }

    private fun baseSelector(lens: Lens): CameraSelector =
        if (lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun availableModes(lens: Lens): List<PhotoMode> {
        val ext = extensions ?: return PhotoMode.entries.filter { !it.vendor }
        val base = baseSelector(lens)
        return PhotoMode.entries.filter { mode ->
            !mode.vendor || runCatching { ext.isExtensionAvailable(base, mode.extensionMode) }.getOrDefault(false)
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

    private fun buildCapture(flash: FlashSetting, raw: Boolean = false): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setJpegQuality(JPEG_QUALITY)
        .setFlashMode(flash.cameraXMode)
        .setTargetRotation(targetRotation)
        .setResolutionSelector(captureResolution())
        .apply { if (raw) setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
        .build()

    /** ¿La cámara puede entregar RAW + JPEG a la vez? */
    private fun rawSupported(provider: ProcessCameraProvider, selector: CameraSelector): Boolean = runCatching {
        val info = provider.getCameraInfo(selector)
        ImageCapture.OUTPUT_FORMAT_RAW_JPEG in ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
    }.getOrDefault(false)

    private fun bind() {
        val cameraProvider = provider ?: return
        val owner = lifecycleOwner ?: return
        val sp = surfaceProvider ?: return
        val current = _state.value

        val modes = availableModes(current.lens)
        val mode = if (current.mode in modes) current.mode else PhotoMode.FOTO
        val base = baseSelector(current.lens)
        val ext = extensions
        val selector = if (!mode.vendor || ext == null) {
            base
        } else {
            ext.getExtensionEnabledCameraSelector(base, mode.extensionMode)
        }

        val previewBuilder = Preview.Builder().setResolutionSelector(previewResolution())
        if (!mode.vendor) {
            // Lee ISO, exposición y distancia de enfoque de cada fotograma (la IA las usa para la luz).
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureMetadata.callback)
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(sp)
        captureMetadata.clear()

        val analysisAllowed = !mode.vendor || ext == null ||
            runCatching { ext.isImageAnalysisSupported(base, mode.extensionMode) }.getOrDefault(false)
        val analysis = analyzer?.takeIf { analysisAllowed }?.let { buildAnalysis(it) }
        val wantRaw = mode == PhotoMode.PRO && prefs.raw && rawSupported(cameraProvider, selector)

        // Se prueba de la combinación más completa a la más sencilla: con RAW (si se pidió), con análisis
        // de la IA, y si el móvil no acepta tantas salidas a la vez, sin ellas.
        val attempts = buildList {
            if (wantRaw) {
                add(true to true)
                add(true to false)
            }
            add(false to true)
            add(false to false)
        }.filter { (_, withAnalysis) -> !withAnalysis || analysis != null }
        cameraProvider.unbindAll()
        var boundCamera: Camera? = null
        var capture: ImageCapture? = null
        var analysisActive = false
        var rawActive = false
        var lastError: Exception? = null
        for ((raw, withAnalysis) in attempts) {
            val candidate = buildCapture(current.flash, raw)
            try {
                boundCamera = if (withAnalysis && analysis != null) {
                    cameraProvider.bindToLifecycle(owner, selector, preview, candidate, analysis)
                } else {
                    cameraProvider.bindToLifecycle(owner, selector, preview, candidate)
                }
                capture = candidate
                analysisActive = withAnalysis
                rawActive = raw
                break
            } catch (e: Exception) {
                lastError = e
                cameraProvider.unbindAll()
            }
        }
        if (boundCamera == null || capture == null) {
            _state.update { it.copy(ready = false, message = "No se pudo abrir la cámara: ${lastError?.message}") }
            return
        }

        camera = boundCamera
        imageCapture = capture
        targetZoom = 1f
        observeZoom(boundCamera, owner)

        val size = capture.resolutionInfo?.resolution
        val exposure = boundCamera.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        val evSupported = exposure.isExposureCompensationSupported && step > 0f
        val proRanges = if (mode == PhotoMode.PRO) readProRanges(boundCamera) else null
        if (mode != PhotoMode.PRO) {
            runCatching { Camera2CameraControl.from(boundCamera.cameraControl).clearCaptureRequestOptions() }
        }
        _state.update {
            it.copy(
                ready = true,
                mode = mode,
                availableModes = modes,
                proRanges = proRanges,
                pro = if (mode == PhotoMode.PRO) it.pro else null,
                hasFlash = boundCamera.cameraInfo.hasFlashUnit(),
                captureMegapixels = size?.let { s -> s.width * s.height / 1_000_000f },
                analysisActive = analysisActive,
                rawActive = rawActive,
                exposureEv = 0f,
                minEv = if (evSupported) exposure.exposureCompensationRange.lower * step else 0f,
                maxEv = if (evSupported) exposure.exposureCompensationRange.upper * step else 0f,
                bindGeneration = it.bindGeneration + 1,
            )
        }
        if (mode == PhotoMode.PRO && proRanges != null) startPro(proRanges)
    }

    // --- Modo PRO ---

    private fun readProRanges(cam: Camera): ProRanges? = runCatching {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return@runCatching null
        val exposure = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return@runCatching null
        ProRanges(iso.lower, iso.upper, exposure.lower, exposure.upper)
    }.getOrNull()

    /** Al entrar en PRO se parte de la exposición que estaba usando la IA. */
    private fun startPro(ranges: ProRanges) {
        val meta = lastAutoMeta
        val iso = (meta?.iso ?: 100).coerceIn(ranges.isoMin, ranges.isoMax)
        val exposure = (meta?.exposureNs ?: 16_666_666L).coerceIn(ranges.exposureMinNs, ranges.exposureMaxNs)
        val settings = ProSettings(iso, exposure, meta?.aperture ?: 1.8f, meta?.ev100)
        _state.update { it.copy(pro = settings) }
        applyPro(settings)
    }

    fun setProExposure(iso: Int, exposureNs: Long) {
        val current = _state.value.pro ?: return
        val ranges = _state.value.proRanges ?: return
        val settings = current.copy(
            iso = iso.coerceIn(ranges.isoMin, ranges.isoMax),
            exposureNs = exposureNs.coerceIn(ranges.exposureMinNs, ranges.exposureMaxNs),
        )
        _state.update { it.copy(pro = settings) }
        applyPro(settings)
    }

    private fun applyPro(settings: ProSettings) {
        val cam = camera ?: return
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureNs)
            .build()
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options) }
    }

    /** Última exposición automática medida (para empezar el modo PRO desde ahí). */
    private var lastAutoMeta: com.iacamara.app.perception.CaptureMeta? = null

    fun rememberAutoExposure() {
        if (_state.value.mode != PhotoMode.PRO) captureMetadata.current()?.let { lastAutoMeta = it }
    }

    // --- Fotografía computacional (Fase 4) ---

    /**
     * Dispara con la técnica que ha decidido el Director IA.
     * @param shot lo que sabía la cámara al disparar, para la mejora automática (null = sin mejora).
     */
    fun capture(plan: CapturePlan, shot: ShotInfo? = null) {
        if (plan.strategy == CaptureStrategy.SINGLE) {
            takePhoto(shot)
            return
        }
        if (_state.value.capturing) return
        scope.launch { runComputational(plan, shot) }
    }

    /**
     * Crea la versión mejorada de una foto recién guardada (en segundo plano). Solo en los modos de la
     * app: los del fabricante ya vienen procesados y en PRO manda el usuario.
     */
    private fun enhanceLater(original: Uri, shot: ShotInfo?, alreadyProcessed: Boolean, mode: PhotoMode) {
        val choice = prefs.enhance
        if (shot == null || choice == EnhanceMode.NO || mode.vendor || mode == PhotoMode.PRO) return
        val geometry = prefs.enhanceGeometry
        _state.update { it.copy(enhancing = it.enhancing + 1) }
        scope.launch {
            try {
                val improved = withContext(enhanceDispatcher) {
                    runCatching { enhancer.enhance(original, shot, alreadyProcessed, geometry) }.getOrNull()
                }
                if (improved != null) {
                    if (choice == EnhanceMode.SOLO_MEJORADA) withContext(Dispatchers.IO) { PhotoSaver.delete(context, original) }
                    _state.update { it.copy(lastPhotoUri = improved) }
                }
            } finally {
                _state.update { it.copy(enhancing = (it.enhancing - 1).coerceAtLeast(0)) }
            }
        }
    }

    private suspend fun runComputational(plan: CapturePlan, shot: ShotInfo?) {
        val mode = _state.value.mode
        val capture = imageCapture ?: return
        _state.update { it.copy(capturing = true, processing = Processing(plan.description, 0f, capturingFrames = true)) }
        try {
            val frames = mutableListOf<CapturedFrame>()
            var evs = FloatArray(0)
            when (plan.strategy) {
                CaptureStrategy.HDR -> {
                    val bracket = captureBracket(capture)
                    bracket.forEach { frames += it.first }
                    evs = FloatArray(bracket.size) { bracket[it].second }
                }
                else -> repeat(plan.frames) {
                    if (it > 0 && plan.intervalMs > 0) delay(plan.intervalMs)
                    frames += capture.captureJpeg(mainExecutor)
                    updateProgress((it + 1f) / plan.frames * 0.3f, capturingFrames = true)
                }
            }
            updateProgress(0.3f, capturingFrames = false)
            val uri = withContext(Dispatchers.Default) {
                when (plan.strategy) {
                    CaptureStrategy.BEST_OF_BURST -> saveBest(frames)
                    CaptureStrategy.NIGHT -> mergeAndSave(frames, plan.description) { sources, report ->
                        BurstPipeline.night(sources, report)
                    }
                    CaptureStrategy.HDR -> mergeAndSave(frames, plan.description) { sources, report ->
                        BurstPipeline.hdr(sources, evs, report)
                    }
                    CaptureStrategy.REMOVE_MOVING -> mergeAndSave(frames, plan.description) { sources, report ->
                        BurstPipeline.removeMoving(sources, report)
                    }
                    CaptureStrategy.LONG_EXPOSURE -> mergeAndSave(frames, plan.description) { sources, report ->
                        BurstPipeline.longExposure(sources, plan.lighten, report)
                    }
                    CaptureStrategy.ZOOM_DETAIL -> mergeAndSave(frames, plan.description) { sources, report ->
                        BurstPipeline.zoomDetail(sources, report)
                    }
                    CaptureStrategy.SINGLE -> null
                }
            }
            _state.update { it.copy(capturing = false, processing = null, lastPhotoUri = uri ?: it.lastPhotoUri) }
            if (uri != null) {
                val fused = plan.strategy != CaptureStrategy.BEST_OF_BURST
                enhanceLater(uri, shot, alreadyProcessed = fused, mode = mode)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(capturing = false, processing = null, message = "No se pudo procesar la foto: ${e.message}") }
        }
    }

    private fun updateProgress(progress: Float, capturingFrames: Boolean) {
        _state.update { s -> s.copy(processing = s.processing?.copy(progress = progress, capturingFrames = capturingFrames)) }
    }

    /** Horquillado: −2, 0 y +2 EV (o lo que permita la cámara). Devuelve cada foto con su EV real. */
    private suspend fun captureBracket(capture: ImageCapture): List<Pair<CapturedFrame, Float>> {
        val cam = camera ?: error("cámara cerrada")
        val exposure = cam.cameraInfo.exposureState
        val step = exposure.exposureCompensationStep.toFloat()
        if (!exposure.isExposureCompensationSupported || step <= 0f) {
            return listOf(capture.captureJpeg(mainExecutor) to 0f)
        }
        val original = exposure.exposureCompensationIndex
        val range = exposure.exposureCompensationRange
        val result = mutableListOf<Pair<CapturedFrame, Float>>()
        try {
            for ((i, ev) in listOf(-2f, 0f, 2f).withIndex()) {
                val index = (ev / step).roundToInt().coerceIn(range.lower, range.upper)
                runCatching { cam.cameraControl.setExposureCompensationIndex(index).await() }
                delay(BRACKET_SETTLE_MS)
                result += capture.captureJpeg(mainExecutor) to index * step
                updateProgress((i + 1) / 3f * 0.3f, capturingFrames = true)
            }
        } finally {
            runCatching { cam.cameraControl.setExposureCompensationIndex(original) }
        }
        // La exposición real de cada foto (EXIF: tiempo × ISO) manda sobre la pedida: si la cámara no
        // llegó a aplicar del todo la compensación, la fusión usa lo que de verdad se captó.
        val light = result.map { it.first.exposureProduct }
        val middle = light.getOrNull(1)
        if (result.size == 3 && middle != null && light.all { it != null }) {
            return result.mapIndexed { i, (frame, _) -> frame to log2(light[i]!! / middle).toFloat() }
        }
        return result
    }

    /**
     * Ráfaga: la foto más nítida y, si hay personas, con los ojos abiertos. En fotos de grupo, si alguien
     * parpadeó en esa foto, se le pone su cara de otra foto de la ráfaga ("todos con los ojos abiertos").
     */
    private fun saveBest(frames: List<CapturedFrame>): Uri? {
        val sources = frames.map { JpegFrameSource(it.jpeg) }
        try {
            val faceFn = faceStates
            val beauty = aestheticScorer
            val faces = arrayOfNulls<List<FaceDetail>>(frames.size)
            val eyes = FloatArray(frames.size)
            val aesthetic = arrayOfNulls<Float>(frames.size)
            for (i in frames.indices) {
                if (faceFn == null && beauty == null) break
                runCatching {
                    // Media resolución: en fotos de grupo las caras son pequeñas.
                    val raw = sources[i].decodeSmall(2)
                    val small = raw.rotated(frames[i].rotationDegrees)
                    try {
                        val found = faceFn?.invoke(small)
                        faces[i] = found
                        if (!found.isNullOrEmpty()) eyes[i] = found.count { it.eyesOpen } / found.size.toFloat()
                        aesthetic[i] = beauty?.invoke(small)
                    } finally {
                        if (small !== raw) small.recycle()
                        raw.recycle()
                    }
                }
            }
            // Nitidez primero, luego ojos abiertos; la estética (NIMA) desempata entre fotos parecidas.
            val notes = aesthetic.filterNotNull()
            val low = notes.minOrNull() ?: 0f
            val range = maxOf(0.3f, (notes.maxOrNull() ?: 0f) - low)
            val best = BurstPipeline.bestFrame(sources) { i ->
                eyes[i] * 2f + (aesthetic[i]?.let { (it - low) / range * 0.3f } ?: 0f)
            }
            runCatching { fixClosedEyes(frames, sources, faces, best) }.getOrNull()?.let { return it }
            return PhotoSaver.saveJpeg(context, frames[best].jpeg, frames[best].rotationDegrees)
        } finally {
            sources.forEach { it.close() }
        }
    }

    /**
     * Fotos de grupo: cada cara con los ojos cerrados en la foto elegida se sustituye por la misma cara
     * de otra foto de la ráfaga en la que los tenía abiertos (la más nítida). Null si no hace falta.
     */
    private fun fixClosedEyes(
        frames: List<CapturedFrame>,
        sources: List<JpegFrameSource>,
        faces: Array<List<FaceDetail>?>,
        best: Int,
    ): Uri? {
        val baseFaces = faces[best] ?: return null
        if (baseFaces.size < 2) return null
        val closed = baseFaces.filter { !it.eyesOpen }
        if (closed.isEmpty()) return null
        val base = sources[best]
        val w = base.width
        val h = base.height
        val rotation = frames[best].rotationDegrees
        val sharpness = sources.map { ImageStats.sharpness(it.luma(4)) }
        var px: IntArray? = null
        var fixed = 0
        for (face in closed) {
            val donor = frames.indices
                .filter { j -> j != best && faces[j]?.any { it.eyesOpen && it.box.iou(face.box) >= 0.35f } == true }
                .maxByOrNull { sharpness[it] } ?: continue
            val pixels = px ?: base.readRegion(0, 0, w, h).also { px = it }
            // Zona de la cara con algo de margen (pelo, barbilla), pasada de la foto derecha al sensor.
            val b = face.box
            val grown = NRect(
                b.left - b.width * 0.35f, b.top - b.height * 0.45f,
                b.right + b.width * 0.35f, b.bottom + b.height * 0.25f,
            ).clamped()
            val s = FrameMath.unrotate(grown, rotation)
            val region = PixelRect((s.left * w).toInt(), (s.top * h).toInt(), (s.right * w).toInt(), (s.bottom * h).toInt())
            FaceMerge.paste(pixels, w, h, sources[donor], region, maxShift = maxOf(16, region.width / 5))
            fixed++
        }
        val result = px ?: return null
        val bitmap = Bitmap.createBitmap(result, w, h, Bitmap.Config.ARGB_8888)
        return try {
            val what = if (fixed == 1) "1 cara" else "$fixed caras"
            PhotoSaver.saveProcessed(context, bitmap, rotation, frames[best].jpeg, "Todos con los ojos abiertos ($what)")
        } finally {
            bitmap.recycle()
        }
    }

    private fun mergeAndSave(
        frames: List<CapturedFrame>,
        description: String,
        merge: (List<JpegFrameSource>, (Float) -> Unit) -> BurstPipeline.Merged,
    ): Uri? {
        val sources = frames.map { JpegFrameSource(it.jpeg) }
        try {
            val merged = merge(sources) { p -> updateProgress(0.3f + 0.6f * p, capturingFrames = false) }
            val bitmap = Bitmap.createBitmap(merged.pixels, merged.width, merged.height, Bitmap.Config.ARGB_8888)
            val reference = frames[merged.reference]
            val uri = PhotoSaver.saveProcessed(context, bitmap, reference.rotationDegrees, reference.jpeg, description)
            bitmap.recycle()
            return uri
        } finally {
            sources.forEach { it.close() }
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

    fun takePhoto(shot: ShotInfo? = null) {
        val capture = imageCapture ?: return
        if (_state.value.capturing) return
        val mode = _state.value.mode
        val raw = _state.value.rawActive
        val token = ++shotToken
        _state.update { it.copy(capturing = true) }

        val name = "IA_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        fun options(fileName: String, mime: String) = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
            },
        ).build()
        val jpegOptions = options(name, "image/jpeg")
        var pending = if (raw) 2 else 1
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                pending--
                val isRaw = raw && output.imageFormat == ImageFormat.RAW_SENSOR
                if (!isRaw) {
                    _state.update { it.copy(lastPhotoUri = output.savedUri ?: it.lastPhotoUri) }
                    output.savedUri?.let { enhanceLater(it, shot, alreadyProcessed = false, mode = mode) }
                }
                if (pending <= 0 && token == shotToken) _state.update { it.copy(capturing = false) }
            }

            override fun onError(exception: ImageCaptureException) {
                _state.update { it.copy(capturing = false, message = "No se pudo guardar la foto: ${exception.message}") }
            }
        }
        if (raw) {
            capture.takePicture(options(name + "_raw", "image/x-adobe-dng"), jpegOptions, mainExecutor, callback)
            // Por si el sistema solo avisa de una de las dos fotos: el disparador no se queda bloqueado.
            scope.launch {
                delay(RAW_RELEASE_MS)
                if (token == shotToken) _state.update { it.copy(capturing = false) }
            }
        } else {
            capture.takePicture(jpegOptions, mainExecutor, callback)
        }
    }

    private var shotToken = 0

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

        /** Tiempo para que la exposición se asiente entre fotos del horquillado. */
        private const val BRACKET_SETTLE_MS = 300L

        /** Tiempo máximo que el disparador espera a que se guarden el RAW y el JPEG. */
        private const val RAW_RELEASE_MS = 6000L
    }
}
