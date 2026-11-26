package com.gravity.ide.files

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.gravity.ide.core.VoiceStyles
import java.util.Locale

/**
 * The user's voice settings (Settings → Voice & speech, and live mode): speaking style, the voice
 * for Hindi and for English, speed and pitch. Used by live mode, the read-aloud button and audio files.
 */
object VoicePrefs {
    const val HINDI = "voice_HINDI"
    const val ENGLISH = "voice_ENGLISH"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("live_voice", Context.MODE_PRIVATE)

    fun style(context: Context): VoiceStyles.Style = VoiceStyles.byId(prefs(context).getString("style", null))
    fun rate(context: Context): Float = prefs(context).getFloat("rate", 1.0f)
    fun pitch(context: Context): Float = prefs(context).getFloat("pitch", 1.0f)
    fun voiceName(context: Context, hindi: Boolean): String? = prefs(context).getString(if (hindi) HINDI else ENGLISH, null)

    /** Live mode answers with the cloud AI (fast) instead of a phone model; off until the user turns it on. */
    fun fastest(context: Context): Boolean = prefs(context).getBoolean("fastest", false)
    fun setFastest(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fastest", on).apply()

    fun setStyle(context: Context, id: String) = prefs(context).edit().putString("style", id).apply()
    fun setRate(context: Context, v: Float) = prefs(context).edit().putFloat("rate", v).apply()
    fun setPitch(context: Context, v: Float) = prefs(context).edit().putFloat("pitch", v).apply()
    fun setVoice(context: Context, hindi: Boolean, name: String?) =
        prefs(context).edit().apply { if (name == null) remove(if (hindi) HINDI else ENGLISH) else putString(if (hindi) HINDI else ENGLISH, name) }.apply()

    /** How Gravity addresses the user in live mode ("Sir", "Boss", a name); blank = the style's own way. */
    fun address(context: Context): String = prefs(context).getString("address", "").orEmpty()
    fun setAddress(context: Context, v: String) = prefs(context).edit().putString("address", v.trim().take(40)).apply()

    /** Live mode greets the user when it opens (on by default). */
    /** Speak like a person (HumanSpeech): speed, pitch and loudness vary per phrase. On by default. */
    fun natural(context: Context): Boolean = prefs(context).getBoolean("natural", true)
    fun setNatural(context: Context, on: Boolean) = prefs(context).edit().putBoolean("natural", on).apply()

    /** What [apply] set on each speaker: its speed and pitch, and whether to speak like a person. */
    private data class Base(val rate: Float, val pitch: Float, val natural: Boolean)
    private val bases = java.util.WeakHashMap<TextToSpeech, Base>()

    fun greet(context: Context): Boolean = prefs(context).getBoolean("greet", true)
    fun setGreet(context: Context, on: Boolean) = prefs(context).edit().putBoolean("greet", on).apply()

    /** English voice accent: IN (India), GB (British) or US. */
    fun accent(context: Context): String = prefs(context).getString("accent", "IN") ?: "IN"
    fun setAccent(context: Context, v: String) = prefs(context).edit().putString("accent", v).remove(ENGLISH).apply() // voices differ per accent

    fun locale(hindi: Boolean): Locale = Locale.forLanguageTag(if (hindi) "hi-IN" else "en-IN")
    fun locale(context: Context, hindi: Boolean): Locale = if (hindi) locale(true) else Locale.forLanguageTag("en-${accent(context)}")

    /** True when [text] is mostly Hindi in Devanagari (then a Hindi voice reads it). */
    fun isHindi(text: String): Boolean {
        val devanagari = text.count { it in 'ऀ'..'ॿ' }
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        return devanagari * 10 > letters * 3
    }

    /** Sets language, voice, speed and pitch on [tts]; [style] overrides the saved one (e.g. a preview). */
    fun apply(context: Context, tts: TextToSpeech, hindi: Boolean, style: VoiceStyles.Style = style(context), voice: Voice? = null): Int {
        val locale = locale(context, hindi)
        val result = tts.setLanguage(locale)
        (voice ?: VoicePicker.choose(tts, locale, voiceName(context, hindi)))?.let { tts.setVoice(it) }
        val base = Base(VoiceStyles.rate(style, rate(context)), VoiceStyles.pitch(style, pitch(context)), natural(context))
        tts.setSpeechRate(base.rate)
        tts.setPitch(base.pitch)
        synchronized(bases) { bases[tts] = base }
        return result
    }

    /**
     * Queues [text] in the style: sentence by sentence with the style's pause between them. The
     * last piece gets [lastId] (for "finished" callbacks). [flush] clears what was playing first.
     */
    fun speak(tts: TextToSpeech, text: String, style: VoiceStyles.Style, idPrefix: String, lastId: String, flush: Boolean) {
        val base = synchronized(bases) { bases[tts] }
        if (base?.natural == true && text.isNotBlank()) {
            speakLikeAPerson(tts, text, style, base, idPrefix, lastId, flush)
            return
        }
        val pieces = if (style.pauseMs > 0) VoiceStyles.sentences(text) else listOf(text)
        val all = pieces.flatMap { com.gravity.ide.core.SpeechText.chunks(it, 3500) }.filter { it.isNotBlank() }
        if (all.isEmpty()) {
            tts.playSilentUtterance(10, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, lastId)
            return
        }
        all.forEachIndexed { i, piece ->
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(piece, mode, null, if (i == all.lastIndex) lastId else "$idPrefix-$i")
            if (style.pauseMs > 0 && i < all.lastIndex) tts.playSilentUtterance(style.pauseMs.toLong(), TextToSpeech.QUEUE_ADD, "$idPrefix-p$i")
        }
    }

    /**
     * Speaks [text] phrase by phrase with its own speed, pitch, loudness and pause (HumanSpeech).
     * Android takes the speed and pitch set when each phrase is queued, so they are set before
     * each one and put back after.
     */
    private fun speakLikeAPerson(
        tts: TextToSpeech, text: String, style: VoiceStyles.Style, base: Base, idPrefix: String, lastId: String, flush: Boolean,
    ) {
        val plan = com.gravity.ide.core.HumanSpeech.plan(text, sentencePauseMs = maxOf(style.pauseMs, 260))
            .flatMap { p -> com.gravity.ide.core.SpeechText.chunks(p.text, 3500).map { p.copy(text = it) } }
            .filter { it.text.isNotBlank() }
        try {
            plan.forEachIndexed { i, p ->
                tts.setSpeechRate(base.rate * p.rate)
                tts.setPitch(base.pitch * p.pitch)
                val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.volume) }
                val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts.speak(p.text, mode, params, if (i == plan.lastIndex) lastId else "$idPrefix-$i")
                if (p.pauseAfterMs > 0 && i < plan.lastIndex) tts.playSilentUtterance(p.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$idPrefix-p$i")
            }
        } finally {
            tts.setSpeechRate(base.rate)
            tts.setPitch(base.pitch)
        }
    }

    /** A short sample line for previews, in the style's mood. */
    fun sample(style: VoiceStyles.Style, hindi: Boolean): String = when (style.id) {
        "romantic" -> if (hindi) "तुम्हारी बातें सुनकर दिल को बहुत सुकून मिलता है।" else "Every time I hear your voice, my day gets a little brighter."
        "sad" -> if (hindi) "कभी-कभी दिल बहुत भारी हो जाता है... पर मैं तुम्हारे साथ हूँ।" else "Some days feel heavy... but I am right here with you."
        "cheerful" -> if (hindi) "अरे वाह! आज तो मज़ा आ गया!" else "Oh wow, that is amazing news!"
        "calm" -> if (hindi) "गहरी साँस लो... सब ठीक हो जाएगा।" else "Take a deep breath... everything will be okay."
        "energetic" -> if (hindi) "चलो! आज हम कुछ बड़ा करके दिखाएँगे!" else "Come on! Today we are going to do something big!"
        "storyteller" -> if (hindi) "बहुत समय पहले की बात है... एक छोटे से गाँव में..." else "Long, long ago... in a small village..."
        "news" -> if (hindi) "नमस्कार, आज की मुख्य ख़बरें इस प्रकार हैं।" else "Good evening, here are today's top stories."
        "teacher" -> if (hindi) "चलो इसे आसान तरीके से समझते हैं।" else "Let us understand this in a simple way."
        "poet" -> if (hindi) "चाँद भी शरमा जाए, ऐसी तेरी बात है।" else "Even the moon would blush at the way you smile."
        "butler" -> if (hindi) "जी सर, काम हो गया। आगे क्या करना है?" else "Done, Sir. Shall I prepare the next one?"
        "companion" -> if (hindi) "अरे वाह, सच में? फिर क्या हुआ, बताओ ना!" else "Oh wow, really? Then what happened? Tell me!"
        "mission" -> if (hindi) "तीन काम पूरे, एक बाकी। अगला काम बताइए, बॉस।" else "Three tasks complete, one remaining. Ready for the next one, Boss."
        else -> if (hindi) "नमस्ते दोस्त! बताओ, आज क्या बात करें?" else "Hey friend! What shall we talk about today?"
    }
}
