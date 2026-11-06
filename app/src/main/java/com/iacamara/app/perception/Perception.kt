package com.iacamara.app.perception

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** Rectángulo normalizado (0..1) en el encuadre vertical de la pantalla, sin espejo. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Intersección sobre unión: 1 = misma caja, 0 = no se tocan. */
    fun iou(other: NRect): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = area + other.area - inter
        return if (union > 0f) inter / union else 0f
    }

    fun clamped(): NRect = NRect(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f),
    )

    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float) = NRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

enum class SceneType(val label: String, val emoji: String) {
    GENERAL("Foto", "📷"),
    RETRATO("Retrato", "👤"),
    GRUPO("Grupo", "👥"),
    SELFIE("Selfie", "🤳"),
    MASCOTA("Mascota", "🐾"),
    COMIDA("Comida", "🍽️"),
    FLORES("Flores", "🌸"),
    MACRO("Macro", "🔍"),
    PAISAJE("Paisaje", "🏞️"),
    PLAYA("Playa", "🏖️"),
    NIEVE("Nieve", "❄️"),
    ATARDECER("Atardecer", "🌅"),
    CIUDAD("Arquitectura", "🏛️"),
    NOCHE("Noche", "🌙"),
    INTERIOR("Interior", "🏠"),
    OBJETO("Objeto", "📦"),
}

enum class Condition(val label: String) {
    CONTRALUZ("Contraluz"),
    POCA_LUZ("Poca luz"),
    TEMBLOR("Temblor"),
    MOVIMIENTO("Sujeto en movimiento"),
    LUCES_QUEMADAS("Luces quemadas"),
}

enum class SubjectKind { CARA, PERSONA, ANIMAL, COMIDA, OBJETO, SALIENTE }

/** Detalle de una cara obtenido con Face Landmarker. */
data class FaceDetail(
    val box: NRect,
    val eyesOpen: Boolean,
    /** Hacia dónde mira, en la imagen sin espejo: −1 izquierda … +1 derecha. */
    val gazeX: Float,
)

data class Subject(val kind: SubjectKind, val label: String, val box: NRect, val score: Float)

/** Objeto detectado (etiqueta COCO en inglés, caja normalizada). */
data class DetectedObject(val label: String, val box: NRect, val score: Float)

/** Medidas de luz y color del fotograma. */
class LightInfo(
    /** 32 barras de luminancia, normalizadas a la mayor = 1. */
    val histogram: FloatArray,
    val meanLuma: Float,
    val highlightClip: Float,
    val shadowClip: Float,
    val subjectLuma: Float,
    val backgroundLuma: Float,
    /** Fracción del tercio superior que es cielo azul. */
    val skyBlue: Float,
    /** Fracción del tercio superior con tonos cálidos de atardecer. */
    val warmTop: Float,
    /** Fracción de la imagen muy clara y sin color (nieve, arena clara, nubes). */
    val brightNeutral: Float,
    val saturation: Float,
    /** Altura del horizonte (0 arriba … 1 abajo, imagen derecha) si hay un cambio claro cielo/tierra. */
    val horizonY: Float? = null,
)

/**
 * Mapa de "lo que llama la atención" (saliencia, U²-Net) en una rejilla pequeña,
 * en coordenadas del encuadre vertical de la pantalla (P). Valores 0–1.
 */
class SaliencyMap(val width: Int, val height: Int, private val values: FloatArray) {
    fun value(i: Int, j: Int): Float = values[j * width + i]
}

/** Ajustes reales del sensor que la cámara está usando en el visor. */
data class CaptureMeta(
    val iso: Int,
    val exposureNs: Long,
    val aperture: Float,
    /** Distancia de enfoque en cm; null = infinito o desconocida. */
    val focusDistanceCm: Float?,
    val timestampMs: Long,
) {
    /** Valor de exposición equivalente a ISO 100: <4 noche, 5–9 interior, 12–16 exterior de día. */
    val ev100: Float
        get() {
            val seconds = exposureNs / 1_000_000_000.0
            if (seconds <= 0.0 || iso <= 0) return 10f
            return (log2(aperture.toDouble() * aperture / seconds) - log2(iso / 100.0)).toFloat()
        }
}

/**
 * Puntos del cuerpo de la persona principal (MediaPipe Pose: 33 puntos) en coordenadas P.
 * Los puntos fuera del encuadre también tienen posición (estimada) pero poca visibilidad.
 */
class BodyPose(val xs: FloatArray, val ys: FloatArray, val visibility: FloatArray) {
    fun visible(i: Int, min: Float = 0.5f): Boolean = visibility[i] >= min

    fun map(f: (Float, Float) -> Pair<Float, Float>): BodyPose {
        val nx = FloatArray(xs.size)
        val ny = FloatArray(ys.size)
        for (i in xs.indices) {
            val (x, y) = f(xs[i], ys[i])
            nx[i] = x
            ny[i] = y
        }
        return BodyPose(nx, ny, visibility)
    }

    companion object {
        const val COUNT = 33
        const val NOSE = 0
        const val LEFT_EYE = 2
        const val RIGHT_EYE = 5
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
        const val LEFT_FOOT = 31
        const val RIGHT_FOOT = 32
    }
}

data class Perception(
    val scene: SceneType,
    val conditions: Set<Condition>,
    val subject: Subject?,
    val faces: List<NRect>,
    val light: LightInfo?,
    val meta: CaptureMeta?,
    /** Etiquetas principales del clasificador (para depurar). */
    val labels: List<String>,
    val frameMs: Long,
    val timestampMs: Long,
    val saliency: SaliencyMap? = null,
    /** Datos de cada cara (ojos, mirada) obtenidos con Face Landmarker. */
    val faceDetails: List<FaceDetail> = emptyList(),
    /** Objetos con nombre en español del detector de vocabulario abierto (YOLOE). */
    val namedObjects: List<DetectedObject> = emptyList(),
    /** Milisegundos medios de cada modelo en este móvil (para ver el rendimiento). */
    val timings: Map<String, Int> = emptyMap(),
    /** Líneas guía, punto de fuga, simetría y reflejos de la escena. */
    val structure: SceneStructure? = null,
    /** Puntos del cuerpo de la persona principal (coordenadas P). */
    val pose: BodyPose? = null,
    /** Probabilidad de cada tipo de escena según CLIP (suma 1). */
    val sceneScores: Map<SceneType, Float> = emptyMap(),
    /** Nota de estética 1–10 (NIMA, aprendida de fotos puntuadas por personas). */
    val aesthetic: Float? = null,
    /** Cómo le da la luz a la cara principal. */
    val faceLight: FaceLight? = null,
    /** Desenfoque por el pulso previsto si se dispara ahora (píxeles de la foto): giro × exposición × focal. */
    val blurPx: Float? = null,
)
