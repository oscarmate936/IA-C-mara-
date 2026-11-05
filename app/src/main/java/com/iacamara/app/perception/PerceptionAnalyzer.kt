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
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Capa 1 del plan: percepción en vivo.
 *
 * Analiza los fotogramas del visor con modelos que van dentro del APK (MediaPipe, sin internet):
 * caras en cada fotograma, objetos ~10 veces por segundo, tipo de escena ~2 veces por segundo,
 * luz y color en cada fotograma. Si el móvil se calienta, reduce la frecuencia.
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

    private var faceDetector: FaceDetector? = null
    private var objectDetector: ObjectDetector? = null
    private var classifier: ImageClassifier? = null
    private var initialized = false

    private val lightAnalyzer = LightAnalyzer()
    private val sceneStabilizer = SceneStabilizer()
    private val conditionStabilizer = ConditionStabilizer()
    private val objectTracker = ObjectTracker()

    private var lastFront = false
    private var lastDegrees = 0
    private var lastFrameMs = 0L
    private var lastObjectsMs = 0L
    private var lastLabelsMs = 0L
    private var lastThermalCheckMs = 0L
    private var slowdown = 1L
    private var lastObjects: List<DetectedObject> = emptyList()
    private var lastLabels: List<Pair<String, Float>> = emptyList()
    private var animalEvidence = true
    private var lastSubjectCenter: Pair<Float, Float>? = null
    private var lastSubjectMs = 0L
    private var subjectSpeed = 0f

    private fun ensureModels() {
        if (initialized) return
        initialized = true
        val failures = mutableListOf<String>()
        // Cada modelo se carga por separado: si uno falla, los demás siguen funcionando.
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
        objectDetector = load("objetos", failures) {
            ObjectDetector.createFromOptions(
                appContext,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(base(OBJECT_MODEL))
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(8)
                    .setScoreThreshold(0.35f)
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
        if (failures.isNotEmpty()) _error.value = "IA incompleta · " + failures.joinToString(" · ")
    }

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
                // Al cambiar de cámara o de orientación, los objetos anteriores ya no valen.
                objectTracker.clear()
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

    private fun process(bitmap: Bitmap, modelRotation: Int, front: Boolean, now: Long) {
        val start = SystemClock.elapsedRealtime()
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Caras: en cada fotograma.
        val faces = faceDetector?.detect(mpImage)?.detections().orEmpty()
            .map { normalize(it.boundingBox(), w, h) }

        // Tipo de escena: ~2 veces por segundo.
        if (now - lastLabelsMs >= LABELS_INTERVAL_MS * slowdown) {
            lastLabelsMs = now
            val categories = classifier?.classify(mpImage)?.classificationResult()?.classifications()
                ?.firstOrNull()?.categories().orEmpty()
            lastLabels = categories.map { it.categoryName() to it.score() }
            // En ImageNet las clases 0–397 son animales: sirve para confirmar lo que dice el detector.
            animalEvidence = classifier == null ||
                categories.any { it.index() < IMAGENET_LAST_ANIMAL && it.score() >= 0.05f }
        }

        // Objetos: ~6 veces por segundo (EfficientDet-Lite2, más preciso).
        if (now - lastObjectsMs >= OBJECTS_INTERVAL_MS * slowdown) {
            lastObjectsMs = now
            val raw = objectDetector?.detect(mpImage)?.detections().orEmpty().mapNotNull { d ->
                val category = d.categories().firstOrNull() ?: return@mapNotNull null
                DetectedObject(category.categoryName(), normalize(d.boundingBox(), w, h), category.score())
            }
            // Solo cuentan los objetos con suficiente seguridad y que se repiten en varios fotogramas.
            lastObjects = objectTracker.update(raw.filter { DetectionFilter.accept(it, animalEvidence) })
        }

        val subjectInModel = chooseSubject(faces, lastObjects)
        val light = lightAnalyzer.analyze(bitmap, subjectInModel?.box)
        val meta = captureMetadata.current()

        val scene = sceneStabilizer.update(
            SceneRules.classify(SceneInputs(front, faces, lastObjects, lastLabels, light, meta)),
        )

        // Movimiento del sujeto.
        if (subjectInModel != null) {
            val center = subjectInModel.box.centerX to subjectInModel.box.centerY
            val previous = lastSubjectCenter
            if (previous != null && now > lastSubjectMs) {
                val speed = hypot(center.first - previous.first, center.second - previous.second) /
                    ((now - lastSubjectMs) / 1000f)
                subjectSpeed += 0.3f * (speed - subjectSpeed)
            }
            lastSubjectCenter = center
            lastSubjectMs = now
        } else {
            lastSubjectCenter = null
            subjectSpeed = 0f
        }

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

        // Todo se devuelve en coordenadas del encuadre vertical de la pantalla.
        val subject = subjectInModel?.copy(box = FrameMath.unrotate(subjectInModel.box, modelRotation))
        _perception.value = Perception(
            scene = scene,
            conditions = conditions,
            subject = subject,
            faces = faces.map { FrameMath.unrotate(it, modelRotation) },
            light = light,
            meta = meta,
            labels = lastLabels.take(3).map { it.first },
            frameMs = SystemClock.elapsedRealtime() - start,
            timestampMs = now,
        )
    }

    private fun chooseSubject(faces: List<NRect>, objects: List<DetectedObject>): Subject? {
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
        return null
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

    /** Espera a que termine el fotograma en curso antes de liberar los modelos. */
    @Synchronized
    fun close() {
        enabled = false
        runCatching { faceDetector?.close() }
        runCatching { objectDetector?.close() }
        runCatching { classifier?.close() }
        faceDetector = null
        objectDetector = null
        classifier = null
    }

    companion object {
        private const val TAG = "Percepcion"
        private const val FACE_MODEL = "models/blaze_face_short_range.tflite"
        private const val OBJECT_MODEL = "models/efficientdet_lite2.tflite"
        private const val SCENE_MODEL = "models/efficientnet_lite0.tflite"
        private const val MIN_FRAME_INTERVAL_MS = 66L
        private const val OBJECTS_INTERVAL_MS = 150L
        private const val LABELS_INTERVAL_MS = 450L
        private const val MOVING_THRESHOLD = 0.35f
        private const val IMAGENET_LAST_ANIMAL = 398

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
