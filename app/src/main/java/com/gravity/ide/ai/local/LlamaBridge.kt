package com.gravity.ide.ai.local

import com.gravity.ide.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/** A build of the on-device engine (llama.cpp). */
enum class EngineKind(val label: String) {
    /** Armv8.0 NEON: runs on every arm64 phone (and the x86_64 emulator build). */
    BASE("standard engine"),

    /** Armv8.2 dot product + fp16 (most phones since 2018): faster, and different CPU code. */
    FAST("fast engine (Armv8.2)"),

    /** Armv8.6 int8 matrix instructions (i8mm; most phones since 2022): reads prompts ~30% faster. */
    I8MM("fastest engine (Armv8.6 i8mm)"),
}

/**
 * JNI bridge to llama.cpp (app/src/main/cpp/gravity_llama.cpp). The native library is
 * only present when the APK was built with `-Pgravity.llama=true`; otherwise
 * [isAvailable] is false and every local-inference entry point reports that honestly.
 *
 * arm64 phones get two builds of the engine ([EngineKind]); each loaded model remembers which
 * one it runs on, so callers just pass the handle.
 */
object LlamaBridge {

    fun interface TokenCallback {
        /** Raw UTF-8 bytes of one token piece. Return false to stop generation. */
        fun onToken(bytes: ByteArray): Boolean
    }

    val loadError: String?
    val isAvailable: Boolean

    init {
        var error: String? = null
        var ok = false
        if (!BuildConfig.LLAMA_BUNDLED) {
            error = "This copy of Yamini was built without the on-device AI engine (llama.cpp). Install yamini-debug.apk from the latest Yamini release, which includes it."
        } else {
            try {
                System.loadLibrary("gravity_llama")
                ok = true
            } catch (t: Throwable) {
                error = "Failed to load native runtime: ${t.message}"
            }
        }
        isAvailable = ok
        loadError = error
    }

    private val engineOfHandle = ConcurrentHashMap<Long, EngineKind>()

    @Volatile
    private var lastUsed = EngineKind.BASE

    /** AT_HWCAP bits (arm64): fphp, asimdhp, asimddp. */
    private const val HWCAP_FPHP = 1L shl 9
    private const val HWCAP_ASIMDHP = 1L shl 10
    private const val HWCAP_ASIMDDP = 1L shl 20

    /** AT_HWCAP2 bit (arm64): i8mm. */
    private const val HWCAP2_I8MM = 1L shl 13

    /** CPU features the kernel reports (0 if unknown or not arm64). */
    fun cpuHwcap(): Long = if (isAvailable) runCatching { NativeBase.cpuHwcap() }.getOrDefault(0L) else 0L

    /** The second word of CPU features (0 if unknown, not arm64, or an older engine library). */
    fun cpuHwcap2(): Long = if (isAvailable) runCatching { NativeBase.cpuHwcap2() }.getOrDefault(0L) else 0L

    /** True on an arm64 CPU with Armv8.2 dot product and fp16 (what the fast and picture engines are built for). */
    fun armv82(): Boolean = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
        cpuHwcap().let { (it and HWCAP_ASIMDDP) != 0L && (it and HWCAP_ASIMDHP) != 0L && (it and HWCAP_FPHP) != 0L }

    /** Why the fast engine is not used on this phone, or null if it can be used. */
    val fastUnavailableReason: String? by lazy {
        when {
            !isAvailable -> loadError
            !BuildConfig.ENGINE_FAST_BUNDLED -> "not in this build"
            android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" -> "not an arm64 phone"
            cpuHwcap().let { (it and HWCAP_ASIMDDP) == 0L || (it and HWCAP_ASIMDHP) == 0L || (it and HWCAP_FPHP) == 0L } ->
                "this CPU has no Armv8.2 dot product / fp16"
            else -> NativeFast.loadError
        }
    }

    /** Why the fastest (i8mm) engine is not used on this phone, or null if it can be used. */
    val i8mmUnavailableReason: String? by lazy {
        when {
            fastUnavailableReason != null -> fastUnavailableReason
            !BuildConfig.ENGINE_I8MM_BUNDLED -> "not in this build"
            (cpuHwcap2() and HWCAP2_I8MM) == 0L -> "this CPU has no Armv8.6 int8 matrix instructions (i8mm)"
            else -> NativeI8mm.loadError
        }
    }

    /** Set when the fast engine crashed Gravity on this phone (see LocalModelHost.checkLastExit). */
    @Volatile
    var fastTurnedOff: String? = null
        private set

    fun turnOffFast(reason: String) { fastTurnedOff = reason }

    /** Set when the i8mm engine crashed Yamini on this phone. */
    @Volatile
    var i8mmTurnedOff: String? = null
        private set

    fun turnOffI8mm(reason: String) { i8mmTurnedOff = reason }

    /** Engines this phone can run, the faster first. */
    val engines: List<EngineKind>
        get() = if (!isAvailable) emptyList()
        else buildList {
            if (i8mmUnavailableReason == null && i8mmTurnedOff == null && fastTurnedOff == null) add(EngineKind.I8MM)
            if (fastUnavailableReason == null && fastTurnedOff == null) add(EngineKind.FAST)
            add(EngineKind.BASE)
        }

    /** Which engine a loaded model runs on. */
    fun engineOf(handle: Long): EngineKind = engineOfHandle[handle] ?: EngineKind.BASE

    /** Returns a native handle, or 0 on failure (see [lastError]). [useMmap] false reads the model into memory. */
    fun load(path: String, contextSize: Int, threads: Int, gpuLayers: Int, useMmap: Boolean, engine: EngineKind = EngineKind.BASE): Long {
        lastUsed = engine
        val handle = when (engine) {
            EngineKind.BASE -> NativeBase.load(path, contextSize, threads, gpuLayers, useMmap)
            EngineKind.FAST -> {
                fastUnavailableReason?.let { throw IllegalStateException("The fast engine cannot run here: $it") }
                fastTurnedOff?.let { throw IllegalStateException("The fast engine is off on this phone: $it") }
                NativeFast.load(path, contextSize, threads, gpuLayers, useMmap)
            }
            EngineKind.I8MM -> {
                i8mmUnavailableReason?.let { throw IllegalStateException("The i8mm engine cannot run here: $it") }
                i8mmTurnedOff?.let { throw IllegalStateException("The i8mm engine is off on this phone: $it") }
                NativeI8mm.load(path, contextSize, threads, gpuLayers, useMmap)
            }
        }
        if (handle != 0L) engineOfHandle[handle] = engine
        return handle
    }

    fun unload(handle: Long) {
        when (engineOfHandle.remove(handle) ?: EngineKind.BASE) {
            EngineKind.BASE -> NativeBase.unload(handle)
            EngineKind.FAST -> NativeFast.unload(handle)
            EngineKind.I8MM -> NativeI8mm.unload(handle)
        }
    }

    /**
     * Saves what a loaded model's context has read to [path] (see gravity_core.h save_state), so
     * the next start does not read the system prompt again. False if there was nothing to save.
     */
    fun saveState(handle: Long, path: String): Boolean = runCatching {
        when (engineOf(handle)) {
            EngineKind.BASE -> NativeBase.saveState(handle, path)
            EngineKind.FAST -> NativeFast.saveState(handle, path)
            EngineKind.I8MM -> NativeI8mm.saveState(handle, path)
        }
    }.getOrDefault(false)

    /**
     * Threads for writing ([threads]) and for reading the prompt ([batchThreads]) of a loaded
     * model; 0 keeps a value. False if the engine could not take it.
     */
    fun setThreads(handle: Long, threads: Int, batchThreads: Int): Boolean = runCatching {
        when (engineOf(handle)) {
            EngineKind.BASE -> NativeBase.setThreads(handle, threads, batchThreads)
            EngineKind.FAST -> NativeFast.setThreads(handle, threads, batchThreads)
            EngineKind.I8MM -> NativeI8mm.setThreads(handle, threads, batchThreads)
        }
        true
    }.getOrDefault(false)

    /** Loads a saved state into a context of [contextSize] (0 = loaded size); tokens kept, or -1. */
    fun loadState(handle: Long, path: String, contextSize: Int): Int = runCatching {
        when (engineOf(handle)) {
            EngineKind.BASE -> NativeBase.loadState(handle, path, contextSize)
            EngineKind.FAST -> NativeFast.loadState(handle, path, contextSize)
            EngineKind.I8MM -> NativeI8mm.loadState(handle, path, contextSize)
        }
    }.getOrDefault(-1)

    /**
     * Applies the model's own chat template (from GGUF metadata), tokenizes with the model's
     * tokenizer and streams sampled tokens. Returns the number of generated tokens, or -1.
     * [contextSize] 0 or less = the size the model was loaded with; [prefix] is text the reply
     * starts with (the model continues from it), "" for none.
     */
    fun generate(
        handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, topP: Float,
        contextSize: Int, prefix: String, callback: TokenCallback,
    ): Int {
        val engine = engineOf(handle)
        lastUsed = engine
        return when (engine) {
            EngineKind.BASE -> NativeBase.generate(handle, roles, contents, maxTokens, temperature, topP, contextSize, prefix, callback)
            EngineKind.FAST -> NativeFast.generate(handle, roles, contents, maxTokens, temperature, topP, contextSize, prefix, callback)
            EngineKind.I8MM -> NativeI8mm.generate(handle, roles, contents, maxTokens, temperature, topP, contextSize, prefix, callback)
        }
    }

    /**
     * [n_ctx_train, n_layer, n_embd, n_head, n_head_kv, tokens the last request reused from the
     * kept context, kept context size] of a loaded model.
     */
    fun modelInfo(handle: Long): IntArray = when (engineOf(handle)) {
        EngineKind.BASE -> NativeBase.modelInfo(handle)
        EngineKind.FAST -> NativeFast.modelInfo(handle)
        EngineKind.I8MM -> NativeI8mm.modelInfo(handle)
    }

    /** The last error of the engine used last. */
    fun lastError(): String = when (lastUsed) {
        EngineKind.BASE -> NativeBase.lastError()
        EngineKind.FAST -> NativeFast.lastError()
        EngineKind.I8MM -> NativeI8mm.lastError()
    }

    /** Why the last generation ended: "eos", "stop", "max", "ctx" (context full) or "error". */
    fun lastFinish(): String = when (lastUsed) {
        EngineKind.BASE -> NativeBase.lastFinish()
        EngineKind.FAST -> NativeFast.lastFinish()
        EngineKind.I8MM -> NativeI8mm.lastFinish()
    }

    fun systemInfo(engine: EngineKind = EngineKind.BASE): String = when (engine) {
        EngineKind.BASE -> NativeBase.systemInfo()
        EngineKind.FAST -> if (fastUnavailableReason == null) NativeFast.systemInfo() else "fast engine: $fastUnavailableReason"
        EngineKind.I8MM -> if (i8mmUnavailableReason == null) NativeI8mm.systemInfo() else "i8mm engine: $i8mmUnavailableReason"
    }

    /** True if native heap pointers are tagged (Android pointer tagging), for diagnostics. */
    fun heapTagged(): Boolean = NativeBase.heapTagged()
}

/** The standard engine (libgravity_llama.so), loaded by [LlamaBridge]. */
internal object NativeBase {
    external fun load(path: String, contextSize: Int, threads: Int, gpuLayers: Int, useMmap: Boolean): Long
    external fun unload(handle: Long)
    external fun generate(
        handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, topP: Float,
        contextSize: Int, prefix: String, callback: LlamaBridge.TokenCallback,
    ): Int
    external fun modelInfo(handle: Long): IntArray
    external fun lastError(): String
    external fun lastFinish(): String
    external fun systemInfo(): String
    external fun heapTagged(): Boolean
    external fun cpuHwcap(): Long
    external fun cpuHwcap2(): Long
    external fun saveState(handle: Long, path: String): Boolean
    external fun loadState(handle: Long, path: String, contextSize: Int): Int
    external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
}

/** The fast engine (libgravity_llama_fast.so, module :engine-fast); loaded only on CPUs that support it. */
internal object NativeFast {
    val loadError: String? = try {
        System.loadLibrary("gravity_llama_fast")
        null
    } catch (t: Throwable) {
        "could not be loaded: ${t.message}"
    }

    external fun load(path: String, contextSize: Int, threads: Int, gpuLayers: Int, useMmap: Boolean): Long
    external fun unload(handle: Long)
    external fun generate(
        handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, topP: Float,
        contextSize: Int, prefix: String, callback: LlamaBridge.TokenCallback,
    ): Int
    external fun modelInfo(handle: Long): IntArray
    external fun lastError(): String
    external fun lastFinish(): String
    external fun systemInfo(): String
    external fun heapTagged(): Boolean
    external fun cpuHwcap(): Long
    external fun cpuHwcap2(): Long
    external fun saveState(handle: Long, path: String): Boolean
    external fun loadState(handle: Long, path: String, contextSize: Int): Int
    external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
}

/** The fastest engine (libgravity_llama_i8mm.so, module :engine-i8mm); loaded only on CPUs with i8mm. */
internal object NativeI8mm {
    val loadError: String? = try {
        System.loadLibrary("gravity_llama_i8mm")
        null
    } catch (t: Throwable) {
        "could not be loaded: ${t.message}"
    }

    external fun load(path: String, contextSize: Int, threads: Int, gpuLayers: Int, useMmap: Boolean): Long
    external fun unload(handle: Long)
    external fun generate(
        handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, topP: Float,
        contextSize: Int, prefix: String, callback: LlamaBridge.TokenCallback,
    ): Int
    external fun modelInfo(handle: Long): IntArray
    external fun lastError(): String
    external fun lastFinish(): String
    external fun systemInfo(): String
    external fun heapTagged(): Boolean
    external fun cpuHwcap(): Long
    external fun cpuHwcap2(): Long
    external fun saveState(handle: Long, path: String): Boolean
    external fun loadState(handle: Long, path: String, contextSize: Int): Int
    external fun setThreads(handle: Long, threads: Int, batchThreads: Int)
}
