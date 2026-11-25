package com.gravity.ide.ai.local

import android.app.ActivityManager
import android.content.Context
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.LocalModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds at most one loaded local model (phones rarely have RAM for two). Loading another
 * model unloads the current one first; the orchestrator calls [unload] under memory pressure.
 */
/** The phone model answered wrongly in every way Gravity can load it (see [LocalModelHost.load]). */
class ModelUnhealthyException(message: String) : IllegalStateException(message)

class LocalModelHost(private val context: Context, private val events: EventLog) {
    private companion object {
        const val KEY_LAST_MODEL = "last_model"
    }

    data class Loaded(val modelId: String, val name: String, val handle: Long, val contextSize: Int, val loadedAt: Long)

    sealed interface State {
        data object Idle : State
        data class Loading(val name: String) : State
        data class Ready(val loaded: Loaded) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()

    val runtimeAvailable: Boolean get() = LlamaBridge.isAvailable
    val runtimeError: String? get() = LlamaBridge.loadError

    fun availableRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** Returns a warning when the model probably does not fit in currently free RAM. */
    fun memoryWarning(model: LocalModelEntity, contextSize: Int): String? {
        val need = com.gravity.ide.core.GgufReader.estimateRamBytes(model.sizeBytes, contextSize)
        val avail = availableRamBytes()
        return if (need > avail) {
            "Estimated need ${need / (1024 * 1024)} MB exceeds currently available ${avail / (1024 * 1024)} MB. Loading may fail or the system may kill Gravity."
        } else {
            null
        }
    }

    /** How a model is loaded on this phone: engine, file mapped (mmap) or read into memory, threads (0 = normal). */
    private data class LoadConfig(val engine: EngineKind, val mmap: Boolean, val threads: Int) {
        override fun toString() = "${engine.name}:$mmap:$threads"
        fun describe(t: Int) = "${engine.label}, ${if (mmap) "file mapped" else "read into memory"}, $t thread(s)"
    }

    private fun parseConfig(s: String?): LoadConfig? {
        val p = s?.split(':') ?: return null
        return when (p.size) {
            2 -> LoadConfig(EngineKind.BASE, p[0].toBoolean(), p[1].toIntOrNull() ?: 0) // saved by older versions
            3 -> runCatching { EngineKind.valueOf(p[0]) }.getOrNull()?.let { LoadConfig(it, p[1].toBoolean(), p[2].toIntOrNull() ?: 0) }
            else -> null
        }?.takeIf { it.engine in LlamaBridge.engines }
    }

    /**
     * Loads [model]. A chat model is checked with two easy questions the first time it is loaded
     * in a given way. If the answers are wrong (the model writes random text on some phones), it
     * is loaded again another way: on each engine this phone can run ([LlamaBridge.engines]: the
     * fast Armv8.2 build, then the standard one), file mapped, read into memory, then with 1
     * thread. The way that works is remembered for this model. If none works, the model is not
     * used and [ModelUnhealthyException] explains why, instead of showing garbage in chats.
     */
    suspend fun load(model: LocalModelEntity, contextSize: Int, threads: Int): Loaded = mutex.withLock {
        if (!LlamaBridge.isAvailable) throw IllegalStateException(LlamaBridge.loadError ?: "Local runtime unavailable")
        val file = File(model.filePath)
        if (!file.isFile) throw IllegalStateException("Model file missing: ${model.filePath}")
        (state.value as? State.Ready)?.let { current ->
            if (current.loaded.modelId == model.id) return@withLock current.loaded
            unloadLocked()
        }
        _state.value = State.Loading(model.name)
        val checkable = model.hasChatTemplate || Regex("(?i)instruct|chat|-it\\b").containsMatchIn(model.name + " " + file.name)
        val known = parseConfig(prefs.getString("cfg_${model.id}", null))
        val ladder = (listOfNotNull(known) + LlamaBridge.engines.flatMap { e ->
            listOf(LoadConfig(e, true, 0), LoadConfig(e, false, 0), LoadConfig(e, false, 1))
        }).distinct()
        var lastAnswer = ""
        val attempts = ArrayList<String>()
        lastAttempts = attempts
        for (cfg in ladder) {
            val t = if (cfg.threads > 0) cfg.threads else threads
            val handle = try {
                withContext(Dispatchers.Default) { LlamaBridge.load(file.absolutePath, contextSize, t, 0, cfg.mmap, cfg.engine) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += "${cfg.describe(t)}: could not start (${e.message})"
                continue
            } catch (e: LinkageError) {
                // A missing or broken engine library: note it and try the next way instead of crashing.
                attempts += "${cfg.describe(t)}: engine library problem (${e.message})"
                continue
            }
            if (handle == 0L && cfg.engine != EngineKind.BASE) {
                attempts += "${cfg.describe(t)}: did not load (${LlamaBridge.lastError()})"
                continue
            }
            if (handle == 0L) {
                val err = LlamaBridge.lastError().ifBlank { "llama.cpp failed to load the model" }
                _state.value = State.Failed(err)
                events.log(EventCategory.MODEL, "Failed to load ${model.name}", err, EventLevel.ERROR)
                throw IllegalStateException(err)
            }
            val loaded = Loaded(model.id, model.name, handle, contextSize, System.currentTimeMillis())
            val verified = prefs.getString("ok_${model.id}", null) == cfg.toString()
            if (checkable && !verified) {
                _state.value = State.Loading("${model.name} (checking it answers correctly)")
                val answer = quickCheck(handle)
                if (answer == null) {
                    attempts += "${cfg.describe(t)}: correct"
                    prefs.edit().putString("ok_${model.id}", cfg.toString()).apply()
                } else {
                    attempts += "${cfg.describe(t)}: WRONG → \"${answer.take(70)}\""
                    lastAnswer = answer
                    events.log(
                        EventCategory.MODEL, "${model.name} answered wrongly (${cfg.describe(t)})",
                        "Asked \"What is the capital of France?\" and \"What is 12 + 30?\"; it answered: ${answer.take(300)}", EventLevel.WARN,
                    )
                    LlamaBridge.unload(handle)
                    continue
                }
            }
            if (cfg != known) prefs.edit().putString("cfg_${model.id}", cfg.toString()).apply()
            _state.value = State.Ready(loaded)
            prefs.edit().putString(KEY_LAST_MODEL, model.id).apply()
            events.log(
                EventCategory.MODEL, "Loaded local model ${model.name}",
                "context=$contextSize threads=$t mmap=${cfg.mmap} engine=${cfg.engine.label}" +
                    if (!cfg.mmap || cfg.threads > 0) " (safe mode for this phone)" else "",
            )
            return@withLock loaded
        }
        val msg = "${model.name} gives broken answers on this phone in every way Gravity can run it " +
            "(${LlamaBridge.engines.joinToString(" and ") { it.label }}; file mapped, read into memory, 1 thread) " +
            "(asked \"What is the capital of France?\", it answered \"${lastAnswer.take(80)}\"). " +
            if (model.sha256Expected == null) {
                // Random words in every mode usually mean damaged weights; this copy was never checked against Hugging Face.
                "This file was never checked against Hugging Face's checksum (imported, or downloaded by link), so it is probably damaged. " +
                    "Delete it and download the model again from Phone Models → Recommended (checked with Hugging Face's SHA-256)."
            } else {
                "Use Gemini, or try another model or quantization (Phone Models → Check the phone AI shows the details)."
            }
        _state.value = State.Failed(msg)
        events.log(EventCategory.MODEL, "Not using ${model.name}: wrong answers in every mode", msg, EventLevel.ERROR)
        throw ModelUnhealthyException(msg)
    }

    /** What the last load tried (mode → result), for Phone Models → Check the phone AI. */
    @Volatile
    var lastAttempts: List<String> = emptyList()
        private set

    /** Asks two easy questions; returns null if either answer is right, else what the model said. */
    private suspend fun quickCheck(handle: Long): String? = generationMutex.withLock {
        withContext(Dispatchers.Default) {
            val answers = ArrayList<String>()
            for ((question, expect) in listOf("What is the capital of France? Answer with one word." to "paris", "What is 12 + 30? Reply with the number only." to "42")) {
                val out = StringBuilder()
                val utf8 = com.gravity.ide.core.Utf8Accumulator()
                LlamaBridge.generate(
                    handle, arrayOf("system", "user"), arrayOf("You are a helpful assistant. Answer briefly.", question),
                    12, 0.1f, 0.95f, 0, "",
                ) { bytes -> out.append(utf8.append(bytes)); true }
                out.append(utf8.flush())
                val answer = out.toString().trim()
                if (answer.contains(expect, ignoreCase = true)) return@withContext null
                answers += answer
            }
            answers.joinToString(" | ")
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        val current = state.value as? State.Ready ?: return
        LlamaBridge.unload(current.loaded.handle)
        _state.value = State.Idle
        events.log(EventCategory.MODEL, "Unloaded ${current.loaded.name}")
    }

    /** Serialises generation: llama.cpp contexts are not thread-safe. */
    val generationMutex = Mutex()

    fun loadedOrNull(): Loaded? = (state.value as? State.Ready)?.loaded

    private val prefs by lazy { context.getSharedPreferences("local_model_host", Context.MODE_PRIVATE) }

    /** How [modelId] is loaded on this phone, for diagnostics ("file mapped" / "read into memory, 1 thread"). */
    fun loadMode(modelId: String): String {
        val cfg = parseConfig(prefs.getString("cfg_${modelId}", null)) ?: return "not checked yet"
        return cfg.engine.label + ", " + (if (cfg.mmap) "file mapped" else "read into memory (safe mode)") +
            if (cfg.threads > 0) ", ${cfg.threads} thread(s)" else ""
    }

    /** The model that was loaded last (kept across restarts). */
    fun lastModelId(): String? = prefs.getString(KEY_LAST_MODEL, null)

    /**
     * Loads a phone model when the AI is needed and none is loaded (e.g. after Gravity restarted):
     * the one used last, else the default, else the largest ready model that fits this phone.
     * Returns null when there is nothing suitable to load.
     */
    suspend fun autoLoad(candidates: List<LocalModelEntity>, settings: com.gravity.ide.data.settings.AppSettings): Loaded? {
        loadedOrNull()?.let { return it }
        if (!LlamaBridge.isAvailable) return null
        val inspector = com.gravity.ide.device.DeviceInspector(context)
        val info = inspector.info()
        val ready = candidates.filter { it.status == "READY" && File(it.filePath).isFile }
        val last = lastModelId()
        val pick = ready.firstOrNull { it.id == last } ?: ready.firstOrNull { it.isDefault }
            ?: ready.filter { it.sizeBytes in 1..inspector.recommendedMaxModelBytes() }.maxByOrNull { it.sizeBytes }
            ?: return null
        val ctx = com.gravity.ide.device.PerformanceManager.contextSize(settings, info)
        // A model the user loaded before is trusted; any other one only if it clearly fits in free memory.
        if (pick.id != last && memoryWarning(pick, ctx) != null) return null
        events.log(EventCategory.MODEL, "Loading ${pick.name} automatically", "The AI was needed and no phone model was loaded")
        return try {
            load(pick, ctx, com.gravity.ide.device.PerformanceManager.threads(settings, info))
        } catch (e: ModelUnhealthyException) {
            throw e // say why, instead of "no model"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Context to use for one long answer from the loaded model: up to [wanted] tokens when free
     * memory allows, otherwise the size it was loaded with.
     */
    fun contextForLongAnswer(loaded: Loaded, wanted: Int): Int {
        val info = runCatching { LlamaBridge.modelInfo(loaded.handle) }.getOrNull() ?: return loaded.contextSize
        if (info.size < 5) return loaded.contextSize
        val perToken = com.gravity.ide.core.ContextPlanner.bytesPerToken(info[1], info[2], info[3], info[4])
        return com.gravity.ide.core.ContextPlanner.choose(loaded.contextSize, wanted, info[0], perToken, availableRamBytes())
    }
}
