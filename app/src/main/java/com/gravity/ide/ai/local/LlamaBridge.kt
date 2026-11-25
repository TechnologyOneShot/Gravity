package com.gravity.ide.ai.local

import com.gravity.ide.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/** A build of the on-device engine (llama.cpp). */
enum class EngineKind(val label: String) {
    /** Armv8.0 NEON: runs on every arm64 phone (and the x86_64 emulator build). */
    BASE("standard engine"),

    /** Armv8.2 dot product + fp16 (most phones since 2018): faster, and different CPU code. */
    FAST("fast engine (Armv8.2)"),
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
            error = "This copy of Gravity was built without the on-device AI engine (llama.cpp). Install gravity-debug.apk from the latest Gravity release, which includes it."
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

    /** CPU features the kernel reports (0 if unknown or not arm64). */
    fun cpuHwcap(): Long = if (isAvailable) runCatching { NativeBase.cpuHwcap() }.getOrDefault(0L) else 0L

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

    /** Engines this phone can run, the faster first. */
    val engines: List<EngineKind> by lazy {
        if (!isAvailable) emptyList() else if (fastUnavailableReason == null) listOf(EngineKind.FAST, EngineKind.BASE) else listOf(EngineKind.BASE)
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
                NativeFast.load(path, contextSize, threads, gpuLayers, useMmap)
            }
        }
        if (handle != 0L) engineOfHandle[handle] = engine
        return handle
    }

    fun unload(handle: Long) {
        when (engineOfHandle.remove(handle) ?: EngineKind.BASE) {
            EngineKind.BASE -> NativeBase.unload(handle)
            EngineKind.FAST -> NativeFast.unload(handle)
        }
    }

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
        }
    }

    /**
     * [n_ctx_train, n_layer, n_embd, n_head, n_head_kv, tokens the last request reused from the
     * kept context, kept context size] of a loaded model.
     */
    fun modelInfo(handle: Long): IntArray = when (engineOf(handle)) {
        EngineKind.BASE -> NativeBase.modelInfo(handle)
        EngineKind.FAST -> NativeFast.modelInfo(handle)
    }

    /** The last error of the engine used last. */
    fun lastError(): String = when (lastUsed) {
        EngineKind.BASE -> NativeBase.lastError()
        EngineKind.FAST -> NativeFast.lastError()
    }

    /** Why the last generation ended: "eos", "stop", "max", "ctx" (context full) or "error". */
    fun lastFinish(): String = when (lastUsed) {
        EngineKind.BASE -> NativeBase.lastFinish()
        EngineKind.FAST -> NativeFast.lastFinish()
    }

    fun systemInfo(engine: EngineKind = EngineKind.BASE): String = when (engine) {
        EngineKind.BASE -> NativeBase.systemInfo()
        EngineKind.FAST -> if (fastUnavailableReason == null) NativeFast.systemInfo() else "fast engine: $fastUnavailableReason"
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
}
