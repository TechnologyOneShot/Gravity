package com.gravity.ide.ai

import com.gravity.ide.core.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val JSON = "application/json; charset=utf-8".toMediaType()

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun client(timeoutSec: Int): OkHttpClient = base.newBuilder()
        .readTimeout(timeoutSec.toLong().coerceAtLeast(10), TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody(JSON)

    /**
     * Executes [request] on the IO dispatcher. Cancelling the calling coroutine cancels the
     * HTTP call immediately (a watcher coroutine calls `Call.cancel()`), so a blocked stream
     * read is interrupted instead of hanging.
     */
    suspend fun <T> execute(client: OkHttpClient, request: Request, block: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val watcher = launch(Dispatchers.Default) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } catch (e: IOException) {
            ensureActive()
            throw LlmException("Network error: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        } finally {
            watcher.cancel()
        }
    }

    /** Reads a server-sent-events body, invoking [onData] for each event payload. */
    fun readSse(response: Response, onData: (String) -> Unit) {
        val source = response.body?.source() ?: return
        val parser = SseParser()
        while (true) {
            val line = source.readUtf8Line() ?: break
            parser.feed(line)?.let(onData)
        }
        parser.finish()?.let(onData)
    }

    /** Converts an error response into a readable, typed exception. */
    fun errorFrom(response: Response): LlmException {
        val code = response.code
        val raw = try { response.body?.string().orEmpty() } catch (_: Exception) { "" }
        val message = try {
            val o = JSONObject(raw)
            val err = o.optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").ifBlank { raw }
        } catch (_: Exception) {
            raw
        }.take(600)
        val retryable = code == 429 || code >= 500
        val hint = when (code) {
            400 -> "Bad request"
            401, 403 -> "Authentication failed — check the API key"
            404 -> "Model or endpoint not found"
            429 -> "Rate limited — retry later"
            else -> "HTTP $code"
        }
        return LlmException("$hint: $message", httpCode = code, retryable = retryable)
    }
}

/** Retries retryable failures with exponential backoff (rate limits, 5xx, network). */
suspend fun <T> withRetry(attempts: Int = 3, initialDelayMs: Long = 1500, block: suspend (attempt: Int) -> T): T {
    var delayMs = initialDelayMs
    var last: LlmException? = null
    for (attempt in 1..attempts) {
        try {
            return block(attempt)
        } catch (e: LlmException) {
            last = e
            if (!e.retryable || attempt == attempts) throw e
            kotlinx.coroutines.delay(delayMs)
            delayMs *= 2
        }
    }
    throw last ?: LlmException("Request failed")
}
