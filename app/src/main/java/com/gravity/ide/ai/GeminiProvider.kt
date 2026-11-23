package com.gravity.ide.ai

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini via the public REST API (`generativelanguage.googleapis.com/v1beta`).
 * Streaming uses `streamGenerateContent?alt=sse`; function calling, Google Search grounding,
 * vision input and thinking configuration are real request fields.
 */
class GeminiProvider(
    override val providerId: String,
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    timeoutSec: Int = 120,
) : LlmProvider {

    override val isLocal: Boolean = false
    private val base = baseUrl.trimEnd('/')
    private val client = Http.client(timeoutSec)

    override suspend fun stream(request: LlmRequest, onEvent: (LlmEvent) -> Unit) {
        val model = request.model.removePrefix("models/")
        val url = "$base/v1beta/models/$model:streamGenerateContent?alt=sse"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(Http.jsonBody(buildBody(request)))
            .build()
        Http.execute(client, httpRequest) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            var finish: String? = null
            var synthetic = 0
            Http.readSse(response) { data ->
                val obj = JSONObject(data)
                obj.optJSONObject("error")?.let { err ->
                    throw LlmException(err.optString("message", "Gemini error"), err.optInt("code"), retryable = err.optInt("code") == 429)
                }
                val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
                val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        // Thought summaries are internal reasoning; never surfaced.
                        if (part.optBoolean("thought", false)) continue
                        val signature = part.optString("thoughtSignature").ifEmpty { null }
                        val fc = part.optJSONObject("functionCall")
                        if (fc != null) {
                            val apiId = fc.optString("id")
                            val id = if (apiId.isNotEmpty()) apiId else "$SYNTHETIC_PREFIX${synthetic++}"
                            onEvent(
                                LlmEvent.ToolCallEvent(
                                    ToolCall(id, fc.getString("name"), (fc.optJSONObject("args") ?: JSONObject()).toString(), signature)
                                )
                            )
                        } else if (part.has("text")) {
                            val t = part.getString("text")
                            if (t.isNotEmpty()) onEvent(LlmEvent.TextDelta(t))
                        }
                    }
                }
                candidate?.optString("finishReason")?.takeIf { it.isNotEmpty() }?.let { finish = it }
                candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
                    val sources = (0 until chunks.length()).mapNotNull { j ->
                        chunks.optJSONObject(j)?.optJSONObject("web")?.let { web ->
                            WebSource(web.optString("title", web.optString("uri")), web.optString("uri"))
                        }
                    }.filter { it.url.isNotEmpty() }
                    if (sources.isNotEmpty()) onEvent(LlmEvent.Sources(sources))
                }
                obj.optJSONObject("usageMetadata")?.let { u ->
                    onEvent(LlmEvent.Usage(u.optInt("promptTokenCount"), u.optInt("candidatesTokenCount") + u.optInt("thoughtsTokenCount")))
                }
            }
            if (finish == "SAFETY" || finish == "RECITATION" || finish == "PROHIBITED_CONTENT") {
                throw LlmException("Gemini stopped the response (finishReason=$finish)")
            }
            onEvent(LlmEvent.Done(finish))
        }
    }

    private fun buildBody(request: LlmRequest): JSONObject {
        val root = JSONObject()
        val system = request.messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.text }
        if (system.isNotBlank()) {
            root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val contents = JSONArray()
        var toolParts: JSONArray? = null
        fun flushTools() {
            toolParts?.let { contents.put(JSONObject().put("role", "user").put("parts", it)) }
            toolParts = null
        }
        for (m in request.messages) {
            when (m.role) {
                Role.SYSTEM -> Unit
                Role.USER -> {
                    flushTools()
                    val parts = JSONArray()
                    if (m.text.isNotEmpty() || m.images.isEmpty()) parts.put(JSONObject().put("text", m.text))
                    for (img in m.images) {
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", img.mimeType).put("data", img.base64)))
                    }
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
                }
                Role.ASSISTANT -> {
                    flushTools()
                    val parts = JSONArray()
                    if (m.text.isNotEmpty()) parts.put(JSONObject().put("text", m.text))
                    for (c in m.toolCalls) {
                        val call = JSONObject().put("name", c.name).put("args", parseArgs(c.argumentsJson))
                        if (!c.id.startsWith(SYNTHETIC_PREFIX)) call.put("id", c.id)
                        val part = JSONObject().put("functionCall", call)
                        if (c.providerMeta != null) part.put("thoughtSignature", c.providerMeta)
                        parts.put(part)
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                Role.TOOL -> {
                    val resp = JSONObject().put("name", m.toolName ?: "tool").put("response", JSONObject().put("result", m.text))
                    val id = m.toolCallId
                    if (id != null && !id.startsWith(SYNTHETIC_PREFIX)) resp.put("id", id)
                    val parts = toolParts ?: JSONArray().also { toolParts = it }
                    parts.put(JSONObject().put("functionResponse", resp))
                }
            }
        }
        flushTools()
        root.put("contents", contents)

        val tools = JSONArray()
        if (request.tools.isNotEmpty()) {
            val decls = JSONArray()
            for (t in request.tools) {
                decls.put(JSONObject().put("name", t.name).put("description", t.description).put("parameters", toGeminiSchema(t.parameters)))
            }
            tools.put(JSONObject().put("functionDeclarations", decls))
        } else if (request.webGrounding) {
            // Search grounding cannot be combined with function declarations on most models.
            tools.put(JSONObject().put("googleSearch", JSONObject()))
        }
        if (tools.length() > 0) root.put("tools", tools)

        val gen = JSONObject()
        request.temperature?.let { gen.put("temperature", it) }
        request.maxOutputTokens?.let { gen.put("maxOutputTokens", it) }
        when (val thinking = ReasoningMapper.gemini(request.model, request.reasoning)) {
            is ReasoningMapper.GeminiThinking.Budget -> gen.put("thinkingConfig", JSONObject().put("thinkingBudget", thinking.tokens))
            is ReasoningMapper.GeminiThinking.Level -> gen.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking.level))
            ReasoningMapper.GeminiThinking.None -> Unit
        }
        if (gen.length() > 0) root.put("generationConfig", gen)
        return root
    }

    override suspend fun listModels(): List<String> {
        val request = Request.Builder().url("$base/v1beta/models?pageSize=200").header("x-goog-api-key", apiKey).get().build()
        return Http.execute(client, request) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")
                val generates = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (generates) m.optString("name").removePrefix("models/") else null
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val SYNTHETIC_PREFIX = "gsyn_"

        fun parseArgs(json: String): JSONObject = try { JSONObject(json) } catch (_: Exception) { JSONObject() }

        /** Gemini's schema dialect uses upper-case OpenAPI type names. */
        fun toGeminiSchema(schema: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in schema.keys()) {
                val value = schema.get(key)
                when {
                    key == "type" && value is String -> out.put("type", value.uppercase())
                    key == "properties" && value is JSONObject -> {
                        val props = JSONObject()
                        for (p in value.keys()) props.put(p, toGeminiSchema(value.getJSONObject(p)))
                        out.put("properties", props)
                    }
                    key == "items" && value is JSONObject -> out.put("items", toGeminiSchema(value))
                    key == "additionalProperties" || key == "\$schema" -> Unit
                    else -> out.put(key, value)
                }
            }
            return out
        }
    }
}
