package com.gravity.ide.files

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gravity.ide.core.SpeechText
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reads a chat reply aloud (the speaker button under each reply): Hindi replies with a Hindi
 * voice, others with Indian English; the voice and speed chosen in live mode are used.
 * One reply at a time; tapping the same reply again stops it.
 */
object ReplySpeaker {
    /** The message being read aloud, or null. */
    val speakingId = MutableStateFlow<Long?>(null)
    /** Why the last request could not be read aloud, for a toast; cleared when read. */
    val problem = MutableStateFlow<String?>(null)

    private var tts: TextToSpeech? = null
    /** The engine [tts] runs on (null = the phone's default; this app = the natural voice). */
    private var ttsEngine: String? = null
    private var ready = false
    private var pending: Pair<Long, String>? = null
    private var appContext: Context? = null

    fun toggle(context: Context, id: Long, text: String) {
        if (speakingId.value == id) {
            stop()
            return
        }
        appContext = context.applicationContext
        speakingId.value = id
        // Hindi/Hinglish or English, on the natural voice if its pack is ready, else the phone's voice.
        val wanted = VoicePrefs.engine(context, VoicePrefs.isHindi(context, SpeechText.clean(text)))
        if (tts != null && wanted != ttsEngine) {
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null
            ready = false
        }
        val engine = tts
        if (engine != null && ready) {
            speak(engine, id, text)
            return
        }
        pending = id to text
        if (engine == null) {
            ttsEngine = wanted
            tts = VoicePrefs.create(context, wanted) { status ->
                ready = status == TextToSpeech.SUCCESS
                val p = pending
                pending = null
                val t = tts
                if (ready && t != null && p != null) {
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("reply-end-") == true) speakingId.compareAndSet(utteranceId.removePrefix("reply-end-").toLongOrNull(), null)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { speakingId.value = null }
                    })
                    speak(t, p.first, p.second)
                } else if (!ready) {
                    speakingId.value = null
                    problem.value = "This phone has no working text-to-speech voice (Android Settings → Text-to-speech)."
                    tts?.shutdown()
                    tts = null
                }
            }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun speak(t: TextToSpeech, id: Long, text: String) {
        val clean = SpeechText.clean(text)
        val ctx = appContext
        if (clean.isBlank() || ctx == null) {
            speakingId.value = null
            return
        }
        // Gemini's online voice when the user switched it on; the phone's voice if it cannot.
        if (VoicePrefs.online(ctx)) {
            runCatching { t.stop() }
            val style = com.gravity.ide.voice.OnlineVoice.style(VoicePrefs.moodFor(ctx, clean), VoicePrefs.mode(ctx))
            // Hinglish sentences in Devanagari, so Hindi words are said the Hindi way.
            val words = com.gravity.ide.core.HinglishReader.forVoice(ctx, clean).text
            com.gravity.ide.voice.OnlineVoicePlayer.speak(ctx, words, style, VoicePrefs.onlineVoice(ctx)) { outcome ->
                when (outcome) {
                    is com.gravity.ide.voice.OnlineVoicePlayer.Outcome.Failed -> main.post {
                        if (speakingId.value == id) {
                            problem.value = "Online voice: ${outcome.reason}. The phone's voice reads it."
                            if (outcome.rest.isBlank()) speakingId.value = null else speakOnPhone(t, id, outcome.rest)
                        }
                    }
                    else -> speakingId.compareAndSet(id, null)
                }
            }
            return
        }
        speakOnPhone(t, id, clean)
    }

    private fun speakOnPhone(t: TextToSpeech, id: Long, clean: String) {
        val ctx = appContext ?: return
        // The user's voice, speed, pitch and speaking style (Settings → Voice & speech).
        val hindi = VoicePrefs.isHindi(ctx, clean)
        val style = VoicePrefs.style(ctx)
        VoicePrefs.apply(ctx, t, hindi, style)
        VoicePrefs.speak(t, clean, style, "reply-$id", "reply-end-$id", flush = true)
    }

    fun stop() {
        com.gravity.ide.voice.OnlineVoicePlayer.stop()
        tts?.stop()
        speakingId.value = null
    }
}
