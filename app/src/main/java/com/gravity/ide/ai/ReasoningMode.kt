package com.gravity.ide.ai

/**
 * Reasoning modes map to real provider parameters (see [ReasoningMapper]); they are not
 * cosmetic. Private chain-of-thought is never displayed — only plans, tool activity and results.
 */
enum class ReasoningMode(val label: String, val description: String) {
    FAST("Fast", "Minimal thinking budget, shortest latency"),
    BALANCED("Balanced", "Provider default / dynamic thinking"),
    DEEP_THINK("Deep Think", "Larger thinking budget for multi-step work"),
    HIGH("High Reasoning", "High thinking budget, slower"),
    MAXIMUM("Maximum Reasoning", "Largest budget the model accepts"),
    AUTO("Auto Think", "Chooses a mode from task complexity"),
    THINK_PLUS("Think Plus", "Maximum budget plus a planning pass before tool use");

    companion object {
        fun fromName(name: String?): ReasoningMode = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

object ReasoningMapper {

    /** Heuristic complexity detection used by AUTO. */
    fun resolveAuto(instruction: String): ReasoningMode {
        val text = instruction.lowercase()
        val heavy = listOf("architecture", "refactor", "debug", "migrate", "design", "multiple files", "whole project", "build error", "crash", "security")
        val score = heavy.count { it in text } + instruction.length / 600
        return when {
            score >= 3 -> ReasoningMode.HIGH
            score >= 1 -> ReasoningMode.DEEP_THINK
            instruction.length < 80 -> ReasoningMode.FAST
            else -> ReasoningMode.BALANCED
        }
    }

    sealed interface GeminiThinking {
        data class Budget(val tokens: Int) : GeminiThinking
        data class Level(val level: String) : GeminiThinking
        data object None : GeminiThinking
    }

    /**
     * Gemini 2.5 models take `thinkingBudget` (-1 = dynamic, 0 = off on Flash/Flash-Lite only);
     * Gemini 3 models take `thinkingLevel`. Older models accept neither, so nothing is sent.
     */
    fun gemini(model: String, mode: ReasoningMode): GeminiThinking {
        val m = model.lowercase()
        val effective = if (mode == ReasoningMode.AUTO) ReasoningMode.BALANCED else mode
        if (m.contains("gemini-3")) {
            return GeminiThinking.Level(if (effective == ReasoningMode.FAST || effective == ReasoningMode.BALANCED) "low" else "high")
        }
        if (!m.contains("2.5")) return GeminiThinking.None
        val isPro = m.contains("pro")
        val max = if (isPro) 32768 else 24576
        return GeminiThinking.Budget(
            when (effective) {
                ReasoningMode.FAST -> if (isPro) 128 else 0
                ReasoningMode.BALANCED, ReasoningMode.AUTO -> -1
                ReasoningMode.DEEP_THINK -> 8192
                ReasoningMode.HIGH -> 16384
                ReasoningMode.MAXIMUM, ReasoningMode.THINK_PLUS -> max
            }
        )
    }

    /** OpenAI-compatible `reasoning_effort` (only sent when the provider is flagged as supporting it). */
    fun openAiEffort(mode: ReasoningMode): String = when (mode) {
        ReasoningMode.FAST -> "low"
        ReasoningMode.BALANCED, ReasoningMode.AUTO -> "medium"
        else -> "high"
    }

    /** Local models: reasoning maps to generation length and temperature. */
    fun localMaxTokens(mode: ReasoningMode): Int = when (mode) {
        ReasoningMode.FAST -> 512
        ReasoningMode.BALANCED, ReasoningMode.AUTO -> 1024
        ReasoningMode.DEEP_THINK -> 2048
        ReasoningMode.HIGH -> 3072
        ReasoningMode.MAXIMUM, ReasoningMode.THINK_PLUS -> 4096
    }

    fun usesPlanningPass(mode: ReasoningMode) = mode == ReasoningMode.THINK_PLUS || mode == ReasoningMode.MAXIMUM
}
