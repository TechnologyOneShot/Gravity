package com.gravity.ide.ai

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Any server implementing the OpenAI Chat Completions API: OpenAI, OpenRouter, Groq,
 * Together, DeepSeek, Mistral, or a self-hosted llama.cpp server / Ollama / LM Studio
 * on the local network (`http://<pc>:11434/v1`).
 */
class OpenAiCompatibleProvider(
    override val providerId: String,
    private val apiKey: String?,
    baseUrl: String,
    timeoutSec: Int = 120,
    private val supportsReasoningEffort: Boolean = false,
    private val streamUsage: Boolean = true,
    override val isLocal: Boolean = false,
) : LlmProvider {

    private val base = baseUrl.trimEnd('/')
    private val client = Http.client(timeoutSec)

    private fun Request.Builder.auth(): Request.Builder = apply {
        if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey")
    }

    override suspend fun stream(request: LlmRequest, onEvent: (LlmEvent) -> Unit) {
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", encodeMessages(request.messages))
            .put("stream", true)
        if (streamUsage) body.put("stream_options", JSONObject().put("include_usage", true))
        request.temperature?.let { body.put("temperature", it) }
        request.maxOutputTokens?.let { body.put("max_tokens", it) }
        if (supportsReasoningEffort) body.put("reasoning_effort", ReasoningMapper.openAiEffort(request.reasoning))
        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (t in request.tools) {
                tools.put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters)
                    )
                )
            }
            body.put("tools", tools)
        }
        val httpRequest = Request.Builder().url("$base/chat/completions").auth().post(Http.jsonBody(body)).build()

        Http.execute(client, httpRequest) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            data class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
            val partials = sortedMapOf<Int, Partial>()
            var finish: String? = null
            var done = false
            Http.readSse(response) { data ->
                if (done) return@readSse
                if (data.trim() == "[DONE]") {
                    done = true
                    return@readSse
                }
                val obj = JSONObject(data)
                obj.optJSONObject("error")?.let { throw LlmException(it.optString("message", "Provider error")) }
                obj.optJSONObject("usage")?.let { u ->
                    onEvent(LlmEvent.Usage(u.optInt("prompt_tokens"), u.optInt("completion_tokens")))
                }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return@readSse
                val delta = choice.optJSONObject("delta")
                if (delta != null) {
                    val content = delta.opt("content")
                    if (content is String && content.isNotEmpty()) onEvent(LlmEvent.TextDelta(content))
                    delta.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val c = calls.getJSONObject(i)
                            val p = partials.getOrPut(c.optInt("index", i)) { Partial() }
                            c.optString("id").takeIf { it.isNotEmpty() }?.let { p.id = it }
                            c.optJSONObject("function")?.let { f ->
                                f.optString("name").takeIf { it.isNotEmpty() }?.let { p.name = it }
                                val a = f.opt("arguments")
                                if (a is String) p.args.append(a)
                            }
                        }
                    }
                }
                choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
            }
            for ((idx, p) in partials) {
                if (p.name.isEmpty()) continue
                onEvent(LlmEvent.ToolCallEvent(ToolCall(p.id.ifEmpty { "call_$idx" }, p.name, p.args.toString().ifBlank { "{}" })))
            }
            onEvent(LlmEvent.Done(finish))
        }
    }

    private fun encodeMessages(messages: List<LlmMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            when (m.role) {
                Role.SYSTEM -> arr.put(JSONObject().put("role", "system").put("content", m.text))
                Role.USER -> {
                    if (m.images.isEmpty()) {
                        arr.put(JSONObject().put("role", "user").put("content", m.text))
                    } else {
                        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", m.text))
                        for (img in m.images) {
                            parts.put(
                                JSONObject().put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", "data:${img.mimeType};base64,${img.base64}"))
                            )
                        }
                        arr.put(JSONObject().put("role", "user").put("content", parts))
                    }
                }
                Role.ASSISTANT -> {
                    val o = JSONObject().put("role", "assistant")
                    o.put("content", if (m.text.isEmpty() && m.toolCalls.isNotEmpty()) JSONObject.NULL else m.text)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        for (c in m.toolCalls) {
                            calls.put(
                                JSONObject().put("id", c.id).put("type", "function")
                                    .put("function", JSONObject().put("name", c.name).put("arguments", c.argumentsJson))
                            )
                        }
                        o.put("tool_calls", calls)
                    }
                    arr.put(o)
                }
                Role.TOOL -> arr.put(
                    JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId ?: "").put("content", m.text)
                )
            }
        }
        return arr
    }

    override suspend fun listModels(): List<String> {
        val request = Request.Builder().url("$base/models").auth().get().build()
        return Http.execute(client, request) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotEmpty() }.sorted()
        }
    }
}
