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
