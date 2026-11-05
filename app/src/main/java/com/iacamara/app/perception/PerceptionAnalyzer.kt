package com.iacamara.app.perception

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.iacamara.app.perception.onnx.AcceleratorStore
import com.iacamara.app.perception.onnx.Accelerator
import com.iacamara.app.perception.onnx.AestheticScorer
import com.iacamara.app.perception.onnx.Backend
import com.iacamara.app.perception.onnx.ClipSceneClassifier
import com.iacamara.app.perception.onnx.OnnxModel
import com.iacamara.app.perception.onnx.OutputCheck
import com.iacamara.app.perception.onnx.SaliencyDetector
import com.iacamara.app.perception.onnx.YoloDetector
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Capa 1 del plan: percepción en vivo, con varios modelos que van dentro del APK (sin internet):
 *
 * - Caras (MediaPipe BlazeFace): en cada fotograma.
 * - Ojos y mirada (MediaPipe Face Landmarker): ~4 veces por segundo si hay caras.
 * - Cuerpo (MediaPipe Pose, 33 puntos): ~4 veces por segundo si hay personas.
 * - Tipo de escena (CLIP pequeño, descripciones de 14 tipos de escena): ~2 veces por segundo.
 * - Estética (NIMA, nota 1–10): ~1 vez por segundo.
 * - Objetos (YOLO11n; YOLO11s si el móvil acelera la IA): ~5 veces por segundo.
 * - Vocabulario abierto (YOLOE con ~120 cosas habituales en fotos): ~1 vez por segundo; da nombre
 *   a lo que YOLO11 no conoce y anula sus confusiones.
 * - Saliencia (U²-Net): ~1–2 veces por segundo; encuentra "lo importante" aunque no tenga nombre.
 * - Estructura (sin modelo): ~3 veces por segundo; líneas guía, punto de fuga, simetría y reflejos
 *   para elegir la guía de composición.
 *
 * Cada modelo se espacia solo según lo que tarda en este móvil y si se calienta, y en cada fotograma
 * se ejecuta como mucho un modelo pesado, para que el visor vaya fluido.
 *
 * Aceleración: los modelos ONNX empiezan en el procesador y el [Accelerator] prueba en segundo plano
 * el NPU y la GPU del Snapdragon; los de MediaPipe intentan la GPU. Si algo falla, procesador normal.
 */
class PerceptionAnalyzer(
    context: Context,
    private val captureMetadata: CaptureMetadata,
    private val motion: MotionSensor,
) : ImageAnalysis.Analyzer {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private val _perception = MutableStateFlow<Perception?>(null)
    val perception: StateFlow<Perception?> = _perception.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile var enabled: Boolean = true
    @Volatile var frontCamera: Boolean = false
    @Volatile var deviceDegrees: Int = 0
    @Volatile var zoomRatio: Float = 1f

    // Aceleración (NPU/GPU) con vuelta al procesador normal.
    private val acceleratorStore = AcceleratorStore.get(appContext)
    private val accelerator = Accelerator(acceleratorStore)
    @Volatile private var foreground = false
    @Volatile private var closedModels = false

    // MediaPipe
    private var faceDetector: FaceDetector? = null
    private val faceLandmarker = MpModel("ojos", acceleratorStore) { delegate ->
        FaceLandmarker.createFromOptions(
            appContext,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base(LANDMARKS_MODEL, delegate))
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(5)
                .setOutputFaceBlendshapes(true)
                .setMinFaceDetectionConfidence(0.5f)
                .build(),
        )
    }
    private val poseLandmarker = MpModel("pose", acceleratorStore) { delegate ->
        PoseLandmarker.createFromOptions(
            appContext,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base(POSE_MODEL, delegate))
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .build(),
        )
    }

    // ONNX Runtime
    private val onnxModels = CopyOnWriteArrayList<OnnxModel>()
    @Volatile private var yolo: YoloDetector? = null
    @Volatile private var yoloModel: OnnxModel? = null
    @Volatile private var yoloName = "YOLO11n"
    private var yoloLabels: List<String> = emptyList()
    private var yoloe: YoloDetector? = null
    private var yoloeModel: OnnxModel? = null
    private var saliencyDetector: SaliencyDetector? = null
    private var saliencyModel: OnnxModel? = null
    private var clip: ClipSceneClassifier? = null
    private var clipModel: OnnxModel? = null
    private var aestheticScorer: AestheticScorer? = null
    private var aestheticModel: OnnxModel? = null
    private var yoloeSpanish: Map<String, String> = emptyMap()
    private var initialized = false

    private val lightAnalyzer = LightAnalyzer()
    private val sceneStabilizer = SceneStabilizer()
    private val conditionStabilizer = ConditionStabilizer()
    private val objectTracker = ObjectTracker()
    private val structureStabilizer = StructureStabilizer()

    private val labelsClock = TaskClock(450, 3f)
    private val landmarksClock = TaskClock(250, 3f)
    private val poseClock = TaskClock(250, 3f)
    private val aestheticClock = TaskClock(1000, 6f)
    private val yoloClock = TaskClock(180, 2.5f)
    private val yoloeClock = TaskClock(900, 4f)
    private val saliencyClock = TaskClock(600, 4f)
    private val structureClock = TaskClock(330, 4f)

    private var lastFront = false
    private var lastDegrees = 0
    private var lastFrameMs = 0L
    private var lastThermalCheckMs = 0L
    private var slowdown = 1L

    // Últimos resultados (coordenadas del fotograma del modelo, M).
    private var lastObjects: List<DetectedObject> = emptyList()
    private var lastOpenVocab: List<DetectedObject> = emptyList()
    private var lastSceneScores: Map<SceneType, Float> = emptyMap()
    private var lastPose: BodyPose? = null
    private var lastAesthetic: Float? = null
    private var animalEvidence = true
    private var lastSaliencyGrid: FloatArray? = null
    private var lastSaliencyW = 0
    private var lastSaliencyH = 0
    private var lastFaceDetails: List<FaceDetail> = emptyList()
    private var lastSubjectCenter: Pair<Float, Float>? = null
    private var lastSubjectMs = 0L
    private var subjectSpeed = 0f
    private var lastStructure: SceneStructure? = null
    private var structurePixels = IntArray(0)

    private fun ensureModels() {
        if (initialized) return
        initialized = true
        val failures = mutableListOf<String>()
        faceDetector = load("caras", failures) {
            FaceDetector.createFromOptions(
                appContext,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(base(FACE_MODEL, Delegate.CPU))
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.5f)
                    .build(),
            )
        }
        load("ojos", failures) { faceLandmarker.load() }
        load("cuerpo", failures) { poseLandmarker.load() }
        yolo = load("objetos", failures) {
            yoloLabels = readLines(YOLO_LABELS)
            val model = onnx(YOLO_MODEL, "yolo11n")
            yoloModel = model
            YoloDetector(model, 512, yoloLabels)
        }
        yoloe = load("vocabulario abierto", failures) {
            val rows = readLines(YOLOE_LABELS).map { it.split('\t') }
            yoloeSpanish = rows.associate { cols -> cols[0] to (cols.getOrNull(1) ?: cols[0]) }
            val model = onnx(YOLOE_MODEL, "yoloe")
            yoloeModel = model
            YoloDetector(model, 480, rows.map { cols -> cols[0] })
        }
        saliencyDetector = load("sujeto principal", failures) {
            onnx(SALIENCY_MODEL, "u2netp").let { saliencyModel = it; SaliencyDetector(it) }
        }
        clip = load("escenas", failures) {
            onnx(CLIP_MODEL, "clip").let { clipModel = it; ClipSceneClassifier.load(appContext, it, CLIP_TEXT, CLIP_NAMES) }
        }
        aestheticScorer = load("estética", failures) {
            onnx(NIMA_MODEL, "nima").let { aestheticModel = it; AestheticScorer(it) }
        }
        if (failures.isNotEmpty()) _error.value = "IA incompleta · " + failures.joinToString(" · ")
        updateActive()
        startAcceleration()
    }

    /**
     * Prueba en segundo plano el NPU y la GPU con cada modelo (o activa lo que ya funcionó antes).
     * Primero los más lentos en el procesador, que son los que más ganan.
     */
    private fun startAcceleration() {
        val detections = { a: Any, b: Any -> OutputCheck.sameDetections(a, b) }
        val hasDetections = { out: Any -> OutputCheck.hasDetections(out) }
        saliencyModel?.let {
            accelerator.optimize(Accelerator.Job(it, { a, b -> OutputCheck.similar(a, b, 0.95f) }) { _, _ -> updateActive() })
        }
        yoloeModel?.let {
            accelerator.optimize(Accelerator.Job(it, detections, informative = hasDetections) { _, _ -> updateActive() })
        }
        yoloModel?.let { model ->
            accelerator.optimize(
                Accelerator.Job(model, detections, informative = hasDetections) { backend, _ ->
                    updateActive()
                    if (backend != Backend.CPU) tryBiggerDetector(model)
                },
            )
        }
        aestheticModel?.let {
            accelerator.optimize(Accelerator.Job(it, { a, b -> OutputCheck.sameMeanScore(a, b) }) { _, _ -> updateActive() })
        }
        clipModel?.let {
            accelerator.optimize(Accelerator.Job(it, { a, b -> OutputCheck.similar(a, b, 0.98f) }) { _, _ -> updateActive() })
        }
    }

    /**
     * Si el móvil acelera la IA, prueba YOLO11s (más preciso que YOLO11n). Solo se queda si va en el
     * NPU o la GPU, da los mismos resultados que en el procesador y tarda como mucho 40 ms.
     */
    private fun tryBiggerDetector(small: OnnxModel) {
        if (acceleratorStore.chosen(YOLO_S_KEY) == Backend.CPU) return
        val big = runCatching { OnnxModel(appContext, YOLO_S_MODEL, YOLO_S_KEY) }.getOrNull() ?: return
        accelerator.optimize(
            Accelerator.Job(
                model = big,
                check = { a, b -> OutputCheck.sameDetections(a, b) },
                informative = { out -> OutputCheck.hasDetections(out) },
                accept = { backend, ms, _ -> backend != Backend.CPU && ms <= 40f },
                sampleFrom = small,
            ) { backend, _ -> switchDetector(small, big, backend) },
        )
    }

    /** Cambia a YOLO11s entre dos fotogramas (o lo descarta). Siempre se cierra el que sobra. */
    @Synchronized
    private fun switchDetector(small: OnnxModel, big: OnnxModel, backend: Backend) {
        if (backend == Backend.CPU || closedModels) {
            big.close()
            return
        }
        onnxModels += big
        yolo = YoloDetector(big, 512, yoloLabels)
        yoloModel = big
        yoloName = "YOLO11s"
        onnxModels -= small
        small.close()
        updateActive()
    }

    /** Hay algún modelo acelerado con la cámara a la vista (para la protección contra cierres). */
    private fun updateActive() {
        val accelerated = onnxModels.any { it.backend != Backend.CPU } || faceLandmarker.onGpu || poseLandmarker.onGpu
        acceleratorStore.setActive(foreground && accelerated)
    }

    /** La cámara se ve (true) o la app pasa a segundo plano (false). */
    fun setForeground(value: Boolean) {
        foreground = value
        updateActive()
    }

    private fun onnx(path: String, key: String): OnnxModel = OnnxModel(appContext, path, key).also { onnxModels += it }

    private fun readLines(path: String): List<String> =
        appContext.assets.open(path).bufferedReader().readLines().filter { it.isNotBlank() }

    private fun <T> load(name: String, failures: MutableList<String>, create: () -> T): T? = try {
        create()
    } catch (e: Throwable) {
        Log.e(TAG, "No se pudo cargar el modelo de $name", e)
        // El error útil suele estar en la causa más interna (p. ej. dentro de ExceptionInInitializerError).
        val root = generateSequence(e) { it.cause }.last()
        failures += "$name: ${root.javaClass.simpleName} ${root.message ?: ""}".trim()
        null
    }

    private fun base(path: String, delegate: Delegate): BaseOptions =
        BaseOptions.builder().setModelAssetPath(path).setDelegate(delegate).build()

    @Synchronized
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!enabled || now - lastFrameMs < MIN_FRAME_INTERVAL_MS * slowdown) {
            image.close()
            return
        }
        lastFrameMs = now
        try {
            ensureModels()
            checkThermal(now)
            val front = frontCamera
            if (front != lastFront || deviceDegrees != lastDegrees) {
                // Al cambiar de cámara o de orientación, los resultados anteriores ya no valen.
                resetResults()
                lastFront = front
                lastDegrees = deviceDegrees
            }
            val modelRotation = FrameMath.modelRotation(deviceDegrees, front)
            val totalRotation = (image.imageInfo.rotationDegrees + modelRotation) % 360
            val raw = image.toBitmap()
            image.close()
            val bitmap = rotate(raw, totalRotation)
            process(bitmap, modelRotation, front, now)
        } catch (e: Throwable) {
            Log.w(TAG, "Fallo al analizar el fotograma", e)
            runCatching { image.close() }
        }
    }

    private fun resetResults() {
        objectTracker.clear()
        lastObjects = emptyList()
        lastOpenVocab = emptyList()
        lastSaliencyGrid = null
        lastFaceDetails = emptyList()
        lastPose = null
        structureStabilizer.clear()
        lastStructure = null
        listOf(yoloClock, yoloeClock, saliencyClock, landmarksClock, structureClock, poseClock).forEach { it.reset() }
    }

    private fun process(bitmap: Bitmap, modelRotation: Int, front: Boolean, now: Long) {
        val start = SystemClock.elapsedRealtime()
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Caras: en cada fotograma (muy rápido).
        val faces = faceDetector?.detect(mpImage)?.detections().orEmpty()
            .map { normalize(it.boundingBox(), w, h) }
        // Mientras se mide la velocidad de un acelerador, el resto de modelos espera (comparación justa).
        val paused = accelerator.measuring

        // Tareas medianas: escena (CLIP) o estética (NIMA), la que más atrasada vaya.
        val medium = listOfNotNull(
            clip?.let { "escena" to labelsClock.overdue(now, slowdown) },
            aestheticScorer?.let { "estética" to aestheticClock.overdue(now, slowdown) },
        ).filter { it.second >= 1f && !paused }.maxByOrNull { it.second }?.first
        when (medium) {
            "escena" -> timed(labelsClock, now) { runClip(bitmap) }
            "estética" -> timed(aestheticClock, now) { runAesthetic(bitmap) }
        }
        if (faces.isNotEmpty() && !paused && landmarksClock.overdue(now, slowdown) >= 1f) {
            timed(landmarksClock, now) { runLandmarks(mpImage) }
        } else if (faces.isEmpty()) {
            lastFaceDetails = emptyList()
        }
        // Cuerpo: solo si hay personas a la vista.
        val people = faces.isNotEmpty() || lastObjects.any { it.label == "person" }
        if (people && !paused && poseClock.overdue(now, slowdown) >= 1f) {
            timed(poseClock, now) { runPose(mpImage) }
        } else if (!people) {
            lastPose = null
        }

        // Como mucho una tarea pesada por fotograma: la que más atrasada vaya.
        val heavy = listOfNotNull(
            yolo?.let { "yolo" to yoloClock.overdue(now, slowdown) },
            yoloe?.let { "yoloe" to yoloeClock.overdue(now, slowdown) },
            saliencyDetector?.let { "saliencia" to saliencyClock.overdue(now, slowdown) },
        ).filter { it.second >= 1f && !paused }.maxByOrNull { it.second }?.first
        when (heavy) {
            "yolo" -> timed(yoloClock, now) { runYolo(bitmap) }
            "yoloe" -> timed(yoloeClock, now) { runOpenVocab(bitmap) }
            "saliencia" -> timed(saliencyClock, now) { runSaliency(bitmap) }
        }

        // Saliencia en el encuadre vertical (P) y región principal.
        val saliencyP = lastSaliencyGrid?.let {
            SaliencyMath.toPortraitGrid(it, lastSaliencyW, lastSaliencyH, modelRotation)
        }
        val salientModel = lastSaliencyGrid?.let { grid ->
            SaliencyMath.mainRegion(SaliencyMap(lastSaliencyW, lastSaliencyH, grid))
        }

        val subjectInModel = chooseSubject(faces, lastObjects, salientModel)
        if (!paused && structureClock.overdue(now, slowdown) >= 1f) {
            timed(structureClock, now) { runStructure(bitmap, subjectInModel?.box) }
        }
        val light = lightAnalyzer.analyze(bitmap, subjectInModel?.box)
        val meta = captureMetadata.current()

        val scene = sceneStabilizer.update(
            SceneRules.classify(SceneInputs(front, faces, lastObjects, lastSceneScores, light, meta)),
        )

        updateSubjectSpeed(subjectInModel, now)

        val ev = meta?.ev100
        val raw = buildSet {
            val backlit = light.subjectLuma < light.backgroundLuma * 0.55f &&
                light.backgroundLuma > 110f && light.highlightClip > 0.02f
            if (backlit) add(Condition.CONTRALUZ)
            if ((ev != null && ev < 5f) || light.meanLuma < 45f) add(Condition.POCA_LUZ)
            if (BlurRisk.isRisky(motion.shake, meta, zoomRatio)) add(Condition.TEMBLOR)
            if (subjectSpeed > MOVING_THRESHOLD) add(Condition.MOVIMIENTO)
            if (light.highlightClip > 0.08f && !backlit) add(Condition.LUCES_QUEMADAS)
        }
        val conditions = conditionStabilizer.update(raw)

        // Todo se devuelve en coordenadas del encuadre vertical de la pantalla (P).
        val toP = { r: NRect -> FrameMath.unrotate(r, modelRotation) }
        _perception.value = Perception(
            scene = scene,
            conditions = conditions,
            subject = subjectInModel?.copy(box = toP(subjectInModel.box)),
            faces = faces.map(toP),
            light = light,
            meta = meta,
            labels = lastSceneScores.entries.sortedByDescending { it.value }.take(3)
                .map { "${it.key.label} ${(it.value * 100).toInt()} %" },
            frameMs = SystemClock.elapsedRealtime() - start,
            timestampMs = now,
            saliency = saliencyP,
            faceDetails = lastFaceDetails.map { it.copy(box = toP(it.box)) },
            namedObjects = lastOpenVocab.map { it.copy(label = spanishOpen(it.label), box = toP(it.box)) },
            timings = mapOf(
                yoloLabel() to yoloClock.averageMs.toInt(),
                "YOLOE ${backendOf(yoloeModel)}" to yoloeClock.averageMs.toInt(),
                "Saliencia ${backendOf(saliencyModel)}" to saliencyClock.averageMs.toInt(),
                "Escena ${backendOf(clipModel)}" to labelsClock.averageMs.toInt(),
                "Estética ${backendOf(aestheticModel)}" to aestheticClock.averageMs.toInt(),
                "Ojos ${faceLandmarker.label}" to landmarksClock.averageMs.toInt(),
                "Cuerpo ${poseLandmarker.label}" to poseClock.averageMs.toInt(),
                "Líneas CPU" to structureClock.averageMs.toInt(),
            ),
            structure = lastStructure?.map { x, y -> FrameMath.unrotate(x, y, modelRotation) },
            pose = lastPose?.map { x, y -> FrameMath.unrotate(x, y, modelRotation) },
            sceneScores = lastSceneScores,
            aesthetic = lastAesthetic,
        )
    }

    private inline fun timed(clock: TaskClock, now: Long, block: () -> Unit) {
        val t = SystemClock.elapsedRealtime()
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "Fallo en un modelo", e)
        }
        clock.record(now, SystemClock.elapsedRealtime() - t)
    }

    private fun yoloLabel(): String {
        val testingBig = yoloName == "YOLO11n" && accelerator.probing?.startsWith("$YOLO_S_KEY ") == true
        return "$yoloName ${backendOf(yoloModel)}" + if (testingBig) " (probando YOLO11s…)" else ""
    }

    private fun backendOf(model: OnnxModel?): String {
        val m = model ?: return "—"
        val testing = accelerator.probing?.takeIf { it.startsWith(m.key + " ") }?.substringAfter(' ')
        return if (testing != null) "${m.backend.label} (probando $testing…)" else m.backend.label
    }

    private fun runClip(bitmap: Bitmap) {
        val scores = clip?.classify(bitmap) ?: return
        lastSceneScores = scores
        // CLIP también confirma si hay un animal (para descartar "animales" dudosos de YOLO11).
        // Umbral bajo: en un salón o un paisaje casi toda la probabilidad se la lleva la escena.
        animalEvidence = (scores[SceneType.MASCOTA] ?: 0f) >= 0.02f
    }

    private fun runAesthetic(bitmap: Bitmap) {
        val score = aestheticScorer?.score(bitmap) ?: return
        // Suavizada: la nota de un solo fotograma baila un poco.
        lastAesthetic = lastAesthetic?.let { it + 0.4f * (score - it) } ?: score
    }

    private fun runPose(mpImage: MPImage) {
        val result = poseLandmarker.run { it.detect(mpImage) } ?: return
        val points = result.landmarks().firstOrNull()
        if (points == null || points.size < BodyPose.COUNT) {
            lastPose = null
            return
        }
        lastPose = BodyPose(
            xs = FloatArray(BodyPose.COUNT) { points[it].x() },
            ys = FloatArray(BodyPose.COUNT) { points[it].y() },
            visibility = FloatArray(BodyPose.COUNT) { points[it].visibility().orElse(0f) },
        )
    }

    private fun runLandmarks(mpImage: MPImage) {
        val result = faceLandmarker.run { it.detect(mpImage) } ?: return
        val shapes = result.faceBlendshapes().orElse(null)
        lastFaceDetails = result.faceLandmarks().mapIndexedNotNull { k, points ->
            if (points.size < 2) return@mapIndexedNotNull null
            val minX = points.minOf { it.x() }
            val maxX = points.maxOf { it.x() }
            val minY = points.minOf { it.y() }
            val maxY = points.maxOf { it.y() }
            val face = shapes?.getOrNull(k)
            val blinkLeft = face?.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score() ?: 0f
            val blinkRight = face?.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
            // La punta de la nariz (punto 1) se desplaza hacia donde gira la cabeza.
            val centerX = (minX + maxX) / 2f
            val halfWidth = ((maxX - minX) / 2f).coerceAtLeast(1e-3f)
            val gaze = ((points[1].x() - centerX) / halfWidth * 2f).coerceIn(-1f, 1f)
            FaceDetail(
                box = NRect(minX, minY, maxX, maxY).clamped(),
                eyesOpen = blinkLeft < EYE_CLOSED && blinkRight < EYE_CLOSED,
                gazeX = gaze,
            )
        }
    }

    private fun runYolo(bitmap: Bitmap) {
        val detector = yolo ?: return
        val raw = detector.detect(bitmap, minScore = 0.3f)
        // Veto: si el detector de vocabulario abierto ve otra cosa (no animal) en el mismo sitio,
        // un "animal" dudoso de YOLO11 se descarta (la "jirafa" de hierro).
        val filtered = raw.filter { d ->
            // El detector de vocabulario abierto también vale como confirmación de un animal.
            val evidence = animalEvidence || lastOpenVocab.any { o ->
                o.label in OPEN_VOCAB_ANIMALS && o.label !in HUMANS && o.score >= 0.3f && o.box.iou(d.box) >= 0.3f
            }
            if (!DetectionFilter.accept(d, evidence)) return@filter false
            if (d.label !in SceneRules.ANIMALS || d.score >= 0.8f) return@filter true
            lastOpenVocab.none { o -> o.label !in OPEN_VOCAB_ANIMALS && o.score >= 0.35f && o.box.iou(d.box) >= 0.4f }
        }
        lastObjects = objectTracker.update(filtered)
    }

    private fun runOpenVocab(bitmap: Bitmap) {
        lastOpenVocab = yoloe?.detect(bitmap, minScore = OPEN_VOCAB_MIN).orEmpty()
    }

    private fun runSaliency(bitmap: Bitmap) {
        val detector = saliencyDetector ?: return
        val (gw, gh) = if (bitmap.width <= bitmap.height) 24 to 32 else 32 to 24
        lastSaliencyGrid = detector.saliency(bitmap, gw, gh)
        lastSaliencyW = gw
        lastSaliencyH = gh
    }

    /** Líneas guía, punto de fuga y simetría sobre una copia pequeña (≈160 px) del fotograma. */
    private fun runStructure(bitmap: Bitmap, subject: NRect?) {
        val scale = STRUCTURE_SIZE / maxOf(bitmap.width, bitmap.height).toFloat()
        val sw = (bitmap.width * scale).toInt().coerceAtLeast(16)
        val sh = (bitmap.height * scale).toInt().coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        if (structurePixels.size != sw * sh) structurePixels = IntArray(sw * sh)
        small.getPixels(structurePixels, 0, sw, 0, 0, sw, sh)
        if (small !== bitmap) small.recycle()
        val reading = StructureAnalyzer.analyze(structurePixels, sw, sh, subject)
        lastStructure = structureStabilizer.update(reading)
    }

    private fun chooseSubject(
        faces: List<NRect>,
        objects: List<DetectedObject>,
        salient: Pair<NRect, Float>?,
    ): Subject? {
        faces.maxByOrNull { it.area }?.let { return Subject(SubjectKind.CARA, "Cara", it, 1f) }
        objects.filter { it.label == "person" }.maxByOrNull { it.box.area }?.let {
            return Subject(SubjectKind.PERSONA, "Persona", it.box, it.score)
        }
        objects.filter { it.label in SceneRules.ANIMALS }.maxByOrNull { it.box.area }?.let {
            return Subject(SubjectKind.ANIMAL, spanish(it.label), it.box, it.score)
        }
        objects.filter { it.label in SceneRules.FOOD_OBJECTS }.maxByOrNull { it.box.area }?.let {
            return Subject(SubjectKind.COMIDA, spanish(it.label), it.box, it.score)
        }
        objects.filter { it.box.area >= 0.03f }.maxByOrNull { it.box.area }?.let {
            return Subject(SubjectKind.OBJETO, spanish(it.label), it.box, it.score)
        }
        // Sin nada conocido: lo que más llama la atención (U²-Net), con el nombre de YOLOE si lo sabe.
        if (salient != null) {
            val (box, strength) = salient
            if (box.area in 0.015f..0.6f && strength >= 0.55f) {
                val name = lastOpenVocab
                    .filter { it.score >= 0.35f && it.box.iou(box) >= 0.3f }
                    .maxByOrNull { it.score * it.box.iou(box) }
                    ?.let { spanishOpen(it.label) }
                return Subject(SubjectKind.SALIENTE, name ?: "Sujeto", box, strength)
            }
        }
        return null
    }

    private fun updateSubjectSpeed(subject: Subject?, now: Long) {
        if (subject == null) {
            lastSubjectCenter = null
            subjectSpeed = 0f
            return
        }
        val center = subject.box.centerX to subject.box.centerY
        val previous = lastSubjectCenter
        if (previous != null && now > lastSubjectMs) {
            val speed = hypot(center.first - previous.first, center.second - previous.second) /
                ((now - lastSubjectMs) / 1000f)
            subjectSpeed += 0.3f * (speed - subjectSpeed)
        }
        lastSubjectCenter = center
        lastSubjectMs = now
    }

    private fun checkThermal(now: Long) {
        if (now - lastThermalCheckMs < 2000) return
        lastThermalCheckMs = now
        val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        slowdown = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 3L
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 2L
            else -> 1L
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun normalize(box: RectF, w: Float, h: Float): NRect =
        NRect(box.left / w, box.top / h, box.right / w, box.bottom / h).clamped()

    private fun spanishOpen(label: String): String = yoloeSpanish[label] ?: label

    /**
     * Para elegir la mejor foto de una ráfaga: fracción de caras con los ojos abiertos en una foto
     * reducida y derecha, o null si no hay caras (o el modelo no está disponible).
     */
    @Synchronized
    fun eyesOpenScore(bitmap: Bitmap): Float? {
        val result = faceLandmarker.run { it.detect(BitmapImageBuilder(bitmap).build()) } ?: return null
        val shapes = result.faceBlendshapes().orElse(null) ?: return null
        if (shapes.isEmpty()) return null
        val open = shapes.count { face ->
            val left = face.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score() ?: 0f
            val right = face.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
            left < EYE_CLOSED && right < EYE_CLOSED
        }
        return open / shapes.size.toFloat()
    }

    /** Para elegir la mejor foto de una ráfaga: nota de estética (NIMA) de una foto reducida y derecha. */
    @Synchronized
    fun aestheticScore(bitmap: Bitmap): Float? = runCatching { aestheticScorer?.score(bitmap) }.getOrNull()

    /** Espera a que termine el fotograma en curso antes de liberar los modelos. */
    @Synchronized
    fun close() {
        enabled = false
        closedModels = true
        foreground = false
        acceleratorStore.setActive(false)
        accelerator.close()
        runCatching { faceDetector?.close() }
        faceLandmarker.close()
        poseLandmarker.close()
        onnxModels.forEach { runCatching { it.close() } }
        onnxModels.clear()
        faceDetector = null
        yolo = null
        yoloe = null
        saliencyDetector = null
        clip = null
        aestheticScorer = null
    }

    companion object {
        private const val TAG = "Percepcion"
        private const val FACE_MODEL = "models/blaze_face_short_range.tflite"
        private const val LANDMARKS_MODEL = "models/face_landmarker.task"
        private const val POSE_MODEL = "models/pose_landmarker_lite.task"
        private const val CLIP_MODEL = "models/clip_image.onnx"
        private const val CLIP_TEXT = "models/clip_scenes.bin"
        private const val CLIP_NAMES = "models/clip_scenes.tsv"
        private const val NIMA_MODEL = "models/nima_aesthetic.onnx"
        private const val YOLO_MODEL = "models/yolo11n.onnx"
        private const val YOLO_S_MODEL = "models/yolo11s.onnx"
        private const val YOLO_S_KEY = "yolo11s"
        private const val YOLO_LABELS = "models/yolo11n_labels.txt"
        private const val YOLOE_MODEL = "models/yoloe_vocab.onnx"
        private const val YOLOE_LABELS = "models/yoloe_labels.tsv"
        private const val SALIENCY_MODEL = "models/u2netp.onnx"
        private const val MIN_FRAME_INTERVAL_MS = 66L
        private const val MOVING_THRESHOLD = 0.35f
        private const val EYE_CLOSED = 0.5f
        private const val OPEN_VOCAB_MIN = 0.25f
        private const val STRUCTURE_SIZE = 160f

        private val HUMANS = setOf("person", "child", "baby")

        /** Nombres del vocabulario abierto que sí son animales (no vetan a un animal de YOLO11). */
        private val OPEN_VOCAB_ANIMALS = setOf(
            "person", "child", "baby", "dog", "cat", "bird", "chicken", "horse", "cow", "goat", "pig",
            "sheep", "butterfly", "insect", "fish",
        )

        private val SPANISH = mapOf(
            "person" to "Persona", "dog" to "Perro", "cat" to "Gato", "bird" to "Pájaro", "horse" to "Caballo",
            "sheep" to "Oveja", "cow" to "Vaca", "elephant" to "Elefante", "bear" to "Oso", "zebra" to "Cebra",
            "giraffe" to "Jirafa", "banana" to "Plátano", "apple" to "Manzana", "sandwich" to "Sándwich",
            "orange" to "Naranja", "broccoli" to "Brócoli", "carrot" to "Zanahoria", "hot dog" to "Perrito",
            "pizza" to "Pizza", "donut" to "Dona", "cake" to "Pastel", "bowl" to "Plato", "cup" to "Taza",
            "wine glass" to "Copa", "bottle" to "Botella", "car" to "Coche", "bicycle" to "Bicicleta",
            "motorcycle" to "Moto", "bus" to "Autobús", "truck" to "Camión", "boat" to "Barco",
            "airplane" to "Avión", "train" to "Tren", "chair" to "Silla", "couch" to "Sofá",
            "potted plant" to "Planta", "vase" to "Jarrón", "clock" to "Reloj", "book" to "Libro",
            "laptop" to "Portátil", "tv" to "Televisión", "cell phone" to "Móvil", "teddy bear" to "Peluche",
            "umbrella" to "Paraguas", "handbag" to "Bolso", "backpack" to "Mochila", "bench" to "Banco",
            "sports ball" to "Balón", "kite" to "Cometa", "dining table" to "Mesa",
        )

        fun spanish(label: String): String =
            SPANISH[label] ?: label.replaceFirstChar { it.uppercase() }
    }
}

/**
 * Un modelo de MediaPipe que intenta usar la GPU y vuelve al procesador si falla. La primera
 * ejecución en GPU va con la marca de protección: si el controlador de la gráfica cierra la app,
 * al volver a abrirla se usa el procesador.
 */
private class MpModel<T : AutoCloseable>(
    private val key: String,
    private val store: AcceleratorStore,
    private val factory: (Delegate) -> T,
) {
    @Volatile private var model: T? = null
    @Volatile var onGpu = false
        private set
    private var verified = false

    val label: String get() = if (onGpu) "GPU" else "CPU"

    fun load() {
        if (!store.disabled && !store.isBroken(key, Backend.GPU)) {
            val gpu = runCatching { store.guarded(key, Backend.GPU) { factory(Delegate.GPU) } }
            gpu.getOrNull()?.let {
                model = it
                onGpu = true
                return
            }
            store.markBroken(key, Backend.GPU, gpu.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message ?: ""}" } ?: "")
        }
        store.choose(key, Backend.CPU)
        model = factory(Delegate.CPU)
        onGpu = false
    }

    fun <R> run(block: (T) -> R): R? {
        val m = model ?: return null
        if (onGpu && !verified) {
            return try {
                store.guarded(key, Backend.GPU) { block(m) }.also {
                    verified = true
                    store.choose(key, Backend.GPU)
                }
            } catch (e: Throwable) {
                Log.w("Percepcion", "$key no funciona en la GPU: se usa el procesador", e)
                store.markBroken(key, Backend.GPU, "${e.javaClass.simpleName}: ${e.message ?: ""}")
                runCatching { m.close() }
                onGpu = false
                store.choose(key, Backend.CPU)
                val cpu = runCatching { factory(Delegate.CPU) }.getOrNull()
                model = cpu
                cpu?.let { runCatching { block(it) }.getOrNull() }
            }
        }
        return block(m)
    }

    fun close() {
        runCatching { model?.close() }
        model = null
    }
}
