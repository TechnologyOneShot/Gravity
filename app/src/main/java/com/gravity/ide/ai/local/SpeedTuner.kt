package com.gravity.ide.ai.local

import com.gravity.ide.ai.LlmEvent
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.Role

/**
 * The phone model's speed test (Phone Models → Check the phone AI → Speed test): measures on this
 * phone how fast the loaded model reads a prompt and writes with different thread counts, and keeps
 * the fastest. Reading a prompt (many tokens at once) is limited by the CPU cores, so more threads
 * can help; writing (one token at a time) is limited by memory speed, where more threads often do
 * not. Every number shown is measured here; nothing is estimated.
 */
class SpeedTuner(private val host: LocalModelHost, private val provider: LocalLlamaProvider) {
    data class Run(val write: Int, val read: Int, val readTokens: Int, val readMs: Long, val wrote: Int, val writeMs: Long) {
        val readTps: Double get() = if (readMs > 0) readTokens * 1000.0 / readMs else 0.0
        val writeTps: Double get() = if (writeMs > 0) wrote * 1000.0 / writeMs else 0.0
        override fun toString() = "writing %d / reading %d threads: reads %.1f tokens/s (%d in %.1f s), writes %.1f tokens/s".format(
            write, read, readTps, readTokens, readMs / 1000.0, writeTps,
        )
    }

    data class Result(val runs: List<Run>, val write: Int, val read: Int, val base: Int) {
        val before: Run? get() = runs.firstOrNull { it.write == base && it.read == base }
    }

    /** One measured answer with [write] and [read] threads. */
    suspend fun measure(loaded: LocalModelHost.Loaded, write: Int, read: Int, seed: Int): Run {
        LlamaBridge.setThreads(loaded.handle, write, read)
        var speed: LlmEvent.Speed? = null
        provider.stream(
            LlmRequest(
                loaded.name,
                listOf(LlmMessage(Role.SYSTEM, testPrompt(seed)), LlmMessage(Role.USER, "Summarise the list above in one sentence.")),
                temperature = 0.2, maxOutputTokens = 32,
            ),
        ) { ev -> if (ev is LlmEvent.Speed) speed = ev }
        val s = speed ?: throw IllegalStateException("The model did not report its speed")
        return Run(write, read, s.promptTokens - s.reusedTokens, s.readMs, s.outputTokens, s.writeMs)
    }

    /**
     * Measures [base] threads (what the app uses now) against more threads for reading and for
     * writing, keeps the fastest for this phone, and puts the chat prompt back. [line] reports
     * each result as it comes.
     */
    suspend fun tune(loaded: LocalModelHost.Loaded, base: Int, cores: Int, line: (String) -> Unit): Result {
        val runs = ArrayList<Run>()
        var seed = (System.nanoTime() % 100_000).toInt()
        suspend fun run(w: Int, r: Int): Run = measure(loaded, w, r, seed++).also { runs += it; line(it.toString()) }
        try {
            for (r in readCandidates(base, cores)) run(base, r)
            val read = pick(runs, base) { it.readTps }.read
            for (w in writeCandidates(base, cores)) if (w != base) run(w, read)
            val write = pick(runs.filter { it.read == read || it.write == base }, base) { it.writeTps }.write
            host.setTunedThreads(base, write, read)
            LlamaBridge.setThreads(loaded.handle, write, read)
            return Result(runs, write, read, base)
        } catch (e: Exception) {
            LlamaBridge.setThreads(loaded.handle, base, base)
            throw e
        } finally {
            runCatching { host.restoreSaved(loaded) }
        }
    }

    companion object {
        /** Reading threads to try: the usual count, all cores (up to 8), and halfway. */
        fun readCandidates(base: Int, cores: Int): List<Int> {
            val all = cores.coerceIn(1, 8)
            return listOf(base, all, (base + all) / 2).filter { it in 1..all }.distinct()
        }

        /** Writing threads to try: the usual count and two more (when the phone has them). */
        fun writeCandidates(base: Int, cores: Int): List<Int> = listOf(base, base + 2).filter { it in 1..cores.coerceAtMost(8) }.distinct()

        /**
         * The fastest run by [speed]; the usual setup ([base] threads) unless another is at least
         * 5% faster (smaller differences are noise).
         */
        fun pick(runs: List<Run>, base: Int, speed: (Run) -> Double): Run {
            val usual = runs.firstOrNull { it.write == base && it.read == base } ?: runs.first()
            val best = runs.maxByOrNull(speed) ?: usual
            return if (speed(best) >= speed(usual) * 1.05) best else usual
        }

        /** About 250 tokens of plain text, different each time (so nothing is reused from before). */
        fun testPrompt(seed: Int): String = buildString {
            append("Test $seed. You are checking reading speed. Here is a list of everyday things:\n")
            val things = listOf("apples", "rivers", "bicycles", "mountains", "lamps", "gardens", "trains", "books", "clouds", "markets")
            for (i in 1..24) append("$i. ${things[(i + seed) % things.size]} in the ${things[(i * 3 + seed) % things.size]} near number ${seed % 97 + i}.\n")
        }
    }
}
