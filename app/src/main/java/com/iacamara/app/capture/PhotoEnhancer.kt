package com.iacamara.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.iacamara.app.imaging.AutoEnhance
import com.iacamara.app.imaging.CropHint
import com.iacamara.app.imaging.PhotoGeometry
import com.iacamara.app.imaging.ShotContext
import com.iacamara.app.perception.NRect
import com.iacamara.app.perception.SceneType
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lo que sabía la cámara al disparar, para revelar la foto. Coordenadas de la foto derecha (0..1).
 *
 * @param rollDeg giro del móvil respecto al horizonte (positivo = girado a la izquierda).
 * @param pitchDeg inclinación de la cámara (positivo = mirando hacia arriba).
 * @param crop dónde estaba el sujeto y dónde lo quería la guía del coach.
 */
data class ShotInfo(
    val scene: SceneType,
    val iso: Int?,
    val rollDeg: Float?,
    val pitchDeg: Float?,
    val crop: CropHint?,
    val front: Boolean,
)

/**
 * Mejora automática (Fase 5): a partir de la foto ya guardada crea una copia revelada con la receta de
 * la escena y, si se quiere, enderezada, con la perspectiva corregida y recortada según la guía.
 * La nota de estética (NIMA) hace de freno: si la mejora no gusta más que la original, se suaviza o
 * no se guarda.
 */
class PhotoEnhancer(private val context: Context) {

    /** Caras en una foto reducida y derecha (coordenadas 0..1). La pone la percepción. */
    var faceFinder: ((Bitmap) -> List<NRect>)? = null

    /** Nota de estética 1–10 de una foto reducida y derecha. */
    var aestheticScorer: ((Bitmap) -> Float?)? = null

    /** Devuelve la Uri de la copia mejorada, o null si no se guardó (no mejoraba o falló). */
    fun enhance(source: Uri, shot: ShotInfo, alreadyProcessed: Boolean, geometry: Boolean): Uri? {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return null
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val focal35 = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)?.takeIf { it > 0 }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = SRGB
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val upright = orient(decoded, orientation)
        val w = upright.width
        val h = upright.height
        val px = IntArray(w * h)
        upright.getPixels(px, 0, w, 0, 0, w, h)
        upright.recycle()

        val small = smallBitmap(px, w, h)
        val faces = runCatching { faceFinder?.invoke(small) }.getOrNull().orEmpty()
        val before = runCatching { aestheticScorer?.invoke(small) }.getOrNull()
        small.recycle()

        val ctx = ShotContext(shot.scene, faces, shot.iso, alreadyProcessed)
        var out = AutoEnhance.enhance(px, w, h, ctx)
        var note = shot.scene.label
        if (before != null) {
            val after = score(out, w, h)
            if (after != null && after < before - TOLERANCE) {
                // La mejora completa no convence: a mitad de fuerza, y si tampoco, no se guarda.
                out = AutoEnhance.enhance(px, w, h, ctx, strength = 0.5f)
                val soft = score(out, w, h)
                if (soft != null && soft < before - TOLERANCE) return null
                note += ", suave"
            }
        }

        var ow = w
        var oh = h
        if (geometry && !shot.front) {
            val roll = shot.rollDeg?.takeIf { abs(it) in 0.5f..MAX_STRAIGHTEN } ?: 0f
            val pitch = shot.pitchDeg ?: 0f
            // Perspectiva solo en arquitectura, mirando algo hacia arriba o hacia abajo.
            val keystone = if (shot.scene == SceneType.CIUDAD && abs(pitch) in 4f..30f && focal35 != null) 0.7f else 0f
            val focalPx = focal35?.let { PhotoGeometry.focalPixels(it.toFloat(), w, h) }
            val plan = PhotoGeometry.plan(w, h, roll, pitch, keystone, focalPx, shot.crop)
            if (plan != null) {
                out = PhotoGeometry.apply(out, w, h, plan)
                ow = plan.width
                oh = plan.height
                note += ", " + plan.description
            }
        }

        val bitmap = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
        return try {
            PhotoSaver.saveProcessed(context, bitmap, 0, bytes, "Mejora automática ($note)", suffix = "_mejorada")
        } finally {
            bitmap.recycle()
        }
    }

    private fun score(px: IntArray, w: Int, h: Int): Float? {
        val scorer = aestheticScorer ?: return null
        val small = smallBitmap(px, w, h)
        return try {
            runCatching { scorer(small) }.getOrNull()
        } finally {
            small.recycle()
        }
    }

    /** Copia reducida (lado mayor ~480 px) tomando un píxel de cada bloque. */
    private fun smallBitmap(px: IntArray, w: Int, h: Int): Bitmap {
        val step = max(1, (max(w, h) / 480f).roundToInt())
        val sw = max(1, w / step)
        val sh = max(1, h / step)
        val out = IntArray(sw * sh)
        for (y in 0 until sh) {
            val row = (y * step + step / 2).coerceAtMost(h - 1) * w
            for (x in 0 until sw) out[y * sw + x] = px[row + (x * step + step / 2).coerceAtMost(w - 1)]
        }
        return Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
    }

    /** Pone la foto derecha según su EXIF (giros y espejos). */
    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap = orientBitmap(bitmap, orientation)

    private companion object {
        /** Bajada de nota (NIMA) que se tolera antes de suavizar la mejora. */
        const val TOLERANCE = 0.1f

        /** Más giro que este se toma como intencionado: no se endereza. */
        const val MAX_STRAIGHTEN = 8f
    }
}
