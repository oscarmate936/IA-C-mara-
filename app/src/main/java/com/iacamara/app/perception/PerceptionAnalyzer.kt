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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.iacamara.app.perception.onnx.OnnxModel
import com.iacamara.app.perception.onnx.SaliencyDetector
import com.iacamara.app.perception.onnx.YoloDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Capa 1 del plan: percepción en vivo, con varios modelos que van dentro del APK (sin internet):
 *
 * - Caras (MediaPipe BlazeFace): en cada fotograma.
 * - Ojos y mirada (MediaPipe Face Landmarker): ~4 veces por segundo si hay caras.
 * - Tipo de escena (MediaPipe EfficientNet, 1000 clases): ~2 veces por segundo.
 * - Objetos (YOLO11n, 80 clases): ~5 veces por segundo.
 * - Vocabulario abierto (YOLOE con ~120 cosas habituales en fotos): ~1 vez por segundo; da nombre
 *   a lo que YOLO11 no conoce y anula sus confusiones.
 * - Saliencia (U²-Net): ~1–2 veces por segundo; encuentra "lo importante" aunque no tenga nombre.
 *
 * Cada modelo se espacia solo según lo que tarda en este móvil y si se calienta, y en cada fotograma
 * se ejecuta como mucho un modelo pesado, para que el visor vaya fluido.
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

    // MediaPipe
    private var faceDetector: FaceDetector? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var classifier: ImageClassifier? = null

    // ONNX Runtime
    private val onnxModels = mutableListOf<OnnxModel>()
    private var yolo: YoloDetector? = null
    private var yoloe: YoloDetector? = null
    private var saliencyDetector: SaliencyDetector? = null
    private var yoloeSpanish: Map<String, String> = emptyMap()
    private var initialized = false

    private val lightAnalyzer = LightAnalyzer()
    private val sceneStabilizer = SceneStabilizer()
    private val conditionStabilizer = ConditionStabilizer()
    private val objectTracker = ObjectTracker()

    private val labelsClock = TaskClock(450, 3f)
    private val landmarksClock = TaskClock(250, 3f)
    private val yoloClock = TaskClock(180, 2.5f)
    private val yoloeClock = TaskClock(900, 4f)
    private val saliencyClock = TaskClock(600, 4f)

    private var lastFront = false
    private var lastDegrees = 0
    private var lastFrameMs = 0L
    private var lastThermalCheckMs = 0L
    private var slowdown = 1L

    // Últimos resultados (coordenadas del fotograma del modelo, M).
    private var lastObjects: List<DetectedObject> = emptyList()
    private var lastOpenVocab: List<DetectedObject> = emptyList()
    private var lastLabels: List<Pair<String, Float>> = emptyList()
    private var animalEvidence = true
    private var lastSaliencyGrid: FloatArray? = null
    private var lastSaliencyW = 0
    private var lastSaliencyH = 0
    private var lastFaceDetails: List<FaceDetail> = emptyList()
    private var lastSubjectCenter: Pair<Float, Float>? = null
    private var lastSubjectMs = 0L
    private var subjectSpeed = 0f

    private fun ensureModels() {
        if (initialized) return
        initialized = true
        val failures = mutableListOf<String>()
        faceDetector = load("caras", failures) {
            FaceDetector.createFromOptions(
                appContext,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(base(FACE_MODEL))
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.5f)
                    .build(),
            )
        }
        faceLandmarker = load("ojos", failures) {
            FaceLandmarker.createFromOptions(
                appContext,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base(LANDMARKS_MODEL))
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(5)
                    .setOutputFaceBlendshapes(true)
                    .setMinFaceDetectionConfidence(0.5f)
                    .build(),
            )
        }
        classifier = load("escenas", failures) {
            ImageClassifier.createFromOptions(
                appContext,
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(base(SCENE_MODEL))
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(5)
                    .setScoreThreshold(0.05f)
                    .build(),
            )
        }
        yolo = load("objetos", failures) {
            val labels = readLines(YOLO_LABELS)
            YoloDetector(onnx(YOLO_MODEL), 512, labels)
        }
        yoloe = load("vocabulario abierto", failures) {
            val rows = readLines(YOLOE_LABELS).map { it.split('\t') }
            yoloeSpanish = rows.associate { cols -> cols[0] to (cols.getOrNull(1) ?: cols[0]) }
            YoloDetector(onnx(YOLOE_MODEL), 480, rows.map { cols -> cols[0] })
        }
        saliencyDetector = load("sujeto principal", failures) { SaliencyDetector(onnx(SALIENCY_MODEL)) }
        if (failures.isNotEmpty()) _error.value = "IA incompleta · " + failures.joinToString(" · ")
    }

    private fun onnx(path: String): OnnxModel = OnnxModel(appContext, path).also { onnxModels += it }

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

    private fun base(path: String): BaseOptions = BaseOptions.builder().setModelAssetPath(path).build()

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
        listOf(yoloClock, yoloeClock, saliencyClock, landmarksClock).forEach { it.reset() }
    }

    private fun process(bitmap: Bitmap, modelRotation: Int, front: Boolean, now: Long) {
        val start = SystemClock.elapsedRealtime()
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Caras: en cada fotograma (muy rápido).
        val faces = faceDetector?.detect(mpImage)?.detections().orEmpty()
            .map { normalize(it.boundingBox(), w, h) }

        // Tareas medianas.
        if (labelsClock.overdue(now, slowdown) >= 1f) {
            timed(labelsClock, now) { runClassifier(mpImage) }
        }
        if (faces.isNotEmpty() && landmarksClock.overdue(now, slowdown) >= 1f) {
            timed(landmarksClock, now) { runLandmarks(mpImage) }
        } else if (faces.isEmpty()) {
            lastFaceDetails = emptyList()
        }

        // Como mucho una tarea pesada por fotograma: la que más atrasada vaya.
        val heavy = listOfNotNull(
            yolo?.let { "yolo" to yoloClock.overdue(now, slowdown) },
            yoloe?.let { "yoloe" to yoloeClock.overdue(now, slowdown) },
            saliencyDetector?.let { "saliencia" to saliencyClock.overdue(now, slowdown) },
        ).filter { it.second >= 1f }.maxByOrNull { it.second }?.first
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
        val light = lightAnalyzer.analyze(bitmap, subjectInModel?.box)
        val meta = captureMetadata.current()

        val scene = sceneStabilizer.update(
            SceneRules.classify(SceneInputs(front, faces, lastObjects, lastLabels, light, meta)),
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
            labels = lastLabels.take(3).map { it.first },
            frameMs = SystemClock.elapsedRealtime() - start,
            timestampMs = now,
            saliency = saliencyP,
            faceDetails = lastFaceDetails.map { it.copy(box = toP(it.box)) },
            namedObjects = lastOpenVocab.map { it.copy(label = spanishOpen(it.label), box = toP(it.box)) },
            timings = mapOf(
                "YOLO" to yoloClock.averageMs.toInt(),
                "YOLOE" to yoloeClock.averageMs.toInt(),
                "Saliencia" to saliencyClock.averageMs.toInt(),
                "Ojos" to landmarksClock.averageMs.toInt(),
            ),
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

    private fun runClassifier(mpImage: MPImage) {
        val categories = classifier?.classify(mpImage)?.classificationResult()?.classifications()
            ?.firstOrNull()?.categories().orEmpty()
        lastLabels = categories.map { it.categoryName() to it.score() }
        // En ImageNet las clases 0–397 son animales: sirve para confirmar lo que dicen los detectores.
        animalEvidence = classifier == null ||
            categories.any { it.index() < IMAGENET_LAST_ANIMAL && it.score() >= 0.05f }
    }

    private fun runLandmarks(mpImage: MPImage) {
        val result = faceLandmarker?.detect(mpImage) ?: return
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
            if (!DetectionFilter.accept(d, animalEvidence)) return@filter false
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
        val landmarker = faceLandmarker ?: return null
        val shapes = landmarker.detect(BitmapImageBuilder(bitmap).build()).faceBlendshapes().orElse(null) ?: return null
        if (shapes.isEmpty()) return null
        val open = shapes.count { face ->
            val left = face.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score() ?: 0f
            val right = face.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
            left < EYE_CLOSED && right < EYE_CLOSED
        }
        return open / shapes.size.toFloat()
    }

    /** Espera a que termine el fotograma en curso antes de liberar los modelos. */
    @Synchronized
    fun close() {
        enabled = false
        runCatching { faceDetector?.close() }
        runCatching { faceLandmarker?.close() }
        runCatching { classifier?.close() }
        onnxModels.forEach { runCatching { it.close() } }
        onnxModels.clear()
        faceDetector = null
        faceLandmarker = null
        classifier = null
        yolo = null
        yoloe = null
        saliencyDetector = null
    }

    companion object {
        private const val TAG = "Percepcion"
        private const val FACE_MODEL = "models/blaze_face_short_range.tflite"
        private const val LANDMARKS_MODEL = "models/face_landmarker.task"
        private const val SCENE_MODEL = "models/efficientnet_lite0.tflite"
        private const val YOLO_MODEL = "models/yolo11n.onnx"
        private const val YOLO_LABELS = "models/yolo11n_labels.txt"
        private const val YOLOE_MODEL = "models/yoloe_vocab.onnx"
        private const val YOLOE_LABELS = "models/yoloe_labels.tsv"
        private const val SALIENCY_MODEL = "models/u2netp.onnx"
        private const val MIN_FRAME_INTERVAL_MS = 66L
        private const val MOVING_THRESHOLD = 0.35f
        private const val IMAGENET_LAST_ANIMAL = 398
        private const val EYE_CLOSED = 0.5f
        private const val OPEN_VOCAB_MIN = 0.25f

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
