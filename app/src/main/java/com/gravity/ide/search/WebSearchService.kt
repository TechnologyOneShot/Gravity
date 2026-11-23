package com.gravity.ide.search

import com.gravity.ide.ai.Http
import com.gravity.ide.ai.LlmException
import com.gravity.ide.ai.WebSource
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.network.NetworkMonitor
import com.gravity.ide.security.SecretStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Web search through a configured provider (Brave Search API). Results are the provider's
 * real results; nothing is generated. Gemini chats can additionally use Google Search
 * grounding, whose sources come back in the model response.
 */
class WebSearchService(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val network: NetworkMonitor,
    private val events: EventLog,
) {
    class SearchUnavailable(message: String) : Exception(message)

    suspend fun isConfigured(): Boolean =
        settings.current().webSearchProvider == "brave" && !secrets.get(SecretStore.BRAVE_KEY).isNullOrBlank()

    suspend fun search(query: String, count: Int = 10): List<WebSource> {
        if (!network.isOnline) throw SearchUnavailable("Web search is unavailable offline. Local project search still works.")
        val provider = settings.current().webSearchProvider
        if (provider != "brave") throw SearchUnavailable("No web search provider configured (Settings → Web Search).")
        val key = secrets.get(SecretStore.BRAVE_KEY) ?: throw SearchUnavailable("Brave Search API key missing (Settings → Web Search).")
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", count.coerceIn(1, 20).toString())
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("X-Subscription-Token", key).get().build()
        val results = Http.execute(Http.base, request) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val arr = JSONObject(response.body?.string().orEmpty()).optJSONObject("web")?.optJSONArray("results")
            if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                WebSource(r.optString("title"), r.optString("url"), stripTags(r.optString("description")))
            }
        }
        events.log(EventCategory.TOOL, "Web search: ${query.take(80)}", "${results.size} results", tool = "web_search")
        return results
    }

    /** Fetches a page and returns readable text (scripts/styles/tags removed), truncated. */
    suspend fun fetchText(url: String, maxChars: Int = 40_000): String {
        if (!network.isOnline) throw SearchUnavailable("Offline")
        val parsed = try { url.toHttpUrl() } catch (e: IllegalArgumentException) { throw LlmException("Invalid URL: $url") }
        if (parsed.scheme != "https" && parsed.scheme != "http") throw LlmException("Only http(s) URLs can be fetched")
        val request = Request.Builder().url(parsed).header("User-Agent", "Gravity/0.1 (Android)").get().build()
        val text = Http.execute(Http.base, request) { response ->
            if (!response.isSuccessful) throw Http.errorFrom(response)
            val type = response.header("Content-Type").orEmpty()
            val body = response.body?.source()?.let { src ->
                src.request(2_000_000)
                src.buffer.clone().readUtf8()
            }.orEmpty()
            if (type.contains("html")) htmlToText(body) else body
        }
        events.log(EventCategory.TOOL, "Fetched ${parsed.host}", url, tool = "fetch_url")
        return if (text.length > maxChars) text.take(maxChars) + "\n… (truncated)" else text
    }

    companion object {
        fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'")

        fun htmlToText(html: String): String = html
            .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr|pre)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n\n")
            .trim()
    }
}
