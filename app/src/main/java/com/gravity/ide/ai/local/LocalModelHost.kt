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

    suspend fun load(model: LocalModelEntity, contextSize: Int, threads: Int): Loaded = mutex.withLock {
        if (!LlamaBridge.isAvailable) throw IllegalStateException(LlamaBridge.loadError ?: "Local runtime unavailable")
        val file = File(model.filePath)
        if (!file.isFile) throw IllegalStateException("Model file missing: ${model.filePath}")
        (state.value as? State.Ready)?.let { current ->
            if (current.loaded.modelId == model.id) return@withLock current.loaded
            unloadLocked()
        }
        _state.value = State.Loading(model.name)
        val handle = withContext(Dispatchers.Default) { LlamaBridge.load(file.absolutePath, contextSize, threads, 0) }
        if (handle == 0L) {
            val err = LlamaBridge.lastError().ifBlank { "llama.cpp failed to load the model" }
            _state.value = State.Failed(err)
            events.log(EventCategory.MODEL, "Failed to load ${model.name}", err, EventLevel.ERROR)
            throw IllegalStateException(err)
        }
        val loaded = Loaded(model.id, model.name, handle, contextSize, System.currentTimeMillis())
        _state.value = State.Ready(loaded)
        prefs.edit().putString(KEY_LAST_MODEL, model.id).apply()
        events.log(EventCategory.MODEL, "Loaded local model ${model.name}", "context=$contextSize threads=$threads")
        loaded
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
        return runCatching { load(pick, ctx, com.gravity.ide.device.PerformanceManager.threads(settings, info)) }.getOrNull()
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
