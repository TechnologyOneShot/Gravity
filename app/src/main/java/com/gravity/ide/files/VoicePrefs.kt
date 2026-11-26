package com.gravity.ide.files

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.gravity.ide.core.EmotionClassifier
import com.gravity.ide.core.Expressions
import com.gravity.ide.core.HinglishReader
import com.gravity.ide.core.SpeechPlanner
import com.gravity.ide.core.SpeechText
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

    /**
     * Feelings in the voice: the mood of each reply (EmotionClassifier) sets its speed, pitch,
     * loudness and pauses. On by default; off: every reply in the same plain voice.
     */
    fun natural(context: Context): Boolean = prefs(context).getBoolean("natural", true)
    fun setNatural(context: Context, on: Boolean) = prefs(context).edit().putBoolean("natural", on).apply()

    /** Normal (everyday), Expressive (full feelings, breath sounds) or Professional (calm and clear). */
    fun mode(context: Context): Expressions.Mode =
        runCatching { Expressions.Mode.valueOf(prefs(context).getString("mode", null) ?: "NORMAL") }.getOrDefault(Expressions.Mode.NORMAL)
    fun setMode(context: Context, m: Expressions.Mode) = prefs(context).edit().putString("mode", m.name).apply()

    /**
     * Gemini's expressive online voice for reading replies (voice/OnlineVoice): off unless the user
     * switches it on; it sends the reply's text to Google with the user's own Gemini key.
     */
    fun online(context: Context): Boolean = prefs(context).getBoolean("online", false)
    fun setOnline(context: Context, on: Boolean) = prefs(context).edit().putBoolean("online", on).apply()

    /** The online voice's name: the chosen one if it matches Yamini's gender, else that gender's first. */
    fun onlineVoice(context: Context): String {
        val g = gender(context)
        val saved = prefs(context).getString("online_voice", null)
        return com.gravity.ide.voice.OnlineVoice.voices.firstOrNull { it.name == saved && it.gender == g }?.name
            ?: com.gravity.ide.voice.OnlineVoice.defaultVoice(g)
    }
    fun setOnlineVoice(context: Context, name: String) = prefs(context).edit().putString("online_voice", name).apply()

    /** The mood to read [text] in, by the same rules as the phone voice (off, fixed, or from the words). */
    fun moodFor(context: Context, text: String, userText: String? = null): EmotionClassifier.Result = when {
        !natural(context) -> EmotionClassifier.Result(EmotionClassifier.Emotion.NEUTRAL, 1f, 0f)
        fixedMood(context) != null -> EmotionClassifier.Result(fixedMood(context)!!, 1f, 0.8f)
        else -> EmotionClassifier.classify(text, userText)
    }

    /** A mood the user fixed for every reply (manual override), or null: found from each reply's words. */
    fun fixedMood(context: Context): EmotionClassifier.Emotion? =
        prefs(context).getString("fixed_mood", null)?.let { n -> EmotionClassifier.Emotion.entries.firstOrNull { it.name == n } }
    fun setFixedMood(context: Context, e: EmotionClassifier.Emotion?) =
        prefs(context).edit().apply { if (e == null) remove("fixed_mood") else putString("fixed_mood", e.name) }.apply()

    /** A soft breath sound before some longer sentences (Expressive mode only). On by default. */
    fun breath(context: Context): Boolean = prefs(context).getBoolean("breath", true)
    fun setBreath(context: Context, on: Boolean) = prefs(context).edit().putBoolean("breath", on).apply()

    /** Live mode says a short "हम्म…" when the answer takes a moment. On by default. */
    fun filler(context: Context): Boolean = prefs(context).getBoolean("filler", true)
    fun setFiller(context: Context, on: Boolean) = prefs(context).edit().putBoolean("filler", on).apply()

    /**
     * Yamini's voice: "female" or "male". It picks the voices and the AI's Hindi grammar for itself
     * (a woman says "कर सकती हूँ", a man "कर सकता हूँ"). Not set: the chosen Hindi voice's, else female.
     */
    fun gender(context: Context): String =
        prefs(context).getString("gender", null) ?: voiceName(context, true)?.let { VoiceStyles.gender(it) } ?: "female"
    fun male(context: Context): Boolean = gender(context) == "male"

    /** Sets the voice's gender; a saved voice of the other gender is forgotten, so both languages match. */
    fun setGender(context: Context, g: String) {
        val e = prefs(context).edit().putString("gender", g)
        for (key in listOf(HINDI, ENGLISH)) {
            val other = prefs(context).getString(key, null)?.let { VoiceStyles.gender(it) }
            if (other != null && other != g) e.remove(key)
        }
        e.apply()
    }

    /**
     * What [apply] set on each speaker: speed and pitch, the voice for each language (a reply can
     * switch between Hindi and English sentence by sentence), the Hinglish word list (a Hindi voice
     * reads Hinglish in Devanagari, see HinglishReader), and the mood so far (feelings carry on
     * from sentence to sentence instead of jumping).
     */
    private class Base(
        val rate: Float, val pitch: Float, val feelings: Boolean, val mode: Expressions.Mode, val breath: Boolean, val fixed: EmotionClassifier.Emotion?,
        val hindiHome: Boolean, val hindiVoice: Voice?, val englishVoice: Voice?,
        val hindiLocale: Locale, val englishLocale: Locale, val words: Map<String, String>,
    ) {
        @Volatile var mood: EmotionClassifier.Result? = null
        @Volatile var voiceNow: String? = null
    }
    private val bases = java.util.WeakHashMap<TextToSpeech, Base>()

    /** The earcon name of the breath sound (res/raw/yamini_breath.wav, made by tools/voice/make-breath.py). */
    private const val BREATH = "[yamini-breath]"

    /**
     * The text-to-speech engine for Hindi ([hindi]) or English speech: Yamini's offline voice
     * (this app's package, see voice/NeuralVoices) when its pack is downloaded and switched on,
     * else null for the phone's default engine (the default: it sounds best).
     */
    fun engine(context: Context, hindi: Boolean): String? =
        if (com.gravity.ide.voice.NeuralVoices.active(context, if (hindi) "hi" else "en") != null) context.packageName else null

    private val natural = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TextToSpeech, Boolean>())

    /** A TextToSpeech on [engine] (null = the phone's default). */
    fun create(context: Context, engine: String?, onInit: TextToSpeech.OnInitListener): TextToSpeech {
        val tts = if (engine != null) TextToSpeech(context.applicationContext, onInit, engine) else TextToSpeech(context.applicationContext, onInit)
        if (engine == context.packageName) synchronized(natural) { natural += tts }
        return tts
    }

    /** True for a TextToSpeech made on Yamini's offline voice. */
    fun isNatural(tts: TextToSpeech): Boolean = synchronized(natural) { tts in natural }

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

    /** Hindi, or Hinglish (Hindi in Roman letters): read with the Hindi voice. */
    fun isHindi(context: Context, text: String): Boolean =
        isHindi(text) || HinglishReader.forVoice(context, text).let { it.hindiWords * 2 >= it.latinWords && it.wasHinglish }

    /**
     * Sets language, voices, speed and pitch on [tts]; [style] overrides the saved one (e.g. a
     * preview), [voice] the voice for this language, [rate] the speed (e.g. video narration).
     */
    fun apply(
        context: Context, tts: TextToSpeech, hindi: Boolean, style: VoiceStyles.Style = style(context), voice: Voice? = null,
        rate: Float? = null,
    ): Int {
        val locale = locale(context, hindi)
        val result = tts.setLanguage(locale)
        val g = gender(context)
        val nat = isNatural(tts)
        // The offline voice has one voice per language (chosen in its own settings).
        val home = if (nat) null else voice ?: VoicePicker.choose(tts, locale, voiceName(context, hindi), g)
        val other = if (nat) null else VoicePicker.choose(tts, locale(context, !hindi), voiceName(context, !hindi), g)
        home?.let { tts.setVoice(it) }
        val base = Base(
            rate ?: VoiceStyles.rate(style, rate(context)), VoiceStyles.pitch(style, pitch(context)), natural(context), mode(context),
            breath(context), fixedMood(context), hindi, if (hindi) home else other, if (hindi) other else home, locale(context, true), locale(context, false),
            com.gravity.ide.core.HinglishReader.words(context),
        )
        base.voiceNow = home?.name ?: locale.toLanguageTag()
        // Feelings carry on from the last turn (live mode sets the voice again for each turn).
        base.mood = synchronized(bases) { bases[tts] }?.mood
        tts.setSpeechRate(base.rate)
        tts.setPitch(base.pitch)
        runCatching { tts.addEarcon(BREATH, context.packageName, com.gravity.ide.R.raw.yamini_breath) }
        synchronized(bases) { bases[tts] = base }
        return result
    }

    /** One piece of speech, ready for a TextToSpeech: its text, voice and delivery. */
    data class Spoken(
        val text: String, val hindi: Boolean, val voice: Voice?, val locale: Locale,
        val rate: Float, val pitch: Float, val volume: Float, val pauseAfterMs: Int, val breathBefore: Boolean,
    )

    /**
     * What [tts] will say for [said]: whole sentences (never cut in the middle), in the reply's mood
     * ([userText] is what the user said, which also sets the mood) and the mode, each sentence with
     * the Hindi or English voice, Hinglish in Devanagari for the Hindi voice.
     */
    fun plan(
        tts: TextToSpeech, said: String, style: VoiceStyles.Style, userText: String? = null, keepMood: Boolean = true,
        mood: EmotionClassifier.Emotion? = null,
    ): List<Spoken> {
        val base = synchronized(bases) { bases[tts] } ?: return SpeechText.chunks(said, 3500).filter { it.isNotBlank() }.map {
            Spoken(it, isHindi(it), null, locale(isHindi(it)), 1f, 1f, 1f, 0, false)
        }
        // The mood: one asked for (a preview), the user's fixed one, or found in the words.
        val forced = mood ?: base.fixed
        val felt = when {
            !base.feelings -> EmotionClassifier.Result(EmotionClassifier.Emotion.NEUTRAL, 1f, 0f)
            forced != null -> EmotionClassifier.Result(forced, 1f, 0.8f)
            else -> EmotionClassifier.continueFrom(base.mood, EmotionClassifier.classify(said, userText))
        }
        if (keepMood && base.feelings && mood == null) base.mood = felt
        val mode = if (base.feelings) base.mode else Expressions.Mode.NORMAL
        val utterances = SpeechPlanner.plan(said, felt, mode, base.hindiHome) { HinglishReader.read(it, base.words).wasHinglish }
        // A sentence goes to the other language's voice only if the phone has one (the offline
        // voice switches by language); else the reply's own voice reads it.
        val nat = isNatural(tts)
        val hasHindi = nat || base.hindiVoice != null
        val hasEnglish = nat || base.englishVoice != null
        return utterances.flatMap { u0 ->
            val u = when {
                u0.hindi == base.hindiHome -> u0
                u0.hindi && !hasHindi -> u0.copy(hindi = false)
                !u0.hindi && !hasEnglish -> u0.copy(hindi = true)
                else -> u0
            }
            val text = if (u.hindi) HinglishReader.read(u.text, base.words).text else u.text
            // The style's own pause (e.g. storyteller) is the least pause between sentences.
            val pause = if (u.pauseAfterMs > 0 || style.pauseMs > 0) maxOf(u.pauseAfterMs, style.pauseMs) else 0
            val pieces = SpeechText.chunks(text, 3500).filter { it.isNotBlank() }
            pieces.mapIndexed { i, piece ->
                Spoken(
                    piece, u.hindi, if (u.hindi) base.hindiVoice else base.englishVoice, if (u.hindi) base.hindiLocale else base.englishLocale,
                    base.rate * u.rate, base.pitch * u.pitch, u.volume, if (i == pieces.lastIndex) pause else 0,
                    u.breathBefore && i == 0 && base.breath && mode == Expressions.Mode.EXPRESSIVE,
                )
            }
        }.let { list -> if (list.isEmpty()) list else list.dropLast(1) + list.last().copy(pauseAfterMs = 0) }
    }

    /** Puts [s]'s voice on [tts] if another one is on (a switch between Hindi and English). */
    fun useVoice(tts: TextToSpeech, s: Spoken) {
        val base = synchronized(bases) { bases[tts] } ?: return
        val want = s.voice?.name ?: s.locale.toLanguageTag()
        if (base.voiceNow == want) return
        if (s.voice != null) tts.setVoice(s.voice) else tts.setLanguage(s.locale)
        base.voiceNow = want
    }

    /**
     * Queues [said] in the style and the reply's mood: whole sentences, Hindi and English each in
     * its own voice, with pauses (and in Expressive mode a soft breath) between them. The last piece
     * gets [lastId] (for "finished" callbacks). [flush] clears what was playing first.
     * [keepMood] false: a filler such as "हम्म…" does not change the mood of the conversation.
     */
    fun speak(
        tts: TextToSpeech, said: String, style: VoiceStyles.Style, idPrefix: String, lastId: String, flush: Boolean,
        userText: String? = null, keepMood: Boolean = true, mood: EmotionClassifier.Emotion? = null,
    ) {
        val plan = plan(tts, said, style, userText, keepMood, mood)
        if (plan.isEmpty()) {
            tts.playSilentUtterance(10, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, lastId)
            return
        }
        val base = synchronized(bases) { bases[tts] }
        try {
            plan.forEachIndexed { i, p ->
                val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (p.breathBefore) {
                    val b = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.6f) }
                    tts.playEarcon(BREATH, mode, b, "$idPrefix-b$i")
                }
                // Android reads speed, pitch and voice when a piece is queued: set before each one.
                useVoice(tts, p)
                tts.setSpeechRate(p.rate)
                tts.setPitch(p.pitch)
                val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, p.volume) }
                tts.speak(p.text, if (p.breathBefore) TextToSpeech.QUEUE_ADD else mode, params, if (i == plan.lastIndex) lastId else "$idPrefix-$i")
                if (p.pauseAfterMs > 0 && i < plan.lastIndex) tts.playSilentUtterance(p.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$idPrefix-p$i")
            }
        } finally {
            if (base != null) {
                tts.setSpeechRate(base.rate)
                tts.setPitch(base.pitch)
            }
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
