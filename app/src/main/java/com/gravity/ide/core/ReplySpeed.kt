package com.gravity.ide.core

/**
 * The speed line under a phone model's reply, from what the engine measured: so a slow reply can
 * be told apart (much to read, slow writing, few threads, the safer engine).
 */
object ReplySpeed {
    fun line(promptTokens: Int, reusedTokens: Int, readMs: Long, outputTokens: Int, writeMs: Long, threads: Int, engine: String): String {
        val newTokens = (promptTokens - reusedTokens).coerceAtLeast(0)
        val read = "Read $newTokens tokens in ${seconds(readMs)}" + (if (reusedTokens > 0) " ($reusedTokens already read)" else "")
        val rate = if (writeMs > 0) outputTokens * 1000.0 / writeMs else 0.0
        val write = "wrote $outputTokens in ${seconds(writeMs)} (${"%.1f".format(java.util.Locale.ROOT, rate)} a second)"
        val engineName = when (engine.uppercase()) {
            "FAST" -> "fast engine"
            "BASE" -> "standard engine"
            else -> engine.lowercase().ifBlank { "engine" }
        }
        return "$read · $write · $threads threads, $engineName"
    }

    private fun seconds(ms: Long): String = when {
        ms < 1000 -> "${ms} ms"
        ms < 60_000 -> "%.1f s".format(java.util.Locale.ROOT, ms / 1000.0)
        else -> "${ms / 60_000} min ${(ms % 60_000) / 1000} s"
    }
}
