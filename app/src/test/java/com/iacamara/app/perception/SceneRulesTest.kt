package com.iacamara.app.perception

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneRulesTest {

    private fun light(
        mean: Float = 120f,
        warmTop: Float = 0f,
        skyBlue: Float = 0f,
        brightNeutral: Float = 0f,
    ) = LightInfo(
        histogram = FloatArray(32),
        meanLuma = mean,
        highlightClip = 0f,
        shadowClip = 0f,
        subjectLuma = mean,
        backgroundLuma = mean,
        skyBlue = skyBlue,
        warmTop = warmTop,
        brightNeutral = brightNeutral,
        saturation = 0.3f,
    )

    /** ISO y exposición que dan aproximadamente el EV pedido con f/1.8. */
    private fun meta(ev: Float, focusCm: Float? = null): CaptureMeta {
        val seconds = 1.8 * 1.8 / Math.pow(2.0, ev.toDouble())
        return CaptureMeta(100, (seconds * 1e9).toLong(), 1.8f, focusCm, 0L)
    }

    private fun inputs(
        front: Boolean = false,
        faces: List<NRect> = emptyList(),
        objects: List<DetectedObject> = emptyList(),
        clip: Map<SceneType, Float> = emptyMap(),
        light: LightInfo = light(),
        meta: CaptureMeta? = meta(12f),
    ) = SceneInputs(front, faces, objects, clip, light, meta)

    private val face = NRect(0.4f, 0.3f, 0.6f, 0.5f)

    @Test fun selfie() = assertEquals(SceneType.SELFIE, SceneRules.classify(inputs(front = true, faces = listOf(face))))

    @Test fun retrato() = assertEquals(SceneType.RETRATO, SceneRules.classify(inputs(faces = listOf(face))))

    @Test fun grupo() = assertEquals(SceneType.GRUPO, SceneRules.classify(inputs(faces = listOf(face, face, face))))

    @Test fun mascota() = assertEquals(
        SceneType.MASCOTA,
        SceneRules.classify(inputs(objects = listOf(DetectedObject("dog", NRect(0.2f, 0.3f, 0.7f, 0.8f), 0.9f)))),
    )

    @Test fun comida() = assertEquals(
        SceneType.COMIDA,
        SceneRules.classify(inputs(clip = mapOf(SceneType.COMIDA to 0.7f))),
    )

    @Test fun noche() = assertEquals(SceneType.NOCHE, SceneRules.classify(inputs(meta = meta(2f))))

    @Test fun macro() = assertEquals(SceneType.MACRO, SceneRules.classify(inputs(meta = meta(10f, focusCm = 8f))))

    @Test fun atardecer() = assertEquals(SceneType.ATARDECER, SceneRules.classify(inputs(light = light(warmTop = 0.4f))))

    @Test fun nieve() = assertEquals(
        SceneType.NIEVE,
        SceneRules.classify(inputs(light = light(mean = 200f, brightNeutral = 0.5f), meta = meta(14f))),
    )

    @Test fun playa() = assertEquals(SceneType.PLAYA, SceneRules.classify(inputs(clip = mapOf(SceneType.PLAYA to 0.6f))))

    @Test fun arquitectura() = assertEquals(SceneType.CIUDAD, SceneRules.classify(inputs(clip = mapOf(SceneType.CIUDAD to 0.7f))))

    @Test fun atardecerSegunClip() = assertEquals(
        SceneType.ATARDECER,
        SceneRules.classify(inputs(clip = mapOf(SceneType.ATARDECER to 0.6f))),
    )

    @Test fun personaDeEspaldasEsRetratoSiClipLoConfirma() = assertEquals(
        SceneType.RETRATO,
        SceneRules.classify(
            inputs(
                objects = listOf(DetectedObject("person", NRect(0.35f, 0.3f, 0.6f, 0.6f), 0.8f)),
                clip = mapOf(SceneType.RETRATO to 0.8f),
            ),
        ),
    )

    @Test fun clipSinDetectorNoInventaUnaMascota() = assertEquals(
        // Un peluche que a CLIP le parece un perro: sin detección de animal, no es "mascota".
        SceneType.OBJETO,
        SceneRules.classify(inputs(clip = mapOf(SceneType.MASCOTA to 0.45f, SceneType.OBJETO to 0.55f))),
    )

    @Test fun paisajePorCielo() = assertEquals(SceneType.PAISAJE, SceneRules.classify(inputs(light = light(skyBlue = 0.4f))))

    @Test fun interior() = assertEquals(SceneType.INTERIOR, SceneRules.classify(inputs(meta = meta(6f))))

    @Test fun objeto() = assertEquals(
        SceneType.OBJETO,
        SceneRules.classify(inputs(objects = listOf(DetectedObject("vase", NRect(0.3f, 0.3f, 0.7f, 0.7f), 0.8f)))),
    )

    @Test fun estabilizadorEsperaVariosFotogramas() {
        val s = SceneStabilizer(requiredFrames = 3)
        assertEquals(SceneType.GENERAL, s.update(SceneType.PAISAJE))
        assertEquals(SceneType.GENERAL, s.update(SceneType.PAISAJE))
        assertEquals(SceneType.PAISAJE, s.update(SceneType.PAISAJE))
        // Un fotograma suelto distinto no cambia la escena.
        assertEquals(SceneType.PAISAJE, s.update(SceneType.NOCHE))
        assertEquals(SceneType.PAISAJE, s.update(SceneType.PAISAJE))
    }

    @Test fun condicionesConMargen() {
        val s = ConditionStabilizer(onFrames = 2, offFrames = 2)
        val on = setOf(Condition.CONTRALUZ)
        assertEquals(emptySet<Condition>(), s.update(on))
        assertEquals(on, s.update(on))
        assertEquals(on, s.update(emptySet()))
        assertEquals(emptySet<Condition>(), s.update(emptySet()))
    }

    @Test fun ev100() {
        // f/1.8, 1/100 s, ISO 100 ≈ EV 8,3.
        val m = CaptureMeta(100, 10_000_000L, 1.8f, null, 0L)
        assertEquals(8.34f, m.ev100, 0.05f)
    }
}
