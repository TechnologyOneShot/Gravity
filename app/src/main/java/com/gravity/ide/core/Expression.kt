package com.gravity.ide.core

/**
 * How Yamini's voice delivers a reply: the mood found in the words (EmotionClassifier), what that
 * mood changes in the voice (Expressions), and the plan of what to say (SpeechPlanner). Pure Kotlin,
 * unit tested.
 *
 * Honest limits: a phone's text-to-speech voice only lets an app set speed, pitch and loudness per
 * sentence, and add silence or a short sound between sentences. So a mood is delivered with those
 * (slower and softer when sad, quicker and brighter when happy, slow and low for serious topics),
 * pauses, a soft breath sound between longer sentences (Expressive), and a spoken "हा हा" where
 * the reply itself laughs. Real laughter, crying or a true whisper are not something these voices
 * can make; [Expressions.Profile.capabilities] says what each mood really uses.
 */
object EmotionClassifier {
    enum class Emotion(val label: String, val hindi: String) {
        NEUTRAL("Neutral", "सामान्य"),
        HAPPY("Happy", "खुश"),
        EXCITED("Excited", "उत्साहित"),
        SAD("Sad", "उदास"),
        EMPATHETIC("Empathetic", "सहानुभूति"),
        CARING("Caring", "अपनापन"),
        APOLOGETIC("Apologetic", "माफ़ी"),
        SERIOUS("Serious", "गंभीर"),
        CALM("Calm", "शांत"),
        THINKING("Thinking", "सोचते हुए"),
        SURPRISED("Surprised", "हैरान"),
        CURIOUS("Curious", "जिज्ञासु"),
        CONFUSED("Confused", "उलझन"),
        FRIENDLY("Friendly", "दोस्ताना"),
        PROFESSIONAL("Professional", "प्रोफेशनल"),
        CONFIDENT("Confident", "आत्मविश्वास"),
        PLAYFUL("Playful", "मज़ाकिया"),
        SARCASTIC("Sarcastic", "हल्का व्यंग्य"),
        LAUGHING("Laughing", "हँसते हुए"),
        WHISPER("Whisper", "फुसफुसाहट"),
        SLEEPY("Sleepy", "थकी हुई"),
        ANGRY("Angry", "गुस्सा"),
    }

    data class Result(val emotion: Emotion, val confidence: Float, val intensity: Float)

    // Words and signs of each mood, in English, Hinglish and Hindi (lower case; Devanagari as is).
    private val cues: Map<Emotion, List<String>> = mapOf(
        Emotion.LAUGHING to listOf("😂", "🤣", "😆", "हाहा", "हा हा"),
        Emotion.EXCITED to listOf("wow", "amazing", "awesome", "fantastic", "incredible", "yay", "woohoo", "kamaal", "कमाल", "zabardast", "ज़बरदस्त", "जबरदस्त", "🎉", "🤩", "🔥", "शानदार", "shandaar"),
        Emotion.HAPPY to listOf("great", "glad", "happy", "congrat", "badhai", "बधाई", "mubarak", "मुबारक", "khushi", "ख़ुशी", "खुशी", "khush", "खुश", "wah", "वाह", "mazaa", "maza", "मज़ा", "मजा", "badhiya", "बढ़िया", "😊", "😄", "😃", "🙂", "🥳"),
        Emotion.SAD to listOf("sad", "unfortunately", "dukh", "दुख", "दुःख", "udaas", "उदास", "afsos", "अफ़सोस", "अफसोस", "dard", "दर्द", "rona", "रोना", "आँसू", "aansu", "heartbroken", "passed away", "गुज़र", "😢", "😭", "💔", "😞"),
        Emotion.EMPATHETIC to listOf("i understand", "i'm here", "i am here", "samajh sakti", "samajh sakta", "समझ सकती", "समझ सकता", "main tumhare saath", "मैं तुम्हारे साथ", "मैं आपके साथ", "you are not alone", "akele nahi", "अकेले नहीं", "that must be", "बहुत मुश्किल"),
        Emotion.CARING to listOf("take care", "khayal", "ख्याल", "ख़याल", "dhyan rakhna", "ध्यान रखना", "ध्यान रखें", "aaram karo", "आराम करो", "आराम करें", "don't worry", "chinta mat", "चिंता मत", "fikr", "फ़िक्र", "❤", "🤗", "pyaar", "प्यार"),
        Emotion.APOLOGETIC to listOf("sorry", "apologize", "apologies", "maaf", "माफ़", "माफ", "क्षमा", "galti", "ग़लती", "गलती", "my mistake"),
        Emotion.SERIOUS to listOf("doctor", "डॉक्टर", "hospital", "अस्पताल", "emergency", "आपातकाल", "danger", "ख़तरा", "खतरा", "warning", "चेतावनी", "important", "ज़रूरी", "जरूरी", "savdhaan", "सावधान", "risk", "police", "पुलिस", "court", "legal", "death", "मृत्यु", "suicide", "serious", "गंभीर", "health", "स्वास्थ्य", "loan", "कर्ज़"),
        Emotion.CALM to listOf("relax", "breathe", "deep breath", "saans", "साँस", "सांस", "shaant", "शांत", "dheere", "धीरे", "sukoon", "सुकून", "peace"),
        Emotion.THINKING to listOf("hmm", "हम्म", "let me think", "soch", "सोच", "ek minute", "एक मिनट", "one moment", "let me check", "dekhti hoon", "देखती हूँ", "देखता हूँ"),
        Emotion.SURPRISED to listOf("really?", "seriously?", "sach me", "sach mein", "सच में", "kya baat", "क्या बात", "😮", "😲", "no way", "अरे!", "arre!"),
        Emotion.CONFUSED to listOf("not sure", "confused", "samajh nahi", "समझ नहीं", "pakka nahi", "पक्का नहीं", "unclear", "🤔"),
        Emotion.FRIENDLY to listOf("hello", "hey", "namaste", "नमस्ते", "dost", "दोस्त", "yaar", "यार", "welcome", "स्वागत"),
        Emotion.CONFIDENT to listOf("definitely", "certainly", "of course", "absolutely", "zaroor", "ज़रूर", "जरूर", "bilkul", "बिल्कुल", "pakka", "पक्का", "no problem", "ho jayega", "हो जाएगा", "😎"),
        Emotion.PLAYFUL to listOf("😜", "😉", "😛", "masti", "मस्ती", "mazak", "मज़ाक", "मजाक", "just kidding", "naughty", "शरारत"),
        Emotion.SARCASTIC to listOf("😏", "🙄", "oh great", "yeah right", "how nice", "wah ji wah", "वाह जी वाह"),
        Emotion.WHISPER to listOf("🤫", "psst", "secret", "राज़", "chupke", "चुपके", "dheere se", "धीरे से", "kisi ko mat batana", "किसी को मत बताना"),
        Emotion.SLEEPY to listOf("good night", "शुभ रात्रि", "so jao", "सो जाओ", "neend", "नींद", "thak", "थक", "tired", "🥱", "😴", "sleepy"),
        Emotion.ANGRY to listOf("angry", "gussa", "गुस्सा", "😠", "😡", "furious", "bardasht nahi", "बर्दाश्त नहीं"),
    )

    // A user who is sad, angry or worried gets a gentler voice even when the reply has no such words.
    private val userCues: Map<Emotion, Emotion> = mapOf(
        Emotion.SAD to Emotion.EMPATHETIC,
        Emotion.ANGRY to Emotion.CALM,
        Emotion.SERIOUS to Emotion.SERIOUS,
        Emotion.LAUGHING to Emotion.HAPPY,
        Emotion.EXCITED to Emotion.HAPPY,
        Emotion.HAPPY to Emotion.HAPPY,
        Emotion.SLEEPY to Emotion.CALM,
    )

    private class Cue(val emotion: Emotion, val word: String, val regex: Regex?)

    // Latin cues match whole words ("lol" not inside "lollipop"); Devanagari and emoji as they are.
    private val compiled: List<Cue> = cues.flatMap { (e, words) ->
        words.map { w ->
            val latin = w.first().code < 128 && w.first().isLetter()
            Cue(e, w, if (latin) Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])") else null)
        }
    }

    // "haha", "hahaha", "hehe", "lol" in any length.
    private val laughWord = Regex("(?i)(?<![a-z])(?:ha){2,}h?(?![a-z])|(?<![a-z])(?:he){2,}(?![a-z])|(?<![a-z])lol(?![a-z])")

    private fun count(t: String, c: Cue): Int {
        c.regex?.let { return it.findAll(t).count().coerceAtMost(3) }
        var n = 0
        var i = t.indexOf(c.word)
        while (i >= 0 && n < 3) { n++; i = t.indexOf(c.word, i + c.word.length) }
        return n
    }

    private fun hits(text: String): Map<Emotion, Int> {
        val t = text.lowercase()
        val out = HashMap<Emotion, Int>()
        for (c in compiled) {
            val n = count(t, c)
            if (n > 0) out[c.emotion] = (out[c.emotion] ?: 0) + n
        }
        val laughs = laughWord.findAll(t).count().coerceAtMost(3)
        if (laughs > 0) out[Emotion.LAUGHING] = (out[Emotion.LAUGHING] ?: 0) + laughs
        return out
    }

    /**
     * The mood to speak [reply] in, from its words and signs and the user's message [context].
     * Neutral unless the words clearly say otherwise (no drama in ordinary answers).
     */
    fun classify(reply: String, context: String? = null): Result {
        val score = HashMap<Emotion, Float>()
        hits(reply).forEach { (e, n) -> score[e] = (score[e] ?: 0f) + n }
        context?.let { c -> hits(c).forEach { (e, n) -> userCues[e]?.let { m -> score[m] = (score[m] ?: 0f) + 1f * n } } }
        // Signs in the writing itself.
        val exclaims = reply.count { it == '!' }
        if (exclaims >= 2) score[Emotion.EXCITED] = (score[Emotion.EXCITED] ?: 0f) + 0.5f * minOf(exclaims, 4)
        val questions = reply.count { it == '?' }
        if (questions >= 2 && reply.length < 400) score[Emotion.CURIOUS] = (score[Emotion.CURIOUS] ?: 0f) + 0.6f * minOf(questions, 3)
        if (reply.contains("```") || Regex("(?m)^\\s*(\\d+\\.|step \\d)", RegexOption.IGNORE_CASE).findAll(reply).count() >= 3) {
            score[Emotion.PROFESSIONAL] = (score[Emotion.PROFESSIONAL] ?: 0f) + 1.5f
        }
        // Laughter in the words is laughter: it wins over plain happiness.
        if ((score[Emotion.LAUGHING] ?: 0f) > 0f) score[Emotion.LAUGHING] = score.getValue(Emotion.LAUGHING) + 1f
        // Ties go to the mood listed first (deterministic).
        val best = score.entries.maxWithOrNull(compareBy<Map.Entry<Emotion, Float>> { it.value }.thenByDescending { it.key.ordinal })
            ?: return Result(Emotion.NEUTRAL, 1f, 0f)
        if (best.value < 1f) return Result(Emotion.NEUTRAL, 1f - best.value, 0f)
        val total = score.values.sum()
        val confidence = (best.value / (total + 0.5f)).coerceIn(0f, 1f)
        val intensity = (best.value / 4f).coerceIn(0.35f, 1f)
        return Result(best.key, confidence, intensity)
    }

    /**
     * Keeps the mood steady through a reply and a conversation: a new mood replaces [previous] only
     * when it is clearly there (emotional continuity, no jumping between moods).
     */
    fun continueFrom(previous: Result?, next: Result): Result = when {
        previous == null -> next
        next.emotion == previous.emotion -> if (next.intensity > previous.intensity) next else previous
        next.emotion == Emotion.NEUTRAL -> previous.copy(intensity = previous.intensity * 0.85f)
        next.confidence >= 0.5f -> next
        else -> previous
    }
}

object Expressions {
    /** How strongly moods show: everyday talk, full expression, or calm and clear for work. */
    enum class Mode(val label: String, val hindi: String, val strength: Float) {
        NORMAL("Normal", "सामान्य: रोज़ की बातचीत", 0.55f),
        EXPRESSIVE("Expressive", "भावपूर्ण: भावनाएँ, साँस, हँसी", 1.0f),
        PROFESSIONAL("Professional", "प्रोफेशनल: साफ़ और शांत", 0.2f),
    }

    /** What a mood really changes in a phone voice. */
    enum class Capability { SPEED, PITCH, LOUDNESS, PAUSES, EMPHASIS, BREATH_SOUND, SPOKEN_LAUGH }

    data class Profile(
        val emotion: EmotionClassifier.Emotion,
        /** Multipliers on the user's speed and pitch; loudness 0..1. */
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        /** Silence after a sentence and after a comma-like break, in ms. */
        val sentencePauseMs: Int,
        val clausePauseMs: Int,
        /** How much the first sentence (or the one with "!") stands out, 0..1. */
        val emphasis: Float,
        val capabilities: Set<Capability>,
        /** What this mood cannot do with a phone voice (shown in settings), or "". */
        val limit: String = "",
    )

    private val base = setOf(Capability.SPEED, Capability.PITCH, Capability.LOUDNESS, Capability.PAUSES)

    /** The full-strength delivery of each mood (Expressive mode, intensity 1). */
    private val full: Map<EmotionClassifier.Emotion, Profile> = EmotionClassifier.Emotion.entries.associateWith { e ->
        when (e) {
            EmotionClassifier.Emotion.NEUTRAL -> Profile(e, 1.0f, 1.0f, 1.0f, 0, 0, 0f, base)
            EmotionClassifier.Emotion.HAPPY -> Profile(e, 1.08f, 1.08f, 1.0f, 220, 0, 0.3f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.EXCITED -> Profile(e, 1.15f, 1.14f, 1.0f, 180, 0, 0.5f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.SAD -> Profile(e, 0.84f, 0.9f, 0.8f, 520, 160, 0f, base, "No real crying: slower, lower and softer")
            EmotionClassifier.Emotion.EMPATHETIC -> Profile(e, 0.88f, 0.95f, 0.86f, 450, 120, 0.1f, base)
            EmotionClassifier.Emotion.CARING -> Profile(e, 0.9f, 1.02f, 0.88f, 400, 100, 0.1f, base)
            EmotionClassifier.Emotion.APOLOGETIC -> Profile(e, 0.92f, 0.96f, 0.86f, 380, 80, 0.1f, base)
            EmotionClassifier.Emotion.SERIOUS -> Profile(e, 0.86f, 0.93f, 1.0f, 480, 140, 0.2f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.CALM -> Profile(e, 0.86f, 0.96f, 0.85f, 520, 150, 0f, base)
            EmotionClassifier.Emotion.THINKING -> Profile(e, 0.9f, 0.98f, 0.92f, 450, 220, 0f, base)
            EmotionClassifier.Emotion.SURPRISED -> Profile(e, 1.1f, 1.16f, 1.0f, 260, 0, 0.5f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.CURIOUS -> Profile(e, 1.03f, 1.06f, 0.96f, 300, 60, 0.2f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.CONFUSED -> Profile(e, 0.93f, 1.03f, 0.92f, 420, 180, 0f, base)
            EmotionClassifier.Emotion.FRIENDLY -> Profile(e, 1.04f, 1.05f, 1.0f, 260, 0, 0.2f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.PROFESSIONAL -> Profile(e, 1.0f, 0.98f, 1.0f, 320, 60, 0f, base)
            EmotionClassifier.Emotion.CONFIDENT -> Profile(e, 1.02f, 0.97f, 1.0f, 280, 0, 0.3f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.PLAYFUL -> Profile(e, 1.1f, 1.12f, 1.0f, 220, 0, 0.4f, base + Capability.EMPHASIS)
            EmotionClassifier.Emotion.SARCASTIC -> Profile(e, 0.94f, 0.96f, 0.95f, 420, 160, 0.4f, base + Capability.EMPHASIS, "Only slower, flatter delivery with pauses")
            EmotionClassifier.Emotion.LAUGHING -> Profile(e, 1.12f, 1.15f, 1.0f, 220, 0, 0.4f, base + Capability.SPOKEN_LAUGH, "Laughs only where the reply says \"haha\": spoken \"हा हा\", not a recorded laugh")
            EmotionClassifier.Emotion.WHISPER -> Profile(e, 0.85f, 0.95f, 0.45f, 450, 120, 0f, base, "Not a true whisper: very soft and slow")
            EmotionClassifier.Emotion.SLEEPY -> Profile(e, 0.8f, 0.9f, 0.75f, 600, 200, 0f, base, "Tired voice: slow, low and soft")
            EmotionClassifier.Emotion.ANGRY -> Profile(e, 1.05f, 0.94f, 1.0f, 260, 0, 0.5f, base + Capability.EMPHASIS, "Firm, not shouting")
        }
    }

    fun full(e: EmotionClassifier.Emotion): Profile = full.getValue(e)

    /** A line to hear a mood with (Settings → Voice); no gendered verbs, so it fits either voice. */
    fun sample(e: EmotionClassifier.Emotion, hindi: Boolean): String = when (e) {
        EmotionClassifier.Emotion.NEUTRAL -> if (hindi) "यह रहा आपका जवाब। Let me know अगर और कुछ चाहिए।" else "Here is your answer. Let me know if you need anything else."
        EmotionClassifier.Emotion.HAPPY -> if (hindi) "अरे वाह, यह तो बहुत अच्छी ख़बर है!" else "Oh, that's really good news!"
        EmotionClassifier.Emotion.EXCITED -> if (hindi) "वाह! कमाल हो गया, that's amazing!" else "Wow! That's absolutely amazing!"
        EmotionClassifier.Emotion.SAD -> if (hindi) "यह सुनकर सच में बहुत दुख हुआ... बहुत मुश्किल वक़्त है।" else "I'm really sorry... that must have hurt a lot."
        EmotionClassifier.Emotion.EMPATHETIC -> if (hindi) "आप अकेले नहीं हैं। जो भी है, हम मिलकर देखेंगे।" else "You're not alone. Whatever it is, we'll figure it out together."
        EmotionClassifier.Emotion.CARING -> if (hindi) "अपना ध्यान रखना, और थोड़ा आराम कर लो।" else "Please take care of yourself, and get some rest."
        EmotionClassifier.Emotion.APOLOGETIC -> if (hindi) "माफ़ कीजिए, वह मेरी ग़लती थी।" else "I'm sorry, that was my mistake."
        EmotionClassifier.Emotion.SERIOUS -> if (hindi) "यह ज़रूरी है: सीने में दर्द हो तो तुरंत डॉक्टर को दिखाइए।" else "This is important: for chest pain, see a doctor right away."
        EmotionClassifier.Emotion.CALM -> if (hindi) "गहरी साँस लो... सब ठीक हो जाएगा।" else "Take a deep breath... everything will be okay."
        EmotionClassifier.Emotion.THINKING -> if (hindi) "हम्म... एक मिनट, let me think." else "Hmm... one moment, let me think."
        EmotionClassifier.Emotion.SURPRISED -> if (hindi) "अरे! सच में? यह तो पता ही नहीं था!" else "Oh! Really? I had no idea!"
        EmotionClassifier.Emotion.CURIOUS -> if (hindi) "अच्छा? फिर क्या हुआ? और बताओ ना!" else "Oh? Then what happened? Tell me more!"
        EmotionClassifier.Emotion.CONFUSED -> if (hindi) "यह ठीक से समझ नहीं आया... फिर से बताओगे?" else "I didn't quite get that... could you say it again?"
        EmotionClassifier.Emotion.FRIENDLY -> if (hindi) "Hello, कैसे हो? आज क्या प्लान है?" else "Hey, how are you? What's the plan today?"
        EmotionClassifier.Emotion.PROFESSIONAL -> if (hindi) "रिपोर्ट तैयार है। इसमें तीन मुख्य बातें हैं।" else "The report is ready. There are three key points."
        EmotionClassifier.Emotion.CONFIDENT -> if (hindi) "बिल्कुल, यह हो जाएगा। चिंता मत करो।" else "Absolutely, this will get done. Don't worry."
        EmotionClassifier.Emotion.PLAYFUL -> if (hindi) "अच्छा जी, तो आज मस्ती का मूड है?" else "Oh ho, so it's that kind of day, huh?"
        EmotionClassifier.Emotion.SARCASTIC -> if (hindi) "वाह जी वाह... क्या टाइमिंग है।" else "Oh great... perfect timing, as always."
        EmotionClassifier.Emotion.LAUGHING -> if (hindi) "हाहा, यह तो बहुत मज़ेदार था!" else "Haha, that was really funny!"
        EmotionClassifier.Emotion.WHISPER -> if (hindi) "धीरे से सुनो... यह एक राज़ की बात है।" else "Listen closely... this is a little secret."
        EmotionClassifier.Emotion.SLEEPY -> if (hindi) "बहुत रात हो गई... चलो, अब सो जाते हैं।" else "It's getting late... let's get some sleep."
        EmotionClassifier.Emotion.ANGRY -> if (hindi) "नहीं, यह बिल्कुल ठीक नहीं है।" else "No, that is not okay at all."
    }

    /** The delivery for a mood at [intensity] (0..1) in [mode]: the change from neutral is scaled. */
    fun profile(e: EmotionClassifier.Emotion, intensity: Float, mode: Mode): Profile {
        val f = full(e)
        val n = full(EmotionClassifier.Emotion.NEUTRAL)
        val k = (mode.strength * (0.5f + 0.5f * intensity.coerceIn(0f, 1f))).coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * k
        fun mixI(a: Int, b: Int) = (a + (b - a) * k).toInt()
        val caps = if (mode == Mode.EXPRESSIVE) f.capabilities + Capability.BREATH_SOUND else f.capabilities
        return f.copy(
            rate = mix(n.rate, f.rate), pitch = mix(n.pitch, f.pitch), volume = mix(n.volume, f.volume),
            sentencePauseMs = mixI(n.sentencePauseMs, f.sentencePauseMs), clausePauseMs = mixI(n.clausePauseMs, f.clausePauseMs),
            emphasis = f.emphasis * k, capabilities = caps,
        )
    }
}

/**
 * Turns a reply into what the voice says: whole sentences (a voice's own intonation is kept: cutting
 * sentences into small pieces made earlier versions sound choppy), each with speed, pitch and
 * loudness from the mood, its language (Hindi or English voice), the pause after it, and where a
 * breath sound goes. Laughter in the text ("haha", 😂) is spoken as "हा हा"; other emoji are not read.
 */
object SpeechPlanner {
    data class Utterance(
        val text: String,
        /** True: the Hindi voice (Devanagari, or Hinglish); false: the English voice. */
        val hindi: Boolean,
        val rate: Float,
        val pitch: Float,
        val volume: Float,
        val pauseAfterMs: Int,
        /** A soft breath sound before this sentence (Expressive mode only). */
        val breathBefore: Boolean,
        val laugh: Boolean = false,
    )

    private val sentenceEnd = Regex("(?<=[.!?।|…])\\s+|\\n+")
    private val laughWord = Regex("(?i)\\b(?:ha){2,}h?\\b|\\b(?:he){2,}\\b|\\blol\\b|हा\\s?हा(?:\\s?हा)*")
    private val laughEmoji = Regex("[😂🤣😆]")
    // Emoji and pictographs are not read aloud (a voice would say their names).
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]")

    /** True when [s] should be read by the Hindi voice: any Devanagari, or Hinglish (see [hinglish]). */
    fun isHindiSentence(s: String, hinglish: (String) -> Boolean): Boolean {
        val letters = s.count { it.isLetter() }
        if (letters == 0) return true
        val deva = s.count { it in 'ऀ'..'ॿ' }
        if (deva * 5 >= letters) return true
        return deva == 0 && hinglish(s)
    }

    /** The sentences of [text], each with whether a line break followed it (kept when sentences are joined again). */
    fun sentences(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var start = 0
        for (m in sentenceEnd.findAll(text)) {
            val s = text.substring(start, m.range.first).trim()
            if (s.isNotEmpty()) out += s to m.value.contains('\n')
            start = m.range.last + 1
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += it to false }
        return out
    }

    /**
     * The plan for [text] spoken in [mood] and [mode]. [hindiDefault] is the voice for sentences
     * with no letters; [hinglish] tells whether a Roman-script sentence is Hindi. Sentences that
     * sound the same are joined again, so an ordinary reply in Normal mode is read in one go, as
     * the phone's voice reads it best.
     */
    fun plan(
        text: String, mood: EmotionClassifier.Result, mode: Expressions.Mode, hindiDefault: Boolean,
        hinglish: (String) -> Boolean = { false },
    ): List<Utterance> {
        val reply = Expressions.profile(mood.emotion, mood.intensity, mode)
        val expressive = mode == Expressions.Mode.EXPRESSIVE
        val k = mode.strength
        val all = sentences(text)
        val out = ArrayList<Pair<Utterance, Boolean>>()
        var sinceBreath = 0
        // A laughing emoji is a laugh only where the words do not laugh already ("Haha! 😂" laughs once).
        val wordsLaugh = laughWord.containsMatchIn(text)
        all.forEachIndexed { i, (raw, lineBreak) ->
            val laughs = laughWord.containsMatchIn(raw) || (!wordsLaugh && laughEmoji.containsMatchIn(raw))
            val hindi = if (raw.none { it.isLetter() }) hindiDefault else isHindiSentence(laughWord.replace(raw, " "), hinglish)
            var s = laughEmoji.replace(raw, if (wordsLaugh) " " else if (hindi) " हा हा " else " ha ha ")
            s = laughWord.replace(s) { if (hindi) "हा हा" else "ha ha" }
            s = emoji.replace(s, "").replace(Regex("\\s{2,}"), " ").trim()
            if (s.none { it.isLetterOrDigit() }) return@forEachIndexed
            // Each sentence keeps the reply's mood. In Expressive mode it also gets a little of its
            // own: an exclamation is brighter, a trailing "..." slower, the first line stands out.
            // (A question already rises in the phone's voice.) A laugh is quick and high.
            var rate = reply.rate
            var pitch = reply.pitch
            val trailing = s.endsWith("...") || s.endsWith("…")
            when {
                laughs && mode != Expressions.Mode.PROFESSIONAL -> { rate *= 1 + 0.08f * k; pitch *= 1 + 0.1f * k }
                expressive && s.endsWith("!") -> { pitch *= 1.05f; rate *= 1.03f }
                expressive && trailing -> rate *= 0.95f
            }
            if (expressive && out.isEmpty() && reply.emphasis > 0f) pitch *= 1 + 0.04f * reply.emphasis
            var pause = reply.sentencePauseMs
            if (expressive && trailing) pause += 250
            if (expressive && s.endsWith("?")) pause += 80
            when (mode) {
                // Normal: no extra silence for small differences (the voice's own pause is natural).
                Expressions.Mode.NORMAL -> if (pause < 150) pause = 0
                // Professional: clear, even pacing.
                Expressions.Mode.PROFESSIONAL -> { pause = maxOf(pause, 160); rate *= 0.97f }
                Expressions.Mode.EXPRESSIVE -> Unit
            }
            // A breath now and then before a longer sentence (Expressive), as people do.
            val words = s.split(' ').size
            val breath = Expressions.Capability.BREATH_SOUND in reply.capabilities && out.isNotEmpty() && sinceBreath >= 1 && (words >= 10 || sinceBreath >= 3)
            sinceBreath = if (breath) 0 else sinceBreath + 1
            out += Utterance(
                s, hindi, rate.coerceIn(0.6f, 1.6f), pitch.coerceIn(0.7f, 1.4f), reply.volume.coerceIn(0.3f, 1f), pause, breath, laughs,
            ) to lineBreak
        }
        // Join neighbours that sound the same (same voice, speed, pitch and loudness, no pause between).
        val joined = ArrayList<Utterance>()
        var joinLine = false
        for ((u, lineBreak) in out) {
            val last = joined.lastOrNull()
            if (last != null && last.pauseAfterMs == 0 && !u.breathBefore && last.hindi == u.hindi && !last.laugh && !u.laugh &&
                kotlin.math.abs(last.rate - u.rate) < 0.005f && kotlin.math.abs(last.pitch - u.pitch) < 0.005f && last.volume == u.volume
            ) {
                joined[joined.lastIndex] = last.copy(text = last.text + (if (joinLine) "\n" else " ") + u.text, pauseAfterMs = u.pauseAfterMs)
            } else {
                joined += u
            }
            joinLine = lineBreak
        }
        if (joined.isNotEmpty()) joined[joined.lastIndex] = joined.last().copy(pauseAfterMs = 0)
        return joined
    }

    /** A short "I am listening" sound to say while the answer is being written (live mode). */
    fun listeningFiller(hindi: Boolean, seed: Int): String {
        val options = if (hindi) listOf("हम्म…", "अच्छा…", "हम्म, एक सेकंड…", "जी…", "ठीक है…") else listOf("Hmm…", "Okay…", "Hmm, one second…", "Right…", "Let me see…")
        return options[Math.floorMod(seed, options.size)]
    }
}
