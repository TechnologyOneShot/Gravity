package com.gravity.ide.voice

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService

/**
 * Yamini's natural voice as an Android text-to-speech engine (see [NeuralVoices]). Yamini's own
 * screens use it through Android's TextToSpeech with this app as the engine, so speed, pitch,
 * loudness, pauses, queueing and audio files work as with any phone voice. It speaks the
 * languages whose voice pack is downloaded (Hindi, English).
 */
class YaminiVoiceService : TextToSpeechService() {
    @Volatile
    private var stopped = false

    private fun langOf(iso3: String?): String? = when (iso3?.lowercase()) {
        "hin", "hi" -> "hi"
        "eng", "en" -> "en"
        else -> null
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val l = langOf(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (NeuralVoices.active(this, l) != null) {
            if (country.isNullOrEmpty()) TextToSpeech.LANG_AVAILABLE else TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else TextToSpeech.LANG_MISSING_DATA
    }

    override fun onGetLanguage(): Array<String> =
        if (NeuralVoices.active(this, "hi") != null) arrayOf("hin", "IND", "") else arrayOf("eng", "USA", "")

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        langOf(lang)?.let { NeuralVoices.preload(this, it) }
        return result
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped = false
        val pack = langOf(request.language)?.let { NeuralVoices.active(this, it) }
        if (pack == null) {
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        val text = request.charSequenceText?.toString().orEmpty()
        // Android's speed and pitch: 100 = normal.
        val speed = (request.speechRate.takeIf { it > 0 } ?: 100) / 100f
        val pitch = (request.pitch.takeIf { it > 0 } ?: 100) / 100f
        try {
            val engine = NeuralVoices.engine(this, pack)
            if (callback.start(engine.sampleRate(), AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
            if (text.isBlank()) {
                callback.done()
                return
            }
            NeuralVoices.speak(this, pack, text, speed, pitch, stopped = { stopped || callback.hasFinished() }) { bytes ->
                val max = callback.maxBufferSize.coerceAtLeast(1024)
                var off = 0
                while (off < bytes.size && !stopped) {
                    val n = minOf(max, bytes.size - off)
                    if (callback.audioAvailable(bytes, off, n) != TextToSpeech.SUCCESS) {
                        stopped = true
                        break
                    }
                    off += n
                }
            }
            callback.done()
        } catch (e: Throwable) {
            android.util.Log.w("YaminiVoice", "could not speak", e)
            if (!callback.hasFinished()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }
}
