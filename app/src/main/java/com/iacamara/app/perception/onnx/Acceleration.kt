package com.iacamara.app.perception.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.iacamara.app.BuildConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Dónde se ejecuta un modelo. */
enum class Backend(val label: String) {
    /** Chip de IA del Snapdragon (Hexagon), con Qualcomm AI Engine Direct (QNN). */
    NPU("NPU"),
    /** Gráfica Adreno, con QNN. */
    GPU("GPU"),
    /** Procesador normal: siempre funciona. */
    CPU("CPU"),
}

/**
 * Recuerda, en este móvil y para esta versión de la app, qué acelerador funciona con cada modelo,
 * cuáles fallaron (y por qué) y cuánto tardó cada uno. Hay uno solo por proceso.
 *
 * Protección contra cierres: antes de probar un acelerador se deja una marca para ese modelo. Si la
 * app se cierra de golpe mientras lo prueba (un fallo dentro del controlador del chip, que no se
 * puede capturar), al volver a abrirla ese acelerador queda descartado para ese modelo. Además, si la
 * app se cierra de golpe dos veces seguidas con la cámara abierta y algún modelo acelerado, se
 * desactiva la aceleración; cada sesión que termina bien pone la cuenta a cero.
 */
class AcceleratorStore private constructor(context: Context) {
    private val sp = context.getSharedPreferences("aceleradores", Context.MODE_PRIVATE)

    /** Carpeta del grafo compilado para el NPU (se borra con cada versión nueva). */
    val cacheDir: File = File(context.noBackupFilesDir, "npu")

    init {
        if (sp.getInt(KEY_VERSION, -1) != BuildConfig.VERSION_CODE) {
            // Versión nueva: modelos o librerías nuevas, se vuelve a probar todo.
            sp.edit().clear().putInt(KEY_VERSION, BuildConfig.VERSION_CODE).commit()
            cacheDir.deleteRecursively()
        }
        cacheDir.mkdirs()
        // Marcas de pruebas que no terminaron en el proceso anterior: ese acelerador cerró la app.
        val pending = sp.all.keys.filter { it.startsWith(PROBE_PREFIX) }
        if (pending.isNotEmpty()) {
            val edit = sp.edit()
            for (key in pending) {
                val probe = key.removePrefix(PROBE_PREFIX)
                edit.putBoolean("roto:$probe", true)
                edit.putString("error:$probe", "la app se cerró al probarlo")
                edit.remove(key)
            }
            edit.commit()
        }
        if (sp.getBoolean(KEY_ACTIVE, false)) {
            val crashes = sp.getInt(KEY_CRASHES, 0) + 1
            sp.edit()
                .putInt(KEY_CRASHES, crashes)
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_DISABLED, crashes >= 2)
                .commit()
        }
    }

    /** La aceleración se desactivó por cierres inesperados. */
    val disabled: Boolean get() = sp.getBoolean(KEY_DISABLED, false)

    fun chosen(model: String): Backend? =
        sp.getString("elegido:$model", null)?.let { name -> Backend.entries.firstOrNull { it.name == name } }

    fun choose(model: String, backend: Backend) {
        sp.edit().putString("elegido:$model", backend.name).apply()
    }

    fun isBroken(model: String, backend: Backend): Boolean = sp.getBoolean("roto:$model:${backend.name}", false)

    fun markBroken(model: String, backend: Backend, reason: String) {
        sp.edit()
            .putBoolean("roto:$model:${backend.name}", true)
            .putString("error:$model:${backend.name}", reason.take(500))
            .apply()
    }

    /** Motivo por el que se descartó un acelerador (para el diagnóstico), o null. */
    fun error(model: String, backend: Backend): String? = sp.getString("error:$model:${backend.name}", null)

    /** Milisegundos medidos en la prueba (0 si no se midió). */
    fun measuredMs(model: String, backend: Backend): Float = sp.getFloat("ms:$model:${backend.name}", 0f)

    fun recordMs(model: String, backend: Backend, ms: Float) {
        sp.edit().putFloat("ms:$model:${backend.name}", ms).apply()
    }

    /** Ejecuta `block` con la marca de prueba de ese modelo puesta (se guarda en disco antes de empezar). */
    fun <T> guarded(model: String, backend: Backend, block: () -> T): T {
        val key = "$PROBE_PREFIX$model:${backend.name}"
        sp.edit().putBoolean(key, true).commit()
        try {
            return block()
        } finally {
            sp.edit().remove(key).commit()
        }
    }

    /** Resumen para el informe de diagnóstico: dónde corre cada modelo, tiempos y motivos de descarte. */
    fun report(): String = buildString {
        append("== ACELERACIÓN (NPU / GPU) ==\n")
        if (disabled) append("Desactivada: la app se cerró de golpe dos veces con la cámara abierta.\n")
        val fastRpc = listOf("/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so").any { File(it).exists() }
        append("Acceso al NPU (libcdsprpc): ").append(if (fastRpc) "sí" else "no encontrado").append('\n')
        for ((key, name) in MODELS) {
            append(name).append(": ").append(chosen(key)?.label ?: "sin probar")
            val times = Backend.entries.mapNotNull { b ->
                measuredMs(key, b).takeIf { it > 0f }?.let { "${b.label} ${it.toInt()} ms" }
            }
            if (times.isNotEmpty()) append(" (").append(times.joinToString(", ")).append(')')
            append('\n')
            for (b in listOf(Backend.NPU, Backend.GPU)) {
                error(key, b)?.let { append("   ").append(b.label).append(" descartado: ").append(it).append('\n') }
            }
        }
        append("(YOLO11s solo se usa si va en el NPU o la GPU en 40 ms o menos.)\n")
    }

    /** Cámara abierta con algún modelo acelerado (true) o cerrada/en segundo plano (false). */
    fun setActive(active: Boolean) {
        val edit = sp.edit().putBoolean(KEY_ACTIVE, active)
        // Si la cámara se cierra con normalidad, los cierres anteriores no cuentan.
        if (!active) edit.putInt(KEY_CRASHES, 0)
        edit.apply()
    }

    companion object {
        private const val KEY_VERSION = "version"
        private const val PROBE_PREFIX = "probando:"
        private const val KEY_ACTIVE = "activa"
        private const val KEY_CRASHES = "cierres"
        private const val KEY_DISABLED = "desactivada"

        @Volatile private var instance: AcceleratorStore? = null

        private val MODELS = listOf(
            "u2netp" to "Saliencia (U²-Net)",
            "yoloe" to "YOLOE",
            "yolo11n" to "YOLO11n",
            "yolo11s" to "YOLO11s",
            "nima" to "Estética (NIMA)",
            "clip" to "Escena (CLIP)",
            "ojos" to "Ojos (MediaPipe)",
            "pose" to "Cuerpo (MediaPipe)",
        )

        fun get(context: Context): AcceleratorStore =
            instance ?: synchronized(this) {
                instance ?: AcceleratorStore(context.applicationContext).also { instance = it }
            }
    }
}

/** Crea sesiones de ONNX Runtime para cada acelerador. */
object Sessions {
    @Volatile private var dspConfigured = false

    /** @param bytes el modelo; solo se lee si hace falta (no, si el NPU ya tiene su grafo compilado). */
    fun create(
        context: Context,
        env: OrtEnvironment,
        bytes: () -> ByteArray,
        backend: Backend,
        threads: Int,
        cache: File? = null,
    ): OrtSession {
        if (backend != Backend.CPU) configureDsp(context)
        // NPU: el grafo compilado para el chip se guarda para no recompilarlo en cada arranque.
        if (backend == Backend.NPU && cache != null && cache.exists()) {
            try {
                return options(backend, threads).use { env.createSession(cache.path, it) }
            } catch (e: Throwable) {
                Log.w(TAG, "Caché de NPU no válida, se recompila", e)
                cache.delete()
            }
        }
        return options(backend, threads).use { o ->
            if (backend == Backend.NPU && cache != null) {
                o.addConfigEntry("ep.context_enable", "1")
                o.addConfigEntry("ep.context_embed_mode", "1")
                o.addConfigEntry("ep.context_file_path", cache.path)
            }
            env.createSession(bytes(), o)
        }
    }

    private fun options(backend: Backend, threads: Int): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (backend) {
                Backend.CPU -> setIntraOpNumThreads(threads)
                Backend.NPU -> {
                    // Lo que el chip no sepa hacer se ejecuta en el procesador.
                    setIntraOpNumThreads(2)
                    addQnn(
                        mapOf(
                            "backend_type" to "htp",
                            "htp_performance_mode" to "sustained_high_performance",
                            "enable_htp_fp16_precision" to "1",
                            "htp_graph_finalization_optimization_mode" to "3",
                        ),
                    )
                }
                Backend.GPU -> {
                    setIntraOpNumThreads(2)
                    addQnn(mapOf("backend_type" to "gpu"))
                }
            }
        }

    /** El chip de IA carga su parte del controlador desde las librerías de la app. */
    private fun configureDsp(context: Context) {
        if (dspConfigured) return
        dspConfigured = true
        val dirs = listOf(
            context.applicationInfo.nativeLibraryDir,
            "/vendor/lib/rfsa/adsp", "/vendor/dsp/cdsp", "/system/lib/rfsa/adsp", "/system/vendor/lib/rfsa/adsp", "/dsp",
        )
        runCatching { Os.setenv("ADSP_LIBRARY_PATH", dirs.joinToString(";"), true) }
    }

    private const val TAG = "Aceleracion"
}

/**
 * Prueba en segundo plano los aceleradores del móvil con cada modelo y se queda con el más rápido
 * que dé el mismo resultado que el procesador normal. Mientras tanto, y si nada funciona, los modelos
 * siguen en el procesador normal: la app nunca se queda sin IA.
 *
 * Todas las pruebas van en un único hilo por proceso (aunque se abra y cierre la cámara), para que
 * nunca se compile el mismo modelo dos veces a la vez.
 */
class Accelerator(private val store: AcceleratorStore) : AutoCloseable {
    @Volatile private var closed = false

    /** Mientras se mide la velocidad, el visor pausa los demás modelos para que la comparación sea justa. */
    val measuring: Boolean get() = measuringFlag && !closed

    /** Lo que se está probando ahora ("saliencia en el NPU"), para el panel, o null. */
    val probing: String? get() = probingNow.takeIf { !closed }

    /**
     * @param check compara la salida del procesador normal con la del acelerador.
     * @param informative la salida del procesador sirve para comparar (p. ej., un detector que no ve
     *   nada no sirve: cualquier acelerador roto "coincidiría").
     * @param accept condición extra para aceptar un acelerador (backend, ms acelerado, ms en CPU).
     * @param sampleFrom modelo del que tomar una imagen real de prueba (por defecto, el mismo).
     * @param onDone se llama exactamente una vez con el resultado (en el hilo del acelerador).
     */
    class Job(
        val model: OnnxModel,
        val check: (Any, Any) -> Boolean,
        val informative: (Any) -> Boolean = { true },
        val accept: (Backend, Float, Float) -> Boolean = { _, _, _ -> true },
        val sampleFrom: OnnxModel = model,
        val onDone: (Backend, Float) -> Unit = { _, _ -> },
    )

    fun optimize(job: Job) {
        val done = AtomicBoolean(false)
        val finish = { backend: Backend, ms: Float -> if (done.compareAndSet(false, true)) job.onDone(backend, ms) }
        if (store.disabled) {
            finish(Backend.CPU, 0f)
            return
        }
        submit(job, finish, attempt = 0)
    }

    /**
     * Pone el trabajo en la cola. Si todavía no hay una imagen útil para compararlo (p. ej. un detector
     * a oscuras, sin nada que detectar), vuelve al final de la cola para no bloquear a los demás modelos.
     */
    private fun submit(job: Job, finish: (Backend, Float) -> Unit, attempt: Int) {
        try {
            executor.execute {
                var retry = false
                try {
                    retry = process(job, finish) && attempt < MAX_ATTEMPTS && !closed
                } catch (e: Throwable) {
                    Log.w(TAG, "No se pudo acelerar ${job.model.key}", e)
                } finally {
                    probingNow = null
                    measuringFlag = false
                    if (retry) submit(job, finish, attempt + 1) else finish(Backend.CPU, 0f)
                }
            }
        } catch (e: RejectedExecutionException) {
            finish(Backend.CPU, 0f)
        }
    }

    /** @return true si hay que volver a intentarlo más tarde (aún no había una imagen útil). */
    private fun process(job: Job, finish: (Backend, Float) -> Unit): Boolean {
        val m = job.model
        val remembered = store.chosen(m.key)
        if (remembered == Backend.CPU || closed) return false
        if (remembered != null && !store.isBroken(m.key, remembered)) {
            val sample = waitSample(job.sampleFrom) ?: return true
            // Ya se probó en este móvil: se activa directamente (con la marca puesta por si acaso).
            probingNow = "${m.key} ${remembered.label}"
            val session = runCatching {
                store.guarded(m.key, remembered) {
                    create(m, remembered).also { s ->
                        try {
                            m.runSample(s, sample)
                        } catch (e: Throwable) {
                            s.close()
                            throw e
                        }
                    }
                }
            }
            if (closed) {
                session.getOrNull()?.close()
                return false
            }
            session.getOrNull()?.let {
                m.swap(it, remembered)
                finish(remembered, store.measuredMs(m.key, remembered))
                return false
            }
            store.markBroken(m.key, remembered, describe(session.exceptionOrNull()))
        }

        // Primera vez: una imagen real con la que se pueda comparar, y su referencia en el procesador.
        val (sample, reference) = informativeSample(job) ?: return true
        val cpuMs = measure { m.runSample(null, sample) }
        store.recordMs(m.key, Backend.CPU, cpuMs)

        var best: Pair<Backend, OrtSession>? = null
        var bestMs = cpuMs
        for (backend in listOf(Backend.NPU, Backend.GPU)) {
            if (closed) break
            if (store.isBroken(m.key, backend)) continue
            probingNow = "${m.key} ${backend.label}"
            val result = runCatching {
                store.guarded(m.key, backend) {
                    val s = create(m, backend)
                    try {
                        val out = m.runSample(s, sample) // la primera vez también compila
                        check(job.check(reference, out)) { "resultado distinto del procesador normal" }
                        s to measure { m.runSample(s, sample) }
                    } catch (e: Throwable) {
                        s.close()
                        throw e
                    }
                }
            }
            result.exceptionOrNull()?.let { e ->
                Log.w(TAG, "${m.key} no funciona en ${backend.label}: ${e.message}")
                store.markBroken(m.key, backend, describe(e))
            }
            val (session, ms) = result.getOrNull() ?: continue
            store.recordMs(m.key, backend, ms)
            if (ms < bestMs * 0.85f && job.accept(backend, ms, cpuMs)) {
                best?.second?.close()
                best = backend to session
                bestMs = ms
            } else {
                session.close()
            }
        }
        val chosen = best
        if (chosen != null && !closed) {
            m.swap(chosen.second, chosen.first)
            store.choose(m.key, chosen.first)
            Log.i(TAG, "${m.key}: ${chosen.first.label} ${bestMs.toInt()} ms (CPU ${cpuMs.toInt()} ms)")
            finish(chosen.first, bestMs)
        } else {
            chosen?.second?.close()
            if (!closed) store.choose(m.key, Backend.CPU)
            finish(Backend.CPU, cpuMs)
        }
        return false
    }

    /** Una imagen real cuya salida en el procesador sirve para comparar, con esa salida. */
    private fun informativeSample(job: Job): Pair<OnnxModel.Sample, Any>? {
        val deadline = SystemClock.elapsedRealtime() + INFORMATIVE_TIMEOUT_MS
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            val candidate = waitSample(job.sampleFrom) ?: return null
            val out = job.model.runSample(null, candidate)
            if (job.informative(out)) return candidate to out
            job.sampleFrom.discardSample()
        }
        return null
    }

    private fun create(m: OnnxModel, backend: Backend): OrtSession {
        val cache = if (backend == Backend.NPU) File(store.cacheDir, "${m.key}.onnx") else null
        return try {
            m.createSession(backend, cache)
        } catch (e: Throwable) {
            if (cache == null) throw e
            // Puede fallar al guardar la caché: se intenta una vez sin ella.
            cache.delete()
            m.createSession(backend, null)
        }
    }

    /** Espera a que el analizador use el modelo con una imagen real. */
    private fun waitSample(m: OnnxModel): OnnxModel.Sample? {
        m.requestSample()
        val deadline = SystemClock.elapsedRealtime() + SAMPLE_TIMEOUT_MS
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            m.sample()?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    /** Milisegundos medios de 5 ejecuciones, con el visor en pausa (tras una de calentamiento). */
    private inline fun measure(block: () -> Unit): Float {
        measuringFlag = true
        try {
            Thread.sleep(150) // que termine lo que estuviera haciendo el visor
            block()
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(5) { block() }
            return (SystemClock.elapsedRealtimeNanos() - start) / 5e6f
        } finally {
            measuringFlag = false
        }
    }

    private fun describe(e: Throwable?): String {
        val root = generateSequence(e) { it.cause }.lastOrNull() ?: return "error desconocido"
        return "${root.javaClass.simpleName}: ${root.message ?: ""}".trim()
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val TAG = "Aceleracion"
        const val SAMPLE_TIMEOUT_MS = 120_000L

        /** Tiempo que se espera una imagen útil antes de pasar al siguiente modelo y volver luego. */
        const val INFORMATIVE_TIMEOUT_MS = 25_000L

        /** Veces que un modelo vuelve a la cola esperando una imagen útil (en total ~2,5 min). */
        const val MAX_ATTEMPTS = 6

        /** Un solo hilo de pruebas para toda la app. */
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "acelerador").apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }

        @Volatile var measuringFlag = false
        @Volatile var probingNow: String? = null
    }
}
