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
}
