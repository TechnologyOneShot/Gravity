package com.gravity.ide.core

import com.gravity.ide.ai.LlmException

/**
 * Chooses a model from the list a provider actually offers to the user's key, so Gravity never
 * depends on a hard-coded model name that the provider has since retired.
 */
object ModelPicker {
    private val stableFlash = Regex("^gemini-(\\d+(?:\\.\\d+)*)-flash$")
    private val anyGemini = Regex("^gemini-(\\d+(?:\\.\\d+)*)-")
    private val specialised = listOf(
        "lite", "image", "tts", "live", "audio", "embedding", "preview", "exp", "robotics", "computer-use",
        "transcribe", "translate", "omni", "vision", "aqa", "learnlm", "veo", "lyria", "imagen",
    )

    private fun version(v: String): List<Int> = v.split('.').map { it.toIntOrNull() ?: 0 }

    private val byVersion = Comparator<List<Int>> { a, b ->
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return@Comparator d
        }
        0
    }

    /**
     * The newest stable, general-purpose Flash model in [models] (e.g. "gemini-3.8-flash"),
     * falling back to the "gemini-flash-latest" alias, then any general Gemini model.
     */
    fun bestGemini(models: List<String>): String? {
        val ids = models.map { it.removePrefix("models/") }.distinct()
        ids.mapNotNull { id -> stableFlash.find(id)?.let { id to version(it.groupValues[1]) } }
            .maxWithOrNull { a, b -> byVersion.compare(a.second, b.second) }?.let { return it.first }
        if ("gemini-flash-latest" in ids) return "gemini-flash-latest"
        val general = ids.filter { id -> id.startsWith("gemini") && specialised.none { it in id } }
        general.filter { "flash" in it }.maxWithOrNull(compareVersions())?.let { return it }
        general.maxWithOrNull(compareVersions())?.let { return it }
        return ids.firstOrNull { "flash" in it } ?: ids.firstOrNull()
    }

    private fun compareVersions() = Comparator<String> { a, b ->
        val va = anyGemini.find(a)?.groupValues?.get(1)?.let(::version) ?: emptyList()
        val vb = anyGemini.find(b)?.groupValues?.get(1)?.let(::version) ?: emptyList()
        byVersion.compare(va, vb)
    }

    /** True when the provider says the requested model does not exist or is no longer offered. */
    fun isModelUnavailable(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        if (e is LlmException && e.httpCode == 404) return true
        return "no longer available" in msg || ("model" in msg && ("not found" in msg || "is not supported" in msg || "deprecated" in msg))
    }
}
