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

    /**
     * Models ordered for a picker: the recommended model first, then other general-purpose
     * models (newest first), then specialised ones (preview, image, TTS, embedding…).
     */
    fun rankForChat(models: List<String>): List<String> {
        val ids = models.map { it.removePrefix("models/") }.distinct()
        val general = ids.filter { id -> specialised.none { it in id } }
        val rest = ids.filterNot { it in general }
        return (listOfNotNull(bestGemini(ids)) + general.sortedWith(compareVersions().reversed()) + rest).distinct()
    }

    /** True for general chat models (not image, speech, embedding, preview…). */
    fun isGeneral(model: String): Boolean = specialised.none { it in model }

    private fun compareVersions() = Comparator<String> { a, b ->
        val va = anyGemini.find(a)?.groupValues?.get(1)?.let(::version) ?: emptyList()
        val vb = anyGemini.find(b)?.groupValues?.get(1)?.let(::version) ?: emptyList()
        byVersion.compare(va, vb)
    }

    /** True when the request failed because the key's quota (free-tier limit) for this model is used up. */
    fun isQuotaExhausted(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return (e is LlmException && e.httpCode == 429) && ("quota" in msg || "rate limit" in msg || "resource_exhausted" in msg || "resource has been exhausted" in msg)
    }

    /** "Please retry in 25m25.07s" → "25 min"; null if the provider did not say. */
    fun retryHint(e: Throwable): String? {
        val m = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(\\d+(?:\\.\\d+)?)s", RegexOption.IGNORE_CASE).find(e.message.orEmpty()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val sec = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val total = h * 60 + min + if (sec > 0) 1 else 0
        return if (total >= 60) "${total / 60} h ${total % 60} min" else "$total min"
    }

    /**
     * Other models to try when [failed]'s quota is used up: general Flash models first (newest
     * first), then Flash-Lite (usually a larger free quota), then the rest. Each model has its own quota.
     */
    fun quotaFallbacks(models: List<String>, failed: String, exclude: Set<String>): List<String> {
        val ids = models.map { it.removePrefix("models/") }.distinct().filter { it != failed && it !in exclude && it.startsWith("gemini") }
        val noSpecial = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer-use", "transcribe", "translate", "vision", "aqa", "learnlm", "veo", "lyria", "imagen")
        val usable = ids.filter { id -> noSpecial.none { it in id } && "preview" !in id && "exp" !in id }
        val flash = usable.filter { "flash" in it && "lite" !in it }.sortedWith(compareVersions().reversed())
        val lite = usable.filter { "lite" in it }.sortedWith(compareVersions().reversed())
        val rest = usable.filterNot { it in flash || it in lite }.sortedWith(compareVersions().reversed())
        return flash + lite + rest
    }

    /** True when the provider says the requested model does not exist or is no longer offered. */
    fun isModelUnavailable(e: Throwable): Boolean {
        val msg = e.message.orEmpty().lowercase()
        if (e is LlmException && e.httpCode == 404) return true
        return "no longer available" in msg || ("model" in msg && ("not found" in msg || "is not supported" in msg || "deprecated" in msg))
    }
}
