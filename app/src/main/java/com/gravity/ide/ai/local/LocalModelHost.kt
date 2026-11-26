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
        /** "engine|modelId|start time" while native code runs; still set at the next start = Gravity died in it. */
        const val KEY_NATIVE_BUSY = "native_busy"
        /** "versionCode|reason": the fast engine crashed with this app version, so it is not used. */
        const val KEY_FAST_OFF = "fast_off"
        /** A model that crashed Gravity (standard engine): not loaded automatically until loaded by hand. */
        const val KEY_CRASHED_MODEL = "crashed_model"
    }

    private val prefs by lazy { context.getSharedPreferences("local_model_host", Context.MODE_PRIVATE) }

    /** Why the phone-model engine changed after a crash, for Phone Models (null if nothing happened). */
    @Volatile
    var crashNotice: String? = null
        private set

    init {
        prefs.getString(KEY_FAST_OFF, null)?.split('|', limit = 2)?.let { (version, reason) ->
            if (version == com.gravity.ide.BuildConfig.VERSION_CODE.toString()) LlamaBridge.turnOffFast(reason)
            else prefs.edit().remove(KEY_FAST_OFF).apply() // a new version may have fixed it: try again
        }
        runCatching { checkLastExit() }
    }

    /**
     * If Gravity was closed by a crash inside the phone-model engine last time (Android's exit
     * record says "native crash" while a model was running), avoid a crash loop: the fast engine
     * is switched off for this app version, or a model that crashed the standard engine is not
     * loaded automatically again.
     */
    private fun checkLastExit() {
        val busy = prefs.getString(KEY_NATIVE_BUSY, null) ?: return
        prefs.edit().remove(KEY_NATIVE_BUSY).commit()
        val parts = busy.split('|')
        val engine = runCatching { EngineKind.valueOf(parts[0]) }.getOrNull() ?: return
        val modelId = parts.getOrNull(1).orEmpty()
        val since = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val crashed = if (android.os.Build.VERSION.SDK_INT >= 30) {
            val am = context.getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.timestamp >= since }?.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
        } else {
            true // no exit records before Android 11: the engine was running when Gravity died
        }
        if (!crashed) return
        if (engine == EngineKind.FAST) {
            val reason = "it crashed Yamini on this phone"
            prefs.edit().putString(KEY_FAST_OFF, "${com.gravity.ide.BuildConfig.VERSION_CODE}|$reason").commit()
            LlamaBridge.turnOffFast(reason)
            crashNotice = "Yamini closed because the fast phone-model engine crashed. It now uses the standard engine (a little slower)."
        } else {
            prefs.edit().putString(KEY_CRASHED_MODEL, modelId).commit()
            crashNotice = "Yamini closed while running a phone model. It will not load that model by itself again; load it from Phone Models to retry " +
                "(close other apps first if memory is low), or use Gemini."
        }
        events.log(EventCategory.MODEL, "Phone-model engine crashed last time", crashNotice.orEmpty(), EventLevel.WARN)
    }

    /** Runs native engine work, noting it first so a crash inside it is recognised at the next start. */
    fun <T> nativeWork(engine: EngineKind, modelId: String, doing: String = "", block: () -> T): T {
        // [doing] (e.g. "writing, context 4096, up to 2500 tokens") goes into the crash report.
        prefs.edit().putString(KEY_NATIVE_BUSY, "${engine.name}|$modelId|${System.currentTimeMillis()}|${doing.replace('|', '/')}").commit()
        // Keeps the CPU awake while the model loads or writes: with the screen off the phone may
        // otherwise sleep and a reply crawls (a user waited 5–15 minutes for a drawing). Released
        // as soon as it is done; at most 30 minutes in any case.
        val wake = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Gravity:phone-model")
                .apply { setReferenceCounted(false); acquire(30 * 60_000L) }
        }.getOrNull()
        try {
            return block()
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
            prefs.edit().remove(KEY_NATIVE_BUSY).commit()
        }
    }

    /** Set while a model is being unloaded: a reply still being written stops at its next word. */
    @Volatile
    var abortRequested = false
        private set

    /** [fileBytes]: the model file's size; its pages sit in memory while it runs (memory planning). */
    data class Loaded(val modelId: String, val name: String, val handle: Long, val contextSize: Int, val loadedAt: Long, val fileBytes: Long = 0)

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
            "Estimated need ${need / (1024 * 1024)} MB exceeds currently available ${avail / (1024 * 1024)} MB. Loading may fail or the system may kill Yamini."
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
        if (prefs.getString(KEY_CRASHED_MODEL, null) == model.id) prefs.edit().remove(KEY_CRASHED_MODEL).apply() // loaded on purpose: try again
        (state.value as? State.Ready)?.let { current ->
            if (current.loaded.modelId == model.id) return@withLock current.loaded
            // Another chat may still be answering with the current model: let it finish, then switch.
            unloadLocked(abort = false)
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
                withContext(Dispatchers.Default) { nativeWork(cfg.engine, model.id, "loading ${model.name}, context $contextSize, ${t} threads") { LlamaBridge.load(file.absolutePath, contextSize, t, 0, cfg.mmap, cfg.engine) } }
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
            val loaded = Loaded(
                model.id, model.name, handle, contextSize, System.currentTimeMillis(),
                fileBytes = model.sizeBytes.takeIf { it > 0 } ?: runCatching { java.io.File(model.filePath).length() }.getOrDefault(0L),
            )
            val verified = prefs.getString("ok_${model.id}", null) == cfg.toString()
            if (checkable && !verified) {
                _state.value = State.Loading("${model.name} (checking it answers correctly)")
                val answer = quickCheck(handle, cfg.engine, model.id)
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
        val msg = "${model.name} gives broken answers on this phone in every way Yamini can run it " +
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
    private suspend fun quickCheck(handle: Long, engine: EngineKind, modelId: String): String? = generationMutex.withLock {
        withContext(Dispatchers.Default) {
            val answers = ArrayList<String>()
            for ((question, expect) in listOf("What is the capital of France? Answer with one word." to "paris", "What is 12 + 30? Reply with the number only." to "42")) {
                val out = StringBuilder()
                val utf8 = com.gravity.ide.core.Utf8Accumulator()
                nativeWork(engine, modelId) {
                    LlamaBridge.generate(
                        handle, arrayOf("system", "user"), arrayOf("You are a helpful assistant. Answer briefly.", question),
                        12, 0.1f, 0.95f, 0, "",
                    ) { bytes -> out.append(utf8.append(bytes)); true }
                }
                out.append(utf8.flush())
                val answer = out.toString().trim()
                if (answer.contains(expect, ignoreCase = true)) return@withContext null
                answers += answer
            }
            answers.joinToString(" | ")
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked(abort = true) }

    /** Finds a downloaded, ready model by id or name (set by AppContainer). */
    @Volatile
    var lookup: (suspend (String) -> LocalModelEntity?)? = null

    /** Current settings (set by AppContainer), for context size and threads when loading. */
    @Volatile
    var settingsSource: (suspend () -> com.gravity.ide.data.settings.AppSettings)? = null

    /**
     * Makes sure [model] is the loaded model (each chat keeps its own phone model): loads it if
     * another one is loaded, after any reply still being written with that one has finished.
     */
    suspend fun ensure(model: LocalModelEntity): Loaded {
        loadedOrNull()?.takeIf { it.modelId == model.id }?.let { return it }
        val s = settingsSource?.invoke() ?: throw IllegalStateException("Settings are not ready")
        val info = com.gravity.ide.device.DeviceInspector(context).info()
        return load(model, com.gravity.ide.device.PerformanceManager.contextSize(s, info), com.gravity.ide.device.PerformanceManager.threads(s, info))
    }

    /** [ensure] by model id or name; null if no such ready model (then the loaded one is used). */
    suspend fun ensure(idOrName: String): Loaded? {
        val m = lookup?.invoke(idOrName) ?: return null
        return ensure(m)
    }

    /** True if [loaded] is the model asked for by id or name (or nothing in particular was asked for). */
    fun matches(loaded: Loaded, idOrName: String?): Boolean =
        idOrName.isNullOrBlank() || loaded.modelId == idOrName || loaded.name == idOrName

    /**
     * Frees the loaded model. A reply still being written with it is stopped first and waited for:
     * freeing the model under a running reply crashed Gravity (the engine's threads read freed memory).
     */
    private suspend fun unloadLocked(abort: Boolean) {
        val current = state.value as? State.Ready ?: return
        // Stop a reply being written only when the user unloads the model; a switch for another chat waits for it.
        abortRequested = abort
        try {
            generationMutex.withLock {
                _state.value = State.Idle
                LlamaBridge.unload(current.loaded.handle)
            }
        } finally {
            abortRequested = false
        }
        events.log(EventCategory.MODEL, "Unloaded ${current.loaded.name}")
    }

    /** Serialises generation: llama.cpp contexts are not thread-safe. */
    val generationMutex = Mutex()

    fun loadedOrNull(): Loaded? = (state.value as? State.Ready)?.loaded


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
        if (prefs.getString(KEY_CRASHED_MODEL, null) == pick.id) {
            throw ModelUnhealthyException(
                "Yamini closed while running ${pick.name} last time, so it is not loaded by itself. Load it from Phone Models to try again " +
                    "(close other apps first), or use Gemini."
            )
        }
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
        // Android counts the model file's pages as "available" (they are file cache), but taking
        // them back makes the model read from storage again, or gets Gravity closed for memory.
        val free = com.gravity.ide.core.ContextPlanner.reallyFree(availableRamBytes(), loaded.fileBytes)
        return com.gravity.ide.core.ContextPlanner.choose(loaded.contextSize, wanted, info[0], perToken, free)
    }
}
