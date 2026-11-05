package com.iacamara.app.perception

/** Todo lo que se sabe de un fotograma para decidir el tipo de escena. */
data class SceneInputs(
    val front: Boolean,
    val faces: List<NRect>,
    val objects: List<DetectedObject>,
    /** Etiquetas del clasificador (ImageNet) con su probabilidad. */
    val imageLabels: List<Pair<String, Float>>,
    val light: LightInfo,
    val meta: CaptureMeta?,
)

/**
 * Reglas fotográficas para reconocer la escena combinando detección de caras y objetos,
 * clasificación de la imagen, luz y los ajustes reales del sensor.
 */
object SceneRules {

    val ANIMALS = setOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
    val FOOD_OBJECTS = setOf(
        "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
        "bowl", "cup", "wine glass",
    )

    private val FOOD_LABELS = setOf(
        "plate", "menu", "guacamole", "consomme", "hot pot", "trifle", "ice cream", "ice lolly", "french loaf",
        "bagel", "pretzel", "cheeseburger", "hotdog", "mashed potato", "head cabbage", "broccoli", "cauliflower",
        "zucchini", "spaghetti squash", "acorn squash", "butternut squash", "cucumber", "artichoke", "bell pepper",
        "cardoon", "mushroom", "granny smith", "strawberry", "orange", "lemon", "fig", "pineapple", "banana",
        "jackfruit", "custard apple", "pomegranate", "carbonara", "chocolate sauce", "dough", "meat loaf", "pizza",
        "potpie", "burrito", "red wine", "espresso", "cup", "eggnog", "soup bowl", "wok", "frying pan",
        "mixing bowl", "restaurant",
    )
    private val LANDSCAPE_LABELS = setOf(
        "alp", "cliff", "geyser", "lakeside", "promontory", "valley", "volcano", "seashore", "sandbar", "coral reef",
    )
    private val BEACH_LABELS = setOf("seashore", "sandbar")
    private val SNOW_LABELS = setOf("alp", "ski", "snowmobile", "dogsled")
    private val FLOWER_LABELS = setOf("daisy", "yellow lady's slipper", "rapeseed", "pot", "vase", "hip")
    private val ARCHITECTURE_LABELS = setOf(
        "church", "palace", "castle", "mosque", "monastery", "dome", "triumphal arch", "obelisk",
        "steel arch bridge", "suspension bridge", "viaduct", "pier", "boathouse", "library", "bell cote", "stupa",
        "planetarium", "cinema", "water tower", "fountain", "streetcar", "traffic light", "tile roof", "prison",
        "cliff dwelling", "megalith", "barn", "triumphal arch", "street sign",
    )
    private val INDOOR_LABELS = setOf(
        "studio couch", "dining table", "home theater", "library", "bookcase", "wardrobe", "window shade",
        "quilt", "four-poster", "desk", "monitor", "television", "refrigerator", "microwave", "dishwasher",
        "restaurant", "barbershop", "shoe shop", "toyshop", "grocery store", "bakery",
    )

    fun classify(i: SceneInputs): SceneType {
        val ev = i.meta?.ev100
        val focusCm = i.meta?.focusDistanceCm
        val biggestFace = i.faces.maxOfOrNull { it.area } ?: 0f
        val persons = i.objects.filter { it.label == "person" }
        val biggestPerson = persons.maxOfOrNull { it.box.area } ?: 0f
        val animalArea = i.objects.filter { it.label in ANIMALS }.maxOfOrNull { it.box.area } ?: 0f
        val foodObjectArea = i.objects.filter { it.label in FOOD_OBJECTS }.sumOf { it.box.area.toDouble() }.toFloat()

        // Personas primero: son lo más importante de una foto.
        if (i.front && i.faces.isNotEmpty()) return SceneType.SELFIE
        if (i.faces.size >= 3 || persons.count { it.box.area > 0.02f } >= 3) return SceneType.GRUPO
        if (i.faces.isNotEmpty() && biggestFace >= 0.012f) return SceneType.RETRATO
        if (biggestPerson >= 0.12f) return SceneType.RETRATO
        if (animalArea >= 0.04f) return SceneType.MASCOTA
        if (score(i, FOOD_LABELS) >= 0.25f || foodObjectArea >= 0.08f) return SceneType.COMIDA

        // Luz y distancia.
        if ((ev != null && ev < 3.5f) || (ev == null && i.light.meanLuma < 40f)) return SceneType.NOCHE
        if (focusCm != null && focusCm < 12f) {
            return if (score(i, FLOWER_LABELS) >= 0.15f) SceneType.FLORES else SceneType.MACRO
        }
        if (score(i, FLOWER_LABELS) >= 0.3f) return SceneType.FLORES

        val outdoorBright = ev == null || ev >= 10.5f
        if (i.light.warmTop >= 0.25f && i.light.meanLuma in 45f..210f && (ev == null || ev >= 6f)) {
            return SceneType.ATARDECER
        }
        if (score(i, SNOW_LABELS) >= 0.2f || (i.light.brightNeutral >= 0.35f && ev != null && ev >= 11.5f)) {
            return SceneType.NIEVE
        }
        if (score(i, BEACH_LABELS) >= 0.2f) return SceneType.PLAYA
        if (score(i, ARCHITECTURE_LABELS) >= 0.2f) return SceneType.CIUDAD
        if (score(i, LANDSCAPE_LABELS) >= 0.2f) return SceneType.PAISAJE

        val biggestThing = i.objects.filter { it.label != "person" }.maxOfOrNull { it.box.area } ?: 0f
        if (i.light.skyBlue >= 0.25f && outdoorBright && biggestThing < 0.1f) return SceneType.PAISAJE
        if (biggestThing >= 0.08f) return SceneType.OBJETO
        if ((ev != null && ev < 8.5f) || score(i, INDOOR_LABELS) >= 0.2f) return SceneType.INTERIOR
        return SceneType.GENERAL
    }

    private fun score(i: SceneInputs, group: Set<String>): Float =
        i.imageLabels.filter { (label, _) -> label.lowercase() in group }.sumOf { it.second.toDouble() }.toFloat()
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
