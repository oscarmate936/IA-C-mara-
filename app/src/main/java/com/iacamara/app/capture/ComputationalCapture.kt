package com.iacamara.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import com.iacamara.app.imaging.FrameSource
import com.iacamara.app.imaging.LumaPlane
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Una foto de la ráfaga tal como la entrega la cámara (JPEG) y su giro. */
class CapturedFrame(val jpeg: ByteArray, val rotationDegrees: Int)

/** Hace una foto y la devuelve en memoria (sin guardarla), para las técnicas de varias fotos. */
suspend fun ImageCapture.captureJpeg(executor: Executor): CapturedFrame = suspendCancellableCoroutine { cont ->
    takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                cont.resume(CapturedFrame(bytes, image.imageInfo.rotationDegrees))
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            } finally {
                image.close()
            }
        }

        override fun onError(exception: ImageCaptureException) {
            cont.resumeWithException(exception)
        }
    })
}

/**
 * Fotograma JPEG que se decodifica por franjas (BitmapRegionDecoder): así se pueden fusionar 8 fotos
 * de 12 MP sin tenerlas todas descomprimidas en memoria.
 */
class JpegFrameSource(private val jpeg: ByteArray) : FrameSource, AutoCloseable {
    private val decoder: BitmapRegionDecoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
    private val lumaCache = HashMap<Int, LumaPlane>()

    override val width: Int = decoder.width
    override val height: Int = decoder.height

    override fun readRegion(x: Int, y: Int, w: Int, h: Int): IntArray {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = synchronized(decoder) { decoder.decodeRegion(Rect(x, y, x + w, y + h), options) }
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return out
    }

    override fun luma(scale: Int): LumaPlane = synchronized(lumaCache) {
        lumaCache.getOrPut(scale) {
            val bitmap = decodeSmall(scale)
            val px = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val plane = LumaPlane.fromArgb(px, bitmap.width, bitmap.height, 1)
            bitmap.recycle()
            plane
        }
    }

    /** La foto reducida (escala potencia de 2), sin girar. */
    fun decodeSmall(scale: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = scale
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    override fun close() {
        decoder.recycle()
    }
}

/** Gira un bitmap para verlo derecho (para analizar caras en la foto reducida). */
fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

/** Guarda fotos en DCIM/IA Camara con sus datos EXIF. */
object PhotoSaver {
    const val PHOTO_DIR = "DCIM/IA Camara"

    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
    )

    private fun newEntry(context: Context): Uri? {
        val name = "IA_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Guarda un JPEG tal cual (la mejor foto de una ráfaga: conserva su calidad y su EXIF). */
    fun saveJpeg(context: Context, jpeg: ByteArray): Uri? {
        val uri = newEntry(context) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
        publish(context, uri)
        return uri
    }

    /**
     * Guarda una foto procesada (noche, HDR). Los píxeles están en la orientación del sensor:
     * el giro se indica en el EXIF, igual que hace la cámara. Se copian los datos de la foto de referencia.
     */
    fun saveProcessed(
        context: Context,
        bitmap: Bitmap,
        rotationDegrees: Int,
        referenceJpeg: ByteArray?,
        software: String,
    ): Uri? {
        val uri = newEntry(context) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            if (referenceJpeg != null) {
                runCatching {
                    val source = ExifInterface(ByteArrayInputStream(referenceJpeg))
                    for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
                }
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation(rotationDegrees).toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "IA Cámara · $software")
            exif.saveAttributes()
        }
        publish(context, uri)
        return uri
    }

    private fun orientation(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
