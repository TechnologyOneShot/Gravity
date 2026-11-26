package com.gravity.ide.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.gravity.ide.GravityApp
import com.gravity.ide.ai.Http
import com.gravity.ide.ai.ProviderKind
import com.gravity.ide.security.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

/**
 * Plays a reply in Gemini's online voice (see [OnlineVoice]): the first piece is asked for at once
 * and played as soon as it comes; the next pieces are asked for while it plays. Nothing is sent
 * unless the user switched the online voice on and has a Gemini key. On any problem the rest of
 * the reply is handed back for the phone's voice.
 */
object OnlineVoicePlayer {
    sealed interface Outcome {
        data object Done : Outcome
        data object Stopped : Outcome
        /** [rest] was not spoken (all of it when nothing played). */
        data class Failed(val reason: String, val rest: String) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** The track playing now (only paused from [stop]; its own job releases it). */
    @Volatile private var current: AudioTrack? = null

    private fun prefs(context: Context) = context.getSharedPreferences("live_voice", Context.MODE_PRIVATE)

    /** The user's Gemini key and server, or null when there is none. */
    suspend fun gemini(context: Context): Pair<String, String>? {
        val c = (context.applicationContext as? GravityApp)?.container ?: return null
        return c.db.providers().all().filter { it.enabled && it.kind == ProviderKind.GEMINI.name }.firstNotNullOfOrNull { p ->
            c.secrets.get(SecretStore.providerKey(p.id))?.takeIf { it.isNotBlank() }?.let { it to p.baseUrl.ifBlank { com.gravity.ide.ai.GeminiProvider.DEFAULT_BASE_URL } }
        }
    }

    /** One piece of speech from Gemini, trying the models in turn (the one that worked first). */
    suspend fun synthesize(context: Context, key: String, base: String, text: String, style: String, voice: String): Pair<ShortArray, Int> {
        val saved = prefs(context).getString("online_model", null)
        val order = (listOfNotNull(saved) + OnlineVoice.models).distinct()
        val client = Http.client(30)
        var last: Exception? = null
        for (model in order) {
            for (withStyle in listOf(true, false)) {
                if (!withStyle && style.isEmpty()) continue
                val req = Request.Builder().url("${base.trimEnd('/')}/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(Http.jsonBody(OnlineVoice.body(text, style, voice, model, withStyle))).build()
                val result = runCatching {
                    Http.execute(client, req) { r ->
                        if (!r.isSuccessful) {
                            val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
                            throw HttpProblem(r.code, body.take(600))
                        }
                        OnlineVoice.audioFrom(JSONObject(r.body!!.string()))
                    }
                }
                result.onSuccess {
                    if (saved != model) prefs(context).edit().putString("online_model", model).apply()
                    return it
                }
                val e = result.exceptionOrNull() as? Exception ?: continue
                last = e
                if (e is HttpProblem) {
                    when {
                        e.code == 401 || e.code == 403 -> throw IllegalStateException("Gemini refused the key (${e.code})")
                        e.code == 429 -> throw IllegalStateException("Gemini's limit for this key is reached for now")
                        // A model that does not take the style: try once without it.
                        e.code == 400 && withStyle && (e.body.contains("speech_metadata") || e.body.contains("Unknown name")) -> continue
                        // This model is not available to this key: the next one.
                        e.code == 404 || e.code == 400 -> break
                        else -> throw IllegalStateException("Gemini answered ${e.code}")
                    }
                } else {
                    throw IllegalStateException(e.message ?: "network error")
                }
            }
        }
        throw IllegalStateException("No Gemini voice model answered" + ((last as? HttpProblem)?.let { " (${it.code})" } ?: ""))
    }

    private class HttpProblem(val code: Int, val body: String) : Exception("HTTP $code")

    /**
     * Speaks [text] (already cleaned for speech) and calls [done] once, on a background thread:
     * Done, Stopped, or Failed with the part that was not spoken.
     */
    fun speak(context: Context, text: String, style: String, voice: String, done: (Outcome) -> Unit) {
        stop()
        val app = context.applicationContext
        job = scope.launch {
            val pieces = OnlineVoice.pieces(text)
            val g = gemini(app)
            if (g == null) { done(Outcome.Failed("no Gemini key (add one in AI Providers)", text)); return@launch }
            val ready = Channel<Pair<ShortArray, Int>>(capacity = 1)
            var failure: String? = null
            var made = 0
            // Ask for the next piece while the last one plays.
            val maker = launch {
                try {
                    for ((i, p) in pieces.withIndex()) {
                        val audio = if (i == 0) withTimeout(12_000) { synthesize(app, g.first, g.second, p, style, voice) } else synthesize(app, g.first, g.second, p, style, voice)
                        ready.send(audio)
                        made = i + 1
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!isActive) throw e
                    failure = "no answer in time"
                } catch (e: Exception) {
                    failure = e.message ?: e.javaClass.simpleName
                } finally {
                    ready.close()
                }
            }
            val focus = focus(app)
            var played = 0
            var t: AudioTrack? = null
            try {
                var frames = 0L
                for ((samples, rate) in ready) {
                    val tr = t ?: newTrack(rate).also { t = it; current = it; it.play() }
                    var off = 0
                    while (off < samples.size && isActive) {
                        val n = tr.write(samples, off, samples.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    frames += off
                    played++
                }
                t?.let { tr ->
                    // Let the last audio play out: stop() plays what is written, then the head stops moving.
                    tr.stop()
                    var last = -1
                    var still = 0
                    while (isActive && still < 400) {
                        val head = tr.playbackHeadPosition
                        if (head.toLong() >= frames) break
                        if (head == last) still += 50 else still = 0
                        last = head
                        delay(50)
                    }
                }
                maker.join()
                val f = failure
                when {
                    !isActive -> done(Outcome.Stopped)
                    f != null -> done(Outcome.Failed(f, pieces.drop(maxOf(played, made)).joinToString(" ")))
                    else -> done(Outcome.Done)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                done(Outcome.Stopped)
                throw e
            } finally {
                t?.let { tr -> if (current === tr) current = null; runCatching { tr.pause(); tr.flush(); tr.release() } }
                focus?.let { (am, req) -> runCatching { am.abandonAudioFocusRequest(req) } }
            }
        }
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate)) // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Asks other audio (music) to duck while Yamini speaks. */
    private fun focus(context: Context): Pair<AudioManager, AudioFocusRequest>? = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(req)
        am to req
    }.getOrNull()

    /** Stops what is playing (the reply's speaker button again, or another reply). */
    fun stop() {
        job?.cancel()
        job = null
        // Pausing makes a blocked write return; the job then releases the track.
        current?.let { runCatching { it.pause(); it.flush() } }
    }
}
