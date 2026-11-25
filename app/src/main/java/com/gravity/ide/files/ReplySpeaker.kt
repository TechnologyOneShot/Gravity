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
        val engine = tts
        if (engine != null && ready) {
            speak(engine, id, text)
            return
        }
        pending = id to text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
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

    private fun speak(t: TextToSpeech, id: Long, text: String) {
        val clean = SpeechText.clean(text)
        val ctx = appContext
        if (clean.isBlank() || ctx == null) {
            speakingId.value = null
            return
        }
        // The user's voice, speed, pitch and speaking style (Settings → Voice & speech).
        val hindi = VoicePrefs.isHindi(clean)
        val style = VoicePrefs.style(ctx)
        VoicePrefs.apply(ctx, t, hindi, style)
        VoicePrefs.speak(t, clean, style, "reply-$id", "reply-end-$id", flush = true)
    }

    fun stop() {
        tts?.stop()
        speakingId.value = null
    }
}
