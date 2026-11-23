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
            error = "This build of Gravity does not include the llama.cpp runtime (built without -Pgravity.llama=true)."
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
        callback: TokenCallback,
    ): Int

    external fun lastError(): String

    external fun systemInfo(): String
}
