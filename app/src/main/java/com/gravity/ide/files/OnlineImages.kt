package com.gravity.ide.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Real AI pictures from Pollinations.ai's free image API (no account, no key; Flux model). Only
 * used when the user chose it (Settings → AI → Pictures and videos): the picture description is
 * sent to Pollinations. Without an account it allows one picture at a time, at least 45 seconds
 * apart, per internet address (its "Classic API" rules); going over answers "402 Payment Required"
 * with a Retry-After time. Nothing here is faked: a failure is reported, never replaced by a stand-in.
 */
object OnlineImages {
    const val SERVICE = "Pollinations.ai (Flux)"
    /** The free limit: one picture at least 45 s after the last (plus a little margin). */
    private const val GAP_MS = 46_000L
    private val lock = Mutex()
    @Volatile private var lastCall = 0L

    /** JPEG bytes of a [width]x[height] picture of [prompt]. Waits as needed to respect the free limit. */
    suspend fun generate(prompt: String, width: Int, height: Int, seed: Int = (Math.random() * 1_000_000).toInt()): ByteArray = lock.withLock {
        val p = prompt.trim().replace(Regex("\\s+"), " ").take(900)
        require(p.isNotEmpty()) { "No picture description" }
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(p, "UTF-8").replace("+", "%20") +
            "?width=$width&height=$height&model=flux&seed=$seed"
        var attempt = 0
        while (true) {
            val wait = lastCall + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
            var retryAfter = 0L
            val (code, type, bytes) = withContext(Dispatchers.IO) {
                com.gravity.ide.ai.Http.client(150).newCall(Request.Builder().url(url).header("User-Agent", "Yamini-Android").build()).execute().use { r ->
                    retryAfter = r.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                    Triple(r.code, r.header("Content-Type").orEmpty(), r.body?.bytes() ?: ByteArray(0))
                }
            }
            // Over the free limit (402, or 429): wait as long as the service says (Retry-After), if reasonable, and ask again.
            if ((code == 402 || code == 429) && attempt < 2 && retryAfter in 0..120) {
                attempt++
                delay(maxOf(retryAfter, 5) * 1000)
                continue
            }
            // Temporarily down (5xx): the free service often recovers in seconds; try twice more.
            if (code in 500..599 && attempt < 2) { attempt++; delay(4_000L * attempt); continue }
            if (code != 200 || !type.startsWith("image/")) {
                throw IllegalStateException(
                    "the online image AI answered HTTP $code" + when {
                        code == 429 -> " (too many pictures; wait a minute)"
                        code == 402 -> " (over its free limit for this internet connection${if (retryAfter > 0) "; free again in about ${retryAfter} s" else ""}; or choose \"Draw on this phone\" in Settings → AI → Pictures and videos)"
                        code in 500..599 -> " (the free service is busy or down right now; try again in a few minutes)"
                        else -> ""
                    }
                )
            }
            val check = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("the online image AI sent something that is not a picture")
            check.recycle()
            return@withLock bytes
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    suspend fun bitmap(prompt: String, width: Int, height: Int): Bitmap {
        val bytes = generate(prompt, width, height)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("not a picture")
        // Fill the frame (crop the middle) if the service sent another size.
        return cover(decoded, width, height)
    }

    /** [bmp] scaled to fill [width]x[height], the middle kept (recycles [bmp] if it made a new one). */
    fun cover(bmp: Bitmap, width: Int, height: Int): Bitmap {
        if (bmp.width == width && bmp.height == height) return bmp
        val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(width), (bmp.height * scale).toInt().coerceAtLeast(height), true)
        if (scaled != bmp) bmp.recycle()
        val out = Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height)
        if (out != scaled) scaled.recycle()
        return out
    }
}
