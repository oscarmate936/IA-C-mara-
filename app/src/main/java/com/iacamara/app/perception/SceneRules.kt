package com.iacamara.app.perception

/** Todo lo que se sabe de un fotograma para decidir el tipo de escena. */
data class SceneInputs(
    val front: Boolean,
    val faces: List<NRect>,
    val objects: List<DetectedObject>,
    /** Probabilidad de cada tipo de escena según CLIP (vacío si el modelo no está disponible). */
    val sceneScores: Map<SceneType, Float>,
    val light: LightInfo,
    val meta: CaptureMeta?,
)

/**
 * Reglas fotográficas para reconocer la escena combinando detección de caras y objetos, lo que
 * "ve" CLIP en la imagen, la luz y los ajustes reales del sensor.
 */
object SceneRules {

    /**
     * Las caras que forman parte de la foto: las de tamaño parecido a la mayor (al menos [MAIN_FACE_SHARE]
     * de su área). El detector de caras ve también caras pequeñas al fondo (gente que pasa lejos): en un
     * retrato no cuentan para decidir que es un grupo. Un grupo lejano (todas pequeñas y parecidas) sí.
     */
    fun mainFaces(faces: List<NRect>): List<NRect> {
        val biggest = faces.maxOfOrNull { it.area } ?: return emptyList()
        return faces.filter { it.area >= biggest * MAIN_FACE_SHARE }
    }

    const val MAIN_FACE_SHARE = 0.25f

    /** Ancho mínimo (parte del encuadre) de una cara para que el modelo de 478 puntos le vea los ojos. */
    const val EYES_MIN_FACE = 0.1f

    val ANIMALS = setOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
    val FOOD_OBJECTS = setOf(
        "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "bowl", "cup", "wine glass",
    )

    fun classify(i: SceneInputs): SceneType {
        val ev = i.meta?.ev100
        val focusCm = i.meta?.focusDistanceCm
        val biggestFace = i.faces.maxOfOrNull { it.area } ?: 0f
        val persons = i.objects.filter { it.label == "person" }
        val biggestPerson = persons.maxOfOrNull { it.box.area } ?: 0f
        val animalArea = i.objects.filter { it.label in ANIMALS }.maxOfOrNull { it.box.area } ?: 0f
        val foodObjectArea = i.objects.filter { it.label in FOOD_OBJECTS }.sumOf { it.box.area.toDouble() }.toFloat()
        fun clip(scene: SceneType): Float = i.sceneScores[scene] ?: 0f

        // Personas primero: son lo más importante de una foto.
        if (i.front && i.faces.isNotEmpty()) return SceneType.SELFIE
        if (mainFaces(i.faces).size >= 3 || persons.count { it.box.area > 0.02f } >= 3) return SceneType.GRUPO
        if (persons.size >= 2 && clip(SceneType.GRUPO) >= 0.6f) return SceneType.GRUPO
        if (i.faces.isNotEmpty() && biggestFace >= 0.012f) return SceneType.RETRATO
        if (biggestPerson >= 0.12f) return SceneType.RETRATO
        // De espaldas o sin cara clara: CLIP confirma que es un retrato.
        if (biggestPerson >= 0.05f && clip(SceneType.RETRATO) >= 0.6f) return SceneType.RETRATO
        if (animalArea >= 0.04f || (animalArea > 0f && clip(SceneType.MASCOTA) >= 0.5f)) return SceneType.MASCOTA
        if (clip(SceneType.COMIDA) >= 0.45f || foodObjectArea >= 0.08f) return SceneType.COMIDA

        // Luz y distancia.
        if ((ev != null && ev < 3.5f) || (ev == null && i.light.meanLuma < 40f)) return SceneType.NOCHE
        if (clip(SceneType.NOCHE) >= 0.6f && i.light.meanLuma < 90f) return SceneType.NOCHE
        if (focusCm != null && focusCm < 12f) {
            return if (clip(SceneType.FLORES) >= 0.25f) SceneType.FLORES else SceneType.MACRO
        }
        if (clip(SceneType.FLORES) >= 0.5f) return SceneType.FLORES
        if (clip(SceneType.MACRO) >= 0.6f) return SceneType.MACRO

        val outdoorBright = ev == null || ev >= 10.5f
        val warmLight = i.light.warmTop >= 0.25f && i.light.meanLuma in 45f..210f && (ev == null || ev >= 6f)
        if (warmLight || clip(SceneType.ATARDECER) >= 0.45f) return SceneType.ATARDECER
        if (clip(SceneType.NIEVE) >= 0.45f || (i.light.brightNeutral >= 0.35f && ev != null && ev >= 11.5f)) {
            return SceneType.NIEVE
        }
        if (clip(SceneType.PLAYA) >= 0.4f) return SceneType.PLAYA
        if (clip(SceneType.CIUDAD) >= 0.4f) return SceneType.CIUDAD
        if (clip(SceneType.PAISAJE) >= 0.4f) return SceneType.PAISAJE

        val biggestThing = i.objects.filter { it.label != "person" }.maxOfOrNull { it.box.area } ?: 0f
        if (i.light.skyBlue >= 0.25f && outdoorBright && biggestThing < 0.1f) return SceneType.PAISAJE
        if (biggestThing >= 0.08f || clip(SceneType.OBJETO) >= 0.5f) return SceneType.OBJETO
        if ((ev != null && ev < 8.5f) || clip(SceneType.INTERIOR) >= 0.4f) return SceneType.INTERIOR
        return SceneType.GENERAL
    }
}

/** Evita que la escena cambie a cada fotograma: un cambio tiene que repetirse varias veces seguidas. */
class SceneStabilizer(private val requiredFrames: Int = 5) {
    var current: SceneType = SceneType.GENERAL
        private set
    private var candidate: SceneType = SceneType.GENERAL
    private var count = 0

    fun update(scene: SceneType): SceneType {
        when (scene) {
            current -> {
                candidate = scene
                count = 0
            }
            candidate -> {
                count++
                if (count >= requiredFrames) {
                    current = scene
                    count = 0
                }
            }
            else -> {
                candidate = scene
                count = 1
            }
        }
        return current
    }
}

/** Igual para las condiciones de luz/movimiento: se encienden y se apagan con un pequeño margen. */
class ConditionStabilizer(private val onFrames: Int = 4, private val offFrames: Int = 6) {
    private val counters = mutableMapOf<Condition, Int>()
    private val active = mutableSetOf<Condition>()

    fun update(raw: Set<Condition>): Set<Condition> {
        for (condition in Condition.entries) {
            val present = condition in raw
            val c = counters[condition] ?: 0
            if (present) {
                val next = if (c < 0) 1 else c + 1
                counters[condition] = next
                if (next >= onFrames) active += condition
            } else {
                val next = if (c > 0) -1 else c - 1
                counters[condition] = next
                if (-next >= offFrames) active -= condition
            }
        }
        return active.toSet()
    }
}
