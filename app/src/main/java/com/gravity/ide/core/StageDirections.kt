package com.gravity.ide.core

/**
 * Stage directions a model writes into a reply, like "(A long pause, filled with unspoken sorrow)",
 * "(My voice is barely a whisper)", "*smiles*" or "(हँसते हुए)". They are not shown or read out
 * as words: the voice says the text after them in that manner instead (sad, whispering, laughing,
 * after a pause). Only bracketed or starred bits with a delivery word are taken; "(5 marks)" or
 * "(Theek hai!)" stay. Pure Kotlin, unit tested.
 */
object StageDirections {
    data class Segment(val text: String, val mood: EmotionClassifier.Emotion?, val pauseBeforeMs: Int, val laugh: Boolean)

    // A bracketed, starred or underscored bit of up to 140 characters.
    private val candidate = Regex("\\(([^()\\n]{2,140})\\)|\\[([^\\[\\]\\n]{2,140})]|(?<![*\\w])\\*([^*\\n]{2,140})\\*(?![*\\w])|(?<![_\\w])_([^_\\n]{2,140})_(?![_\\w])")

    // Words that say how something is said or done (English and Hindi).
    private val cue = Regex(
        "(?i)\\b(pause[sd]?|pausing|tone|voice|whisper\\w*|sigh\\w*|laugh\\w*|chuckl\\w*|giggl\\w*|smil\\w*|grin\\w*|softly|quietly|gently|" +
            "sadly|tear\\w*|cr(y|ies|ying)|sob\\w*|sorrow\\w*|mournful|struggl\\w*|hesitat\\w*|excited\\w*|cheerful\\w*|warmly|nervous\\w*|" +
            "blush\\w*|wink\\w*|silence|clears? (?:her|his)? ?throat|deep breath|softens|" +
            "playfully|teasing\\w*|seriously|firmly|angrily|calmly|shyly|happily|nervously)\\b|" +
            "हँस|हंस|मुस्कुरा|मुस्करा|धीरे से|रुककर|रुकते हुए|आह(?![\\p{L}\\p{M}])|उदास|आँसू|रोते|फुसफुसा|गहरी साँस|चुप हो",
    )

    private fun cueText(m: MatchResult): String? {
        val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return inner.takeIf { cue.containsMatchIn(it) }
    }

    /** The mood a direction asks for, or null when it is only a pause or an action. */
    fun moodOf(direction: String): EmotionClassifier.Emotion? {
        val d = direction.lowercase()
        return when {
            Regex("whisper|फुसफुसा|barely").containsMatchIn(d) -> EmotionClassifier.Emotion.WHISPER
            Regex("laugh|chuckl|giggl|हँस|हंस").containsMatchIn(d) -> EmotionClassifier.Emotion.LAUGHING
            Regex("sorrow|mournful|sad|tear|cry|cries|sob|उदास|आँसू|रोते").containsMatchIn(d) -> EmotionClassifier.Emotion.SAD
            Regex("excited|cheerful|happily|beam").containsMatchIn(d) -> EmotionClassifier.Emotion.EXCITED
            Regex("smil|grin|warmly|मुस्कुरा|मुस्करा").containsMatchIn(d) -> EmotionClassifier.Emotion.HAPPY
            Regex("wink|teas|playful").containsMatchIn(d) -> EmotionClassifier.Emotion.PLAYFUL
            Regex("serious|firm").containsMatchIn(d) -> EmotionClassifier.Emotion.SERIOUS
            Regex("angr").containsMatchIn(d) -> EmotionClassifier.Emotion.ANGRY
            Regex("nervous|hesitat|struggl|shy|blush").containsMatchIn(d) -> EmotionClassifier.Emotion.CONFUSED
            Regex("quiet|soft|gentl|calm|slow|deep breath|धीरे").containsMatchIn(d) -> EmotionClassifier.Emotion.CALM
            else -> null
        }
    }

    private fun pauseOf(direction: String): Int = when {
        Regex("(?i)long pause|silence|लंबी").containsMatchIn(direction) -> 900
        Regex("(?i)pause|struggl|hesitat|deep breath|sigh|रुक|आह").containsMatchIn(direction) -> 500
        else -> 0
    }

    /**
     * [text] with starred, underscored or square-bracketed directions written in round brackets
     * ("*smiles*" → "(smiles)"), so they survive the removal of Markdown before speaking.
     */
    fun asParens(text: String): String {
        if (!has(text)) return text
        return candidate.replace(text) { m -> cueText(m)?.let { "($it)" } ?: m.value }
    }

    /** True when [text] has stage directions. */
    fun has(text: String): Boolean = candidate.findAll(text).any { cueText(it) != null }

    /** [text] without its stage directions (and without lines left empty by them), for the screen. */
    fun strip(text: String): String {
        if (!has(text)) return text
        val lines = candidate.replace(text) { m -> if (cueText(m) != null) "" else m.value }.lines().map { it.trimEnd() }
        // Lines that only held a direction go; one blank line between paragraphs stays.
        return lines.filterIndexed { i, l -> l.isNotBlank() || (i > 0 && lines[i - 1].isNotBlank()) }
            .joinToString("\n").replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /**
     * [text] as pieces to say, each in the manner its direction asked (the mood stays until the next
     * direction), with the pause a direction asked for and a laugh where it says so.
     */
    fun segments(text: String): List<Segment> {
        val marks = candidate.findAll(text).mapNotNull { m -> cueText(m)?.let { m to it } }.toList()
        if (marks.isEmpty()) return listOf(Segment(text, null, 0, false))
        val out = ArrayList<Segment>()
        var mood: EmotionClassifier.Emotion? = null
        var pause = 0
        var laugh = false
        var last = 0
        for ((m, dir) in marks) {
            val piece = text.substring(last, m.range.first)
            if (piece.isNotBlank()) {
                out += Segment(piece.trim(), mood, pause, laugh)
                pause = 0
                laugh = false
            }
            moodOf(dir)?.let { mood = it }
            pause = maxOf(pause, pauseOf(dir))
            laugh = laugh || Regex("(?i)laugh|chuckl|giggl|हँस|हंस").containsMatchIn(dir)
            last = m.range.last + 1
        }
        text.substring(last).takeIf { it.isNotBlank() }?.let { out += Segment(it.trim(), mood, pause, laugh) }
        return out
    }
}
