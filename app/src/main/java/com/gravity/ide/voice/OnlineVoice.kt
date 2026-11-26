package com.gravity.ide.voice

import com.gravity.ide.core.EmotionClassifier
import com.gravity.ide.core.Expressions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini's expressive online voice for reading replies aloud: opt-in (Settings → Voice), with the
 * user's own Gemini key, and only while switched on: the reply's text is then sent to Google to be
 * spoken. Its feelings are real model features (a style and laugh/sigh tags), not speed tricks.
 * If there is no key, no internet, an error or no answer in time, the phone's voice reads the
 * reply instead (automatic fallback). The pure parts are here (unit tested); network and
 * playback are in [OnlineVoicePlayer].
 *
 * API (Gemini API text-to-speech, checked November 2026): generateContent with responseModalities
 * AUDIO and speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName. Gemini 3.8 TTS models take the
 * text as a verbatim transcript (vocal events as <laugh>, the delivery as a "style" in
 * speech_metadata) and answer WAV; gemini-3.1-flash-tts-preview takes [tags] in the text and
 * answers raw 24 kHz PCM. Both answers are read here.
 */
object OnlineVoice {
    data class Option(val name: String, val gender: String, val character: String)

    /** Gemini's prebuilt voices (a few of each gender). */
    val voices = listOf(
        Option("Kore", "female", "firm"),
        Option("Zephyr", "female", "bright"),
        Option("Aoede", "female", "breezy"),
        Option("Leda", "female", "youthful"),
        Option("Puck", "male", "upbeat"),
        Option("Charon", "male", "informative"),
        Option("Fenrir", "male", "excitable"),
        Option("Orus", "male", "firm"),
    )

    /** Tried in this order; the first that answers is remembered. */
    val models = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview")

    /** True for models that read the text word for word (3.8 and later). */
    fun verbatim(model: String) = !model.startsWith("gemini-2") && !model.startsWith("gemini-3.1")

    fun defaultVoice(gender: String) = voices.first { it.gender == gender }.name

    /** How the voice should sound, in words the model follows. "" = its own natural delivery. */
    fun style(mood: EmotionClassifier.Result, mode: Expressions.Mode): String {
        if (mode == Expressions.Mode.PROFESSIONAL) return "clear, calm and professional"
        val s = when (mood.emotion) {
            EmotionClassifier.Emotion.NEUTRAL -> ""
            EmotionClassifier.Emotion.HAPPY -> "warm and happy"
            EmotionClassifier.Emotion.EXCITED -> "excited"
            EmotionClassifier.Emotion.SAD -> "sad, soft and slow"
            EmotionClassifier.Emotion.EMPATHETIC -> "gentle and understanding"
            EmotionClassifier.Emotion.CARING -> "warm and caring"
            EmotionClassifier.Emotion.APOLOGETIC -> "apologetic"
            EmotionClassifier.Emotion.SERIOUS -> "serious, calm and a little slower"
            EmotionClassifier.Emotion.CALM -> "calm and soothing"
            EmotionClassifier.Emotion.THINKING -> "thoughtful"
            EmotionClassifier.Emotion.SURPRISED -> "surprised"
            EmotionClassifier.Emotion.CURIOUS -> "curious"
            EmotionClassifier.Emotion.CONFUSED -> "unsure and puzzled"
            EmotionClassifier.Emotion.FRIENDLY -> "friendly"
            EmotionClassifier.Emotion.PROFESSIONAL -> "clear and professional"
            EmotionClassifier.Emotion.CONFIDENT -> "confident"
            EmotionClassifier.Emotion.PLAYFUL -> "playful"
            EmotionClassifier.Emotion.SARCASTIC -> "lightly sarcastic"
            EmotionClassifier.Emotion.LAUGHING -> "amused"
            EmotionClassifier.Emotion.WHISPER -> "whispering"
            EmotionClassifier.Emotion.SLEEPY -> "sleepy and tired"
            EmotionClassifier.Emotion.ANGRY -> "firm and annoyed"
        }
        // Normal mode: a lighter touch of the mood.
        return if (s.isNotEmpty() && mode == Expressions.Mode.NORMAL) "slightly $s" else s
    }

    private val laughWord = Regex("(?i)(?<![a-z])(?:ha){2,}h?(?![a-z])|(?<![a-z])(?:he){2,}(?![a-z])|(?<![a-z])lol(?![a-z])|हा\\s?हा(?:\\s?हा)*")
    private val laughEmoji = Regex("[😂🤣😆]")
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    /** The text to send: laughter as the model's laugh tag, emoji left out (they are not words). */
    fun transcript(text: String, verbatim: Boolean): String {
        val laugh = if (verbatim) "<laugh>" else "[laughs]"
        // A laughing emoji is a laugh only where the words do not laugh already.
        val t = if (laughWord.containsMatchIn(text)) laughEmoji.replace(text, "") else laughEmoji.replace(text, " $laugh ")
        return emoji.replace(laughWord.replace(t, laugh), "").replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** The generateContent body. [withStyle] false: the style is left out (a model that refused it). */
    fun body(text: String, style: String, voice: String, model: String, withStyle: Boolean = true): JSONObject {
        val verbatim = verbatim(model)
        val words = transcript(text, verbatim)
        val part = JSONObject()
        if (verbatim) {
            part.put("text", words)
            if (withStyle && style.isNotEmpty()) part.put("speech_metadata", JSONObject().put("style", style))
        } else {
            part.put("text", if (withStyle && style.isNotEmpty()) "[$style] $words" else words)
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(part))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))),
            )
    }

    /** Mono 16-bit samples and their rate from a generateContent answer (WAV, or raw L16 PCM). */
    fun audioFrom(json: JSONObject): Pair<ShortArray, Int> {
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("The online voice sent no audio" + (json.optJSONObject("promptFeedback")?.let { " ($it)" } ?: ""))
        val out = java.io.ByteArrayOutputStream()
        var mime = ""
        for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            mime = data.optString("mimeType", mime)
            out.write(java.util.Base64.getDecoder().decode(data.optString("data")))
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) throw IllegalStateException("The online voice sent no audio")
        if (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return com.gravity.ide.core.WavJoin.pcmMono16(bytes)
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        val samples = ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xff) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
        return samples to rate
    }

    /** Pieces of about [max] characters, at sentence ends, so the first one starts playing soon. */
    fun pieces(text: String, first: Int = 280, max: Int = 1200): List<String> {
        val sentences = com.gravity.ide.core.SpeechPlanner.sentences(text).map { it.first }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            val limit = if (out.isEmpty()) first else max
            if (cur.isNotEmpty() && cur.length + s.length + 1 > limit) {
                out += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out.flatMap { com.gravity.ide.core.SpeechText.chunks(it, max * 2) }
    }
}
