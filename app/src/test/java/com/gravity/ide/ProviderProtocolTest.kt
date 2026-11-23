package com.gravity.ide

import com.gravity.ide.agent.ContextWindow
import com.gravity.ide.ai.GeminiProvider
import com.gravity.ide.ai.LlmException
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmMessageCodec
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.OpenAiCompatibleProvider
import com.gravity.ide.ai.ReasoningMapper
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.ToolCall
import com.gravity.ide.ai.ToolSpec
import com.gravity.ide.ai.complete
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real request builders and streaming parsers against a local HTTP server
 * that replays the providers' documented wire formats.
 */
class ProviderProtocolTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private val tool = ToolSpec(
        "read_file", "Read a file",
        JSONObject("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
    )

    @Test fun openAiStreamingTextAndToolCalls() = runBlocking {
        val body = listOf(
            """{"choices":[{"delta":{"content":"Hel"}}]}""",
            """{"choices":[{"delta":{"content":"lo"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.kt\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val provider = OpenAiCompatibleProvider("p", "sk-test", server.url("/v1").toString().trimEnd('/'))
        val result = provider.complete(LlmRequest("m", listOf(LlmMessage(Role.USER, "hi")), tools = listOf(tool)))
        assertEquals("Hello", result.text)
        assertEquals(1, result.toolCalls.size)
        assertEquals("read_file", result.toolCalls[0].name)
        assertEquals("a.kt", JSONObject(result.toolCalls[0].argumentsJson).getString("path"))
        assertEquals(12, result.inputTokens)
        assertEquals(7, result.outputTokens)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals(true, sent.getBoolean("stream"))
        assertEquals("function", sent.getJSONArray("tools").getJSONObject(0).getString("type"))
    }

    @Test fun openAiErrorsAreTyped() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val provider = OpenAiCompatibleProvider("p", "x", server.url("/v1").toString().trimEnd('/'))
        try {
            provider.complete(LlmRequest("m", listOf(LlmMessage(Role.USER, "hi"))))
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(401, e.httpCode)
            assertTrue(e.message!!.contains("bad key"))
        }
    }

    @Test fun geminiStreamingFunctionCallWithSignature() = runBlocking {
        val body = listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Plan: "}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"secret"},{"functionCall":{"name":"read_file","args":{"path":"b.kt"}},"thoughtSignature":"sig123"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":5}}""",
        ).joinToString("") { "data: $it\n\n" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        val provider = GeminiProvider("g", "AIzaTEST", server.url("/").toString().trimEnd('/'))
        val result = provider.complete(LlmRequest("gemini-2.5-flash", listOf(LlmMessage(Role.SYSTEM, "sys"), LlmMessage(Role.USER, "go")), tools = listOf(tool), reasoning = ReasoningMode.DEEP_THINK))
        assertEquals("Plan: ", result.text) // thought parts are never surfaced
        assertEquals("read_file", result.toolCalls.single().name)
        assertEquals("sig123", result.toolCalls.single().providerMeta)
        assertEquals(20, result.inputTokens)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
        assertEquals("AIzaTEST", recorded.getHeader("x-goog-api-key"))
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals("sys", sent.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(8192, sent.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        val params = sent.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters")
        assertEquals("OBJECT", params.getString("type"))
    }

    @Test fun geminiEchoesSignatureAndGroupsToolResults() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n"))
        val provider = GeminiProvider("g", "k", server.url("/").toString().trimEnd('/'))
        val history = listOf(
            LlmMessage(Role.USER, "go"),
            LlmMessage(Role.ASSISTANT, "", toolCalls = listOf(ToolCall("gsyn_0", "read_file", "{\"path\":\"a\"}", "sigA"), ToolCall("gsyn_1", "read_file", "{\"path\":\"b\"}"))),
            LlmMessage(Role.TOOL, "A", toolCallId = "gsyn_0", toolName = "read_file"),
            LlmMessage(Role.TOOL, "B", toolCallId = "gsyn_1", toolName = "read_file"),
        )
        provider.complete(LlmRequest("gemini-2.0-flash", history))
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        val contents = sent.getJSONArray("contents")
        assertEquals(3, contents.length())
        val modelParts = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals("sigA", modelParts.getJSONObject(0).getString("thoughtSignature"))
        assertEquals(2, contents.getJSONObject(2).getJSONArray("parts").length())
        assertTrue(!sent.has("generationConfig")) // gemini-2.0 takes no thinking config
    }

    @Test fun reasoningMapping() {
        assertEquals(ReasoningMapper.GeminiThinking.Budget(0), ReasoningMapper.gemini("gemini-2.5-flash", ReasoningMode.FAST))
        assertEquals(ReasoningMapper.GeminiThinking.Budget(128), ReasoningMapper.gemini("gemini-2.5-pro", ReasoningMode.FAST))
        assertEquals(ReasoningMapper.GeminiThinking.Level("high"), ReasoningMapper.gemini("gemini-3-pro-preview", ReasoningMode.HIGH))
        assertEquals(ReasoningMapper.GeminiThinking.None, ReasoningMapper.gemini("gemini-1.5-flash", ReasoningMode.HIGH))
        assertEquals("high", ReasoningMapper.openAiEffort(ReasoningMode.MAXIMUM))
    }

    @Test fun conversationCodecRoundTrip() {
        val msgs = listOf(
            LlmMessage(Role.SYSTEM, "s"),
            LlmMessage(Role.ASSISTANT, "t", toolCalls = listOf(ToolCall("1", "run_command", "{\"command\":\"ls\"}", "meta"))),
            LlmMessage(Role.TOOL, "out", toolCallId = "1", toolName = "run_command"),
        )
        assertEquals(msgs, LlmMessageCodec.decode(LlmMessageCodec.encode(msgs)))
    }

    @Test fun contextWindowNeverStartsTailWithOrphanToolResult() {
        val msgs = mutableListOf(LlmMessage(Role.SYSTEM, "s"), LlmMessage(Role.USER, "task"))
        repeat(30) { i ->
            msgs += LlmMessage(Role.ASSISTANT, "step $i", toolCalls = listOf(ToolCall("c$i", "read_file", "{}")))
            msgs += LlmMessage(Role.TOOL, "x".repeat(2000), toolCallId = "c$i", toolName = "read_file")
        }
        val fitted = ContextWindow.fit(msgs, 10_000)
        assertEquals(Role.SYSTEM, fitted[0].role)
        val afterMarker = fitted[3]
        assertTrue(afterMarker.role != Role.TOOL)
        assertTrue(fitted.sumOf { it.text.length } <= 12_000)
    }
}
