package com.gravity.ide.ai

import org.json.JSONArray
import org.json.JSONObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ImagePart(val mimeType: String, val base64: String)

/** [providerMeta] carries provider-specific data that must be echoed back (e.g. Gemini thought signatures). */
data class ToolCall(val id: String, val name: String, val argumentsJson: String, val providerMeta: String? = null)

data class LlmMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val images: List<ImagePart> = emptyList(),
)

data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val reasoning: ReasoningMode = ReasoningMode.BALANCED,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val webGrounding: Boolean = false,
    /** On-device only: context for this request (larger than the loaded size when memory allows). */
    val contextSize: Int? = null,
    /** On-device only: the reply starts with this text and the model continues from it. */
    val assistantPrefix: String? = null,
    /** On-device only: stop as soon as the reply contains one of these (kept in the reply). */
    val stop: List<String> = emptyList(),
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ToolCallEvent(val call: ToolCall) : LlmEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : LlmEvent
    data class Sources(val sources: List<WebSource>) : LlmEvent
    data class Done(val finishReason: String?) : LlmEvent
    /** Take back the last [chars] characters already sent (a phone model started repeating itself). */
    data class Retract(val chars: Int) : LlmEvent
    /** The request was retried with [to] instead of [from] (retired model, or its free quota is used up); [reason] says why. */
    data class ModelSwitched(val from: String, val to: String, val reason: String = "") : LlmEvent
    /**
     * How fast a phone model answered: it read [promptTokens] ([reusedTokens] of them already read
     * before) in [readMs], then wrote [outputTokens] in [writeMs], on [threads] threads of [engine].
     */
    data class Speed(
        val promptTokens: Int, val reusedTokens: Int, val readMs: Long,
        val outputTokens: Int, val writeMs: Long, val threads: Int, val engine: String,
    ) : LlmEvent
}

data class WebSource(val title: String, val url: String, val snippet: String = "")

open class LlmException(
    message: String,
    val httpCode: Int? = null,
    val retryable: Boolean = false,
) : Exception(message)

/** Collected result of a streamed call. */
data class LlmResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    val inputTokens: Int,
    val outputTokens: Int,
    val finishReason: String?,
    val sources: List<WebSource>,
)

interface LlmProvider {
    val providerId: String
    val isLocal: Boolean

    /** Streams events to [onEvent] (called on a background thread). Cancellable. */
    suspend fun stream(request: LlmRequest, onEvent: (LlmEvent) -> Unit)

    suspend fun listModels(): List<String>
}

suspend fun LlmProvider.complete(request: LlmRequest, onText: (String) -> Unit = {}): LlmResult {
    val text = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    val sources = mutableListOf<WebSource>()
    var inTok = 0
    var outTok = 0
    var finish: String? = null
    stream(request) { ev ->
        when (ev) {
            is LlmEvent.TextDelta -> {
                text.append(ev.text)
                onText(text.toString())
            }
            is LlmEvent.ToolCallEvent -> calls += ev.call
            is LlmEvent.Usage -> {
                inTok = maxOf(inTok, ev.inputTokens)
                outTok = maxOf(outTok, ev.outputTokens)
            }
            is LlmEvent.Sources -> sources += ev.sources
            is LlmEvent.Done -> finish = ev.finishReason
            is LlmEvent.ModelSwitched, is LlmEvent.Speed -> Unit
            is LlmEvent.Retract -> {
                text.setLength(maxOf(0, text.length - ev.chars))
                onText(text.toString())
            }
        }
    }
    return LlmResult(text.toString(), calls, inTok, outTok, finish, sources.distinctBy { it.url })
}

/** Serialisation of conversations so interrupted agent tasks can be resumed after a restart. */
object LlmMessageCodec {
    fun encode(messages: List<LlmMessage>): String {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role.name)
            o.put("text", m.text)
            if (m.toolCallId != null) o.put("toolCallId", m.toolCallId)
            if (m.toolName != null) o.put("toolName", m.toolName)
            if (m.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                for (c in m.toolCalls) {
                    calls.put(
                        JSONObject().put("id", c.id).put("name", c.name).put("args", c.argumentsJson)
                            .apply { if (c.providerMeta != null) put("meta", c.providerMeta) }
                    )
                }
                o.put("toolCalls", calls)
            }
            // Images are not persisted in task state (size); they are re-attached on demand.
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String?): List<LlmMessage> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        val out = ArrayList<LlmMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val callsArr = o.optJSONArray("toolCalls")
            val calls = if (callsArr == null) emptyList() else (0 until callsArr.length()).map { j ->
                val c = callsArr.getJSONObject(j)
                ToolCall(c.getString("id"), c.getString("name"), c.optString("args", "{}"), c.optString("meta").ifEmpty { null })
            }
            out += LlmMessage(
                role = Role.valueOf(o.getString("role")),
                text = o.optString("text"),
                toolCalls = calls,
                toolCallId = o.optString("toolCallId").ifEmpty { null },
                toolName = o.optString("toolName").ifEmpty { null },
            )
        }
        return out
    }
}
