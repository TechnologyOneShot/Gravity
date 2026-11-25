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
 * sent to Pollinations. Without an account it allows about one picture every 15 seconds and may
 * add a small watermark. Nothing here is faked: a failure is reported, never replaced by a stand-in.
 */
object OnlineImages {
    const val SERVICE = "Pollinations.ai (Flux)"
    private const val GAP_MS = 15_500L
    private val lock = Mutex()
    @Volatile private var lastCall = 0L

    /** JPEG bytes of a [width]x[height] picture of [prompt]. Waits as needed to respect the free limit. */
    suspend fun generate(prompt: String, width: Int, height: Int, seed: Int = (Math.random() * 1_000_000).toInt()): ByteArray = lock.withLock {
        val p = prompt.trim().replace(Regex("\\s+"), " ").take(900)
        require(p.isNotEmpty()) { "No picture description" }
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(p, "UTF-8").replace("+", "%20") +
            "?width=$width&height=$height&model=flux&nologo=true&seed=$seed"
        var attempt = 0
        while (true) {
            val wait = lastCall + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
            val (code, type, bytes) = withContext(Dispatchers.IO) {
                com.gravity.ide.ai.Http.client(150).newCall(Request.Builder().url(url).header("User-Agent", "Gravity-Android").build()).execute().use { r ->
                    Triple(r.code, r.header("Content-Type").orEmpty(), r.body?.bytes() ?: ByteArray(0))
                }
            }
            // Busy (429) or temporarily down (5xx): the free service often recovers in seconds; try twice more.
            if ((code == 429 || code in 500..599) && attempt < 2) { attempt++; delay(4_000L * attempt); continue }
            if (code != 200 || !type.startsWith("image/")) {
                throw IllegalStateException(
                    "the online image AI answered HTTP $code" + when {
                        code == 429 -> " (too many pictures; wait a minute)"
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
        if (decoded.width == width && decoded.height == height) return decoded
        // Fill the frame (crop the middle) if the service sent another size.
        val scale = maxOf(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(width), (decoded.height * scale).toInt().coerceAtLeast(height), true)
        if (scaled != decoded) decoded.recycle()
        val out = Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height)
        if (out != scaled) scaled.recycle()
        return out
    }
}
