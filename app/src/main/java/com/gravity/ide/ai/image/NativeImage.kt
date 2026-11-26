package com.gravity.ide.ai.image

/** The on-phone picture engine (libgravity_image.so: stable-diffusion.cpp, module :engine-image). */
internal object NativeImage {
    fun interface StepCallback {
        fun onStep(step: Int, steps: Int)
    }

    /** Null when the library loaded, else why not. */
    val loadError: String? = try {
        System.loadLibrary("gravity_image")
        null
    } catch (t: Throwable) {
        "could not be loaded: ${t.message}"
    }

    external fun load(model: String, lora: String, taesd: String, threads: Int, wtype: String): Long
    external fun generate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long,
        method: String, loraStrength: Float, callback: StepCallback,
    ): ByteArray?
    external fun lastWidth(): Int
    external fun lastHeight(): Int
    external fun cancel(handle: Long)
    external fun unload(handle: Long)
    external fun lastError(): String
}
