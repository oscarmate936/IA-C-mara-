package com.iacamara.app.perception.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.iacamara.app.BuildConfig
import java.io.File
import java.util.concurrent.Executors

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
 * Recuerda, en este móvil y para esta versión de la app, qué acelerador funciona con cada modelo y
 * cuáles fallaron.
 *
 * Protección contra cierres: antes de probar un acelerador se deja una marca. Si la app se cierra de
 * golpe mientras lo prueba (un fallo dentro del controlador del chip, que no se puede capturar), al
 * volver a abrirla ese acelerador queda descartado para ese modelo. Además, si la app se cierra de
 * golpe dos veces con la cámara abierta y algún modelo acelerado, se desactiva la aceleración entera.
 */
class AcceleratorStore(context: Context) {
    private val sp = context.getSharedPreferences("aceleradores", Context.MODE_PRIVATE)

    init {
        if (sp.getInt(KEY_VERSION, -1) != BuildConfig.VERSION_CODE) {
            // Versión nueva: modelos o librerías nuevas, se vuelve a probar todo.
            sp.edit().clear().putInt(KEY_VERSION, BuildConfig.VERSION_CODE).commit()
        }
        sp.getString(KEY_PROBE, null)?.let { probe ->
            sp.edit().putBoolean("roto:$probe", true).remove(KEY_PROBE).commit()
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

    fun markBroken(model: String, backend: Backend) {
        sp.edit().putBoolean("roto:$model:${backend.name}", true).apply()
    }

    /** Ejecuta `block` con la marca de prueba puesta (se guarda en disco antes de empezar). */
    fun <T> guarded(model: String, backend: Backend, block: () -> T): T {
        sp.edit().putString(KEY_PROBE, "$model:${backend.name}").commit()
        try {
            return block()
        } finally {
            sp.edit().remove(KEY_PROBE).commit()
        }
    }

    /** Cámara abierta con algún modelo acelerado (true) o cerrada/en segundo plano (false). */
    fun setActive(active: Boolean) {
        sp.edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_PROBE = "probando"
        const val KEY_ACTIVE = "activa"
        const val KEY_CRASHES = "cierres"
        const val KEY_DISABLED = "desactivada"
    }
}

/** Crea sesiones de ONNX Runtime para cada acelerador. */
object Sessions {
    @Volatile private var dspConfigured = false

    fun create(
        context: Context,
        env: OrtEnvironment,
        bytes: ByteArray,
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
            env.createSession(bytes, o)
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
 */
class Accelerator(context: Context, private val store: AcceleratorStore) : AutoCloseable {
    private val cacheDir = File(context.noBackupFilesDir, "npu").apply { mkdirs() }
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "acelerador").apply { priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var closed = false

    /**
     * @param check compara la salida del procesador normal con la del acelerador.
     * @param accept condición extra para aceptar un acelerador (milisegundos acelerado, milisegundos en CPU).
     * @param sampleFrom modelo del que tomar una imagen real de prueba (por defecto, el mismo).
     * @param onDone se llama con el resultado (en el hilo del acelerador).
     */
    class Job(
        val model: OnnxModel,
        val check: (Any, Any) -> Boolean,
        val accept: (Backend, Float, Float) -> Boolean = { _, _, _ -> true },
        val sampleFrom: OnnxModel = model,
        val onDone: (Backend, Float) -> Unit = { _, _ -> },
    )

    fun optimize(job: Job) {
        if (store.disabled) {
            job.onDone(Backend.CPU, 0f)
            return
        }
        executor.execute {
            try {
                process(job)
            } catch (e: Throwable) {
                Log.w(TAG, "No se pudo acelerar ${job.model.key}", e)
            }
        }
    }

    private fun process(job: Job) {
        val m = job.model
        val remembered = store.chosen(m.key)
        if (remembered == Backend.CPU) {
            job.onDone(Backend.CPU, 0f)
            return
        }
        val sample = waitSample(job.sampleFrom) ?: return
        if (remembered != null && !store.isBroken(m.key, remembered)) {
            // Ya se probó en este móvil: se activa directamente (con la marca puesta por si acaso).
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
            }.getOrNull()
            if (closed) {
                session?.close()
                return
            }
            if (session != null) {
                m.swap(session, remembered)
                job.onDone(remembered, 0f)
                return
            }
            store.markBroken(m.key, remembered)
        }

        // Primera vez: referencia en el procesador normal.
        val reference = m.runSample(null, sample)
        val cpuMs = timeMs { m.runSample(null, sample) }
        var best: Pair<Backend, OrtSession>? = null
        var bestMs = cpuMs
        for (backend in listOf(Backend.NPU, Backend.GPU)) {
            if (closed) break
            if (store.isBroken(m.key, backend)) continue
            val result = runCatching {
                store.guarded(m.key, backend) {
                    val s = create(m, backend)
                    try {
                        val out = m.runSample(s, sample) // la primera vez también compila
                        check(job.check(reference, out)) { "resultado distinto del procesador normal" }
                        s to timeMs { m.runSample(s, sample) }
                    } catch (e: Throwable) {
                        s.close()
                        throw e
                    }
                }
            }
            result.exceptionOrNull()?.let { e ->
                Log.w(TAG, "${m.key} no funciona en ${backend.label}: ${e.message}")
                store.markBroken(m.key, backend)
            }
            val (session, ms) = result.getOrNull() ?: continue
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
            job.onDone(chosen.first, bestMs)
        } else {
            chosen?.second?.close()
            if (!closed) store.choose(m.key, Backend.CPU)
            job.onDone(Backend.CPU, cpuMs)
        }
    }

    private fun create(m: OnnxModel, backend: Backend): OrtSession {
        val cache = if (backend == Backend.NPU) File(cacheDir, "${m.key}.onnx") else null
        return try {
            m.createSession(backend, cache)
        } catch (e: Throwable) {
            if (cache == null) throw e
            // Puede fallar al guardar la caché: se intenta una vez sin ella.
            cache.delete()
            m.createSession(backend, null)
        }
    }

    /** Espera (hasta 2 minutos) a que el analizador use el modelo con una imagen real. */
    private fun waitSample(m: OnnxModel): OnnxModel.Sample? {
        m.requestSample()
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            m.sample()?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private inline fun timeMs(block: () -> Unit): Float {
        block() // calentamiento
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(3) { block() }
        return (SystemClock.elapsedRealtimeNanos() - start) / 3e6f
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }

    private companion object {
        const val TAG = "Aceleracion"
    }
}
