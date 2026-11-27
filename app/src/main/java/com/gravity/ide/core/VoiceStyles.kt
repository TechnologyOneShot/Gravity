package com.gravity.ide.core

/**
 * Speaking styles for read-aloud and live mode (pure Kotlin, unit tested). A style is two things
 * that are real on a phone: how the AI talks (its words and mood, told to the AI) and how the
 * phone's voice delivers it (speed, pitch and pauses between sentences).
 */
object VoiceStyles {
    data class Style(
        val id: String,
        val title: String,
        val hindi: String,
        val rate: Float,
        val pitch: Float,
        /** Silence between sentences, in ms (0 = the voice's own pauses). */
        val pauseMs: Int,
        /** Told to the AI in live mode, so the words match the mood. */
        val persona: String,
    )

    val all: List<Style> = listOf(
        Style("friend", "Friendly", "दोस्त जैसा", 1.0f, 1.0f, 0,
            "Talk like a warm, caring close friend: natural, easy and cheerful."),
        // Original assistant personalities in the spirit of film and desktop AI assistants.
        Style("butler", "AI Butler", "स्मार्ट AI असिस्टेंट (सर / बॉस)", 1.0f, 0.92f, 160,
            "You are a highly capable personal AI butler, like the assistant of a genius inventor: calm, polite, precise and quietly " +
                "confident, with light dry wit. Address the user as {you}. Give the answer first in one crisp sentence, then offer the " +
                "next step (for example \"Shall I…?\"). Composed, never overexcited, never long-winded. In English, formal British " +
                "phrasing (\"Certainly, Sir.\", \"Right away.\", \"Might I suggest…\")."),
        Style("companion", "Companion", "दिल से बात करने वाला साथी", 1.04f, 1.04f, 60,
            "Talk like a warm, expressive companion in a real back-and-forth conversation: relaxed and natural, react first to what " +
                "the user said (\"Oh nice!\", \"Hmm, samjha\"), show real feeling, keep it short and often ask something back. " +
                "A light filler now and then is fine; no rambling."),
        Style("mission", "Mission Control", "मिशन कंट्रोल (फटाफट रिपोर्ट)", 1.08f, 0.98f, 100,
            "Talk like a sharp mission-control AI giving status reports: crisp, efficient and confident. Address the user as {you}. " +
                "Short sentences, key facts first, numbers when useful, then \"Ready for the next one.\""),
        Style("romantic", "Romantic", "रोमांटिक", 0.9f, 1.04f, 220,
            "Talk in a soft, tender, romantic way, like someone deeply in love: gentle, sweet and poetic, never vulgar."),
        Style("sad", "Sad / emotional", "उदास / भावुक", 0.84f, 0.92f, 320,
            "Talk in a gentle, emotional, slightly sad tone: slow, heartfelt and comforting, with feeling in every line."),
        Style("cheerful", "Cheerful", "खुशमिज़ाज", 1.1f, 1.1f, 0,
            "Talk in a happy, bright, playful way: excited, smiling and full of positivity."),
        Style("calm", "Calm", "शांत", 0.9f, 0.96f, 260,
            "Talk in a calm, soothing, peaceful way, like a meditation guide: slow, soft and reassuring."),
        Style("energetic", "Energetic", "जोशीला", 1.16f, 1.06f, 0,
            "Talk with high energy and motivation, like a coach: strong, confident and inspiring."),
        Style("storyteller", "Storyteller", "कहानी सुनाने वाला", 0.94f, 1.0f, 380,
            "Talk like a gripping storyteller: vivid, dramatic, with suspense and expressive pauses."),
        Style("news", "News anchor", "न्यूज़ एंकर", 1.06f, 0.98f, 120,
            "Talk like a clear, confident TV news anchor: crisp, formal and to the point."),
        Style("teacher", "Teacher", "टीचर", 0.95f, 1.0f, 200,
            "Talk like a patient, kind teacher: simple words, clear steps, encouraging."),
        Style("poet", "Shayar / poet", "शायर", 0.88f, 1.0f, 420,
            "Talk like a shayar: lyrical, with rhyme and rhythm, each line full of feeling."),
    )

    fun byId(id: String?): Style = all.firstOrNull { it.id == id } ?: all.first()

    /** How a style addresses the user when they have not said: the butler says Sir, mission control Boss. */
    fun defaultAddress(style: Style, hindi: Boolean): String? = when (style.id) {
        "butler" -> if (hindi) "सर" else "Sir"
        "mission" -> if (hindi) "बॉस" else "Boss"
        else -> null
    }

    /**
     * What the AI is told in live mode: the style's personality, how to address the user, and —
     * so Hindi sounds right — grammar that matches the voice (a woman's voice says "कर सकती हूँ").
     */
    fun personaFor(style: Style, address: String?, voiceGender: String?, hindi: Boolean): String = buildString {
        val name = address?.trim()?.takeIf { it.isNotEmpty() }
        val you = name ?: defaultAddress(style, hindi) ?: "the user"
        append(style.persona.replace("{you}", "\"$you\""))
        if (name != null && !style.persona.contains("{you}")) append(" Call the user \"$name\" now and then.")
        when (voiceGender) {
            "female" -> append(" Your voice is a woman's: in Hindi use feminine forms for yourself (मैं कर सकती हूँ, मैं सुन रही हूँ).")
            "male" -> append(" Your voice is a man's: in Hindi use masculine forms for yourself (मैं कर सकता हूँ, मैं सुन रहा हूँ).")
        }
    }

    /** The line live mode says when it opens (part of the app, not an AI answer). */
    fun greeting(style: Style, hindi: Boolean, address: String?, hour: Int, female: Boolean): String {
        val name = address?.trim()?.takeIf { it.isNotEmpty() } ?: defaultAddress(style, hindi)
        val listening = if (female) "सुन रही हूँ" else "सुन रहा हूँ"
        return when (style.id) {
            "butler" -> if (hindi) {
                "${hindiDaypart(hour)}, ${name ?: "सर"}। सब तैयार है। बताइए, क्या करना है?"
            } else {
                "${englishDaypart(hour)}, ${name ?: "Sir"}. All systems are ready. What shall we do?"
            }
            "mission" -> if (hindi) "${name ?: "बॉस"}, सिस्टम ऑनलाइन। अगला काम बताइए।" else "Systems online, ${name ?: "Boss"}. Ready for your next task."
            "companion" -> if (hindi) "हाय${name?.let { " $it" } ?: ""}! कैसा चल रहा है? मैं $listening।" else "Hey${name?.let { " $it" } ?: ""}! How's it going? I'm listening."
            else -> if (hindi) "हाँ${name?.let { " $it" } ?: ""}, बोलिए! मैं $listening।" else "Hi${name?.let { " $it" } ?: ""}! I'm listening."
        }
    }

    private fun englishDaypart(hour: Int) = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    private fun hindiDaypart(hour: Int) = when (hour) {
        in 5..11 -> "सुप्रभात"
        in 12..16 -> "नमस्कार"
        else -> "शुभ संध्या"
    }

    /** The style's speed and pitch on top of the user's own speed and pitch settings. */
    fun rate(style: Style, userRate: Float) = (style.rate * userRate).coerceIn(0.5f, 2.0f)
    fun pitch(style: Style, userPitch: Float) = (style.pitch * userPitch).coerceIn(0.5f, 2.0f)

    private val sentenceEnd = Regex("(?<=[.!?।|])\\s+|\\n+")

    /** Sentences to speak one by one, so a style can put pauses between them. */
    fun sentences(text: String): List<String> = text.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * "female", "male" or null for a text-to-speech voice name. Google's Android voices for India
     * (documented by the Readium speech project's voice lists): Hindi hia/hic female, hid/hie male;
     * English (India) ena/enc female, end/ene male. Samsung voices carry f/m (SMTf00, SMTm00).
     */
    fun gender(voiceName: String): String? {
        val n = voiceName.lowercase()
        Regex("-x-(hi[a-z]|en[a-z])-").find(n)?.groupValues?.get(1)?.let { code ->
            when (code) {
                "hia", "hic", "ena", "enc" -> return "female"
                "hid", "hie", "end", "ene" -> return "male"
            }
        }
        if ("female" in n || Regex("smtf\\d").containsMatchIn(n)) return "female"
        if (Regex("(^|[^e])male").containsMatchIn(n) || Regex("smtm\\d").containsMatchIn(n)) return "male"
        return null
    }
}
