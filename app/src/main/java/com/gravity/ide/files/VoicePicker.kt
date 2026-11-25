package com.gravity.ide.files

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Picks the clearest installed text-to-speech voice for a language: highest quality, the right
 * country (hi-IN, en-IN), installed on the phone (works offline) before online-only voices.
 */
object VoicePicker {

    private fun installed(v: Voice) = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

    private fun sameLanguage(v: Voice, locale: Locale) = v.locale.language.equals(locale.language, ignoreCase = true)

    private fun score(v: Voice, locale: Locale): Int =
        v.quality + (if (v.locale.country.equals(locale.country, ignoreCase = true)) 100 else 0) +
            (if (v.isNetworkConnectionRequired) -60 else 0) - v.latency / 10

    /** Voices for [locale]'s language on this phone, best first. */
    fun voices(tts: TextToSpeech, locale: Locale): List<Voice> =
        (runCatching { tts.voices }.getOrNull() ?: emptySet())
            .filter { installed(it) && sameLanguage(it, locale) }
            .sortedByDescending { score(it, locale) }

    /** The voice named [preferred] if it is still installed, else the best one; null if none. */
    fun choose(tts: TextToSpeech, locale: Locale, preferred: String?): Voice? {
        val list = voices(tts, locale)
        return list.firstOrNull { it.name == preferred } ?: list.firstOrNull()
    }

    /** A short label for a voice: "Voice 2 · offline · very high quality". */
    fun label(v: Voice, index: Int): String {
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "very high quality"
            v.quality >= Voice.QUALITY_HIGH -> "high quality"
            else -> "normal quality"
        }
        val where = if (v.isNetworkConnectionRequired) "online" else "offline"
        val gender = v.name.lowercase().let { n -> if ("female" in n) " · female" else if ("male" in n) " · male" else "" }
        return "Voice ${index + 1}$gender · $where · $quality"
    }
}
