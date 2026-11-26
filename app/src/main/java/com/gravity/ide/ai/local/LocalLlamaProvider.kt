package com.gravity.ide.ai.local

import com.gravity.ide.ai.LlmEvent
import com.gravity.ide.ai.LlmException
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmProvider
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ReasoningMapper
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.ToolCall
import com.gravity.ide.core.TextToolProtocol
import com.gravity.ide.core.Utf8Accumulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device inference through llama.cpp. Tool calls use [TextToolProtocol] because local
 * chat templates have no common function-calling format.
 */
class LocalLlamaProvider(
    private val host: LocalModelHost,
    private val modelName: () -> String?,
) : LlmProvider {

    override val providerId: String = ID
    override val isLocal: Boolean = true

    override suspend fun stream(request: LlmRequest, onEvent: (LlmEvent) -> Unit) {
        if (!host.runtimeAvailable) throw LlmException(host.runtimeError ?: "Local runtime not available")
        val messages = flatten(request)
        val roles = messages.map { it.first }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()
        val maxTokens = request.maxOutputTokens ?: ReasoningMapper.localMaxTokens(request.reasoning)
        val temperature = (request.temperature ?: 0.4).toFloat()

        val full = StringBuilder()
        val utf8 = Utf8Accumulator()
        val ctx = currentCoroutineContext()
        val prefix = request.assistantPrefix.orEmpty()
        if (prefix.isNotEmpty()) {
            full.append(prefix)
            onEvent(LlmEvent.TextDelta(prefix))
        }
        val stops = request.stop.filter { it.isNotEmpty() }
        val longestStop = stops.maxOfOrNull { it.length } ?: 0
        var stopped = false
        // Chat replies (not App Builder pages or tool calls): stop when the model repeats itself.
        val guardLoops = prefix.isEmpty() && request.tools.isEmpty()
        var sinceCheck = 0
        var loopCut = -1
        // Each chat keeps its own phone model. If another chat switched the model (while this reply
        // waited for the engine), switch back after that chat's reply and go on: never answer with
        // the wrong model, never fail because of another chat.
        var wanted: String? = request.model
        var attempt = 0
        var outcome: Pair<Int, String>? = null
        // For the reply's speed line: when the engine started, wrote its first piece, finished.
        var tStart = 0L
        var tFirst = 0L
        var tEnd = 0L
        var info: IntArray? = null
        var engineName = ""
        while (outcome == null) {
            val current = host.loadedOrNull()
            if (current == null || !host.matches(current, wanted)) {
                if (attempt++ >= 4) throw LlmException("The phone model kept being switched by other chats; send again.")
                if (host.ensure(wanted.orEmpty()) == null) wanted = null // not a downloaded model's name: use the loaded one
                if (host.loadedOrNull() == null) throw LlmException("No local model is loaded. Load one in Model Manager.")
            }
            outcome = host.generationMutex.withLock {
                // Looked up once this reply holds the engine: a freed model must never be used.
                val loaded = host.loadedOrNull() ?: throw LlmException("The phone model was unloaded. Load one in Phone Models, then send again.")
                if (!host.matches(loaded, wanted)) return@withLock null // switched while waiting: go round again
                withContext(Dispatchers.Default) {
                val count = host.nativeWork(
                    LlamaBridge.engineOf(loaded.handle), loaded.modelId,
                    "${loaded.name} writing, context ${request.contextSize ?: loaded.contextSize}, up to $maxTokens tokens",
                ) { tStart = System.nanoTime(); LlamaBridge.generate(loaded.handle, roles, contents, maxTokens, temperature, 0.95f, request.contextSize ?: 0, prefix) { bytes ->
                    val piece = utf8.append(bytes)
                    if (piece.isNotEmpty()) {
                        if (tFirst == 0L) tFirst = System.nanoTime()
                        full.append(piece)
                        onEvent(LlmEvent.TextDelta(piece))
                        if (stops.isNotEmpty()) {
                            val tail = full.substring(maxOf(0, full.length - piece.length - longestStop))
                            if (stops.any { it in tail }) stopped = true
                        }
                        sinceCheck += piece.length
                        if (guardLoops && !stopped && (piece.contains('\n') || sinceCheck >= 120)) {
                            sinceCheck = 0
                            val cut = com.gravity.ide.core.LoopGuard.loopStart(full)
                            if (cut >= 0) {
                                loopCut = cut
                                stopped = true
                            }
                        }
                    }
                    ctx.isActive && !stopped && !host.abortRequested
                } }
                tEnd = System.nanoTime()
                info = runCatching { LlamaBridge.modelInfo(loaded.handle) }.getOrNull()
                // Read a long prompt ahead: keep it on disk so the next start of this model skips it.
                // Only when the prompt was actually read now (not already reused from a saved one).
                if (request.warmUp && count >= 0) info?.takeIf { it.size >= 8 && it[7] - it[5] > 64 }?.let { runCatching { host.saveState(loaded) } }
                engineName = runCatching { LlamaBridge.engineOf(loaded.handle).name }.getOrDefault("")
                count to runCatching { LlamaBridge.lastFinish() }.getOrDefault("")
                }
            }
        }
        val (generated, nativeFinish) = outcome
        val tail = utf8.flush()
        if (tail.isNotEmpty()) {
            full.append(tail)
            onEvent(LlmEvent.TextDelta(tail))
        }
        if (loopCut in 0 until full.length) {
            // Keep the first copy, drop the repeats.
            onEvent(LlmEvent.Retract(full.length - loopCut))
            full.setLength(loopCut)
        }
        if (generated < 0) throw LlmException("Local generation failed: ${LlamaBridge.lastError()}")
        if (request.tools.isNotEmpty()) {
            TextToolProtocol.parse(full.toString()).forEachIndexed { i, call ->
                onEvent(LlmEvent.ToolCallEvent(ToolCall("local_$i", call.name, call.argumentsJson)))
            }
        }
        onEvent(LlmEvent.Usage(0, generated))
        info?.takeIf { it.size >= 9 && tStart > 0 && generated > 0 }?.let { v ->
            val first = if (tFirst > 0) tFirst else tEnd
            onEvent(LlmEvent.Speed(v[7], v[5], (first - tStart) / 1_000_000, generated, (tEnd - first) / 1_000_000, v[8], engineName))
        }
        // Out of room = the token limit or a full context (the native side reports which).
        val outOfRoom = !stopped && (generated >= maxTokens || nativeFinish == "ctx")
        onEvent(LlmEvent.Done(if (outOfRoom) "MAX_TOKENS" else "STOP"))
    }

    /** Maps the conversation to plain (role, content) pairs for the GGUF chat template. */
    private fun flatten(request: LlmRequest): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val system = StringBuilder(request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.text })
        if (request.tools.isNotEmpty()) {
            if (system.isNotEmpty()) system.append("\n\n")
            // Compact tool list (names of arguments, not full JSON schemas): small models have small contexts.
            system.append(TextToolProtocol.instructions(request.tools.map { it.name to "${it.description} Arguments: ${argumentList(it.parameters)}" }))
        }
        if (system.isNotEmpty()) out += "system" to system.toString()
        for (m: LlmMessage in request.messages) {
            when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> out += "user" to (m.text + if (m.images.isNotEmpty()) "\n[${m.images.size} image(s) omitted: local model has no vision input]" else "")
                Role.ASSISTANT -> {
                    val calls = m.toolCalls.joinToString("\n") { "```tool\n{\"name\": \"${it.name}\", \"arguments\": ${it.argumentsJson}}\n```" }
                    out += "assistant" to listOf(m.text, calls).filter { it.isNotBlank() }.joinToString("\n")
                }
                Role.TOOL -> out += "user" to "Tool result for ${m.toolName}:\n${m.text}"
            }
        }
        // Templates generally require alternating turns: merge consecutive same-role entries.
        val merged = ArrayList<Pair<String, String>>()
        for (p in out) {
            val last = merged.lastOrNull()
            if (last != null && last.first == p.first) merged[merged.size - 1] = last.first to (last.second + "\n\n" + p.second) else merged += p
        }
        return merged
    }

    private fun argumentList(schema: org.json.JSONObject): String {
        val props = schema.optJSONObject("properties") ?: return "none"
        val required = schema.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.optString(it) }.toSet() } ?: emptySet()
        val names = props.keys().asSequence().map { if (it in required) it else "$it (optional)" }.toList()
        return if (names.isEmpty()) "none" else names.joinToString(", ")
    }

    override suspend fun listModels(): List<String> = listOfNotNull(modelName())

    companion object {
        const val ID = "local"
    }
}
