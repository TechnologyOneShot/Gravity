package com.gravity.ide.ai.local

import com.gravity.ide.BuildConfig

/**
 * JNI bridge to llama.cpp (app/src/main/cpp/gravity_llama.cpp). The native library is
 * only present when the APK was built with `-Pgravity.llama=true`; otherwise
 * [isAvailable] is false and every local-inference entry point reports that honestly.
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

    /** Returns a native handle, or 0 on failure (see [lastError]). */
    external fun load(path: String, contextSize: Int, threads: Int, gpuLayers: Int): Long

    external fun unload(handle: Long)

    /**
     * Applies the model's own chat template (from GGUF metadata), tokenizes with the model's
     * tokenizer and streams sampled tokens. Returns the number of generated tokens, or -1.
     */
    external fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        /** Context for this request; 0 or less = the size the model was loaded with. */
        contextSize: Int,
        /** Text the reply starts with (the model continues from it); "" for none. */
        prefix: String,
        callback: TokenCallback,
    ): Int

    /** [n_ctx_train, n_layer, n_embd, n_head, n_head_kv] of a loaded model. */
    external fun modelInfo(handle: Long): IntArray

    external fun lastError(): String

    /** Why the last generation ended: "eos", "stop", "max", "ctx" (context full) or "error". */
    external fun lastFinish(): String

    external fun systemInfo(): String
}
