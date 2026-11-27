package com.gravity.ide.core

/** Turns a chat reply into text to read aloud (pure Kotlin, unit tested). */
object SpeechText {
    private val codeBlock = Regex("```[\\s\\S]*?(```|$)")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val lineMarks = Regex("(?m)^\\s{0,3}(#{1,6}|[-*•>]|\\d+[.)])\\s+")
    private val emphasis = Regex("(\\*\\*|__|\\*|~~)")
    private val italicUnderscore = Regex("(?<![\\p{L}\\p{N}])_([^_\\n]+)_(?![\\p{L}\\p{N}])")
    private val spaces = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{2,}")

    /** Markdown, code, links and the "(generation stopped)" note removed; what remains reads naturally. */
    fun clean(text: String): String {
        // "*smiles*" → "(smiles)": stage directions are said as a manner, not read out (StageDirections).
        var t = StageDirections.asParens(text.replace("_(generation stopped)_", ""))
        t = codeBlock.replace(t, " (code not read aloud) ")
        t = inlineCode.replace(t, "$1")
        t = link.replace(t, "$1")
        t = url.replace(t, "")
        t = lineMarks.replace(t, "")
        t = emphasis.replace(t, "")
        t = italicUnderscore.replace(t, "$1")
        t = spaces.replace(t, " ")
        t = blankLines.replace(t, "\n")
        return t.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** Pieces of at most [max] characters, split after sentences where possible (text-to-speech has a length limit). */
    fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("। "), window.lastIndexOf("? "), window.lastIndexOf("! "), window.lastIndexOf('\n'))
                .let { if (it < max / 3) window.lastIndexOf(' ') else it + 1 }
                .let { if (it <= 0) max else it }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out.filter { it.isNotBlank() }
    }

    /**
     * While a reply is still being written: the end of the text from [from] that can be spoken
     * now (up to the last finished sentence; or, for a long sentence, up to a comma or space once
     * it passes [longAt] characters). Returns [from] when nothing is ready yet. Lets live mode
     * start talking after the first sentence instead of waiting for the whole reply.
     */
    /**
     * For the first piece of a spoken reply: where the voice can already start without waiting for
     * a long first sentence. A short exclamation counts ("Arre wah!"), and so does a clause end
     * (", ", " — ", ": ", "; ") after at least 2 words, e.g. "Arre yaar," of "Arre yaar, office ka
     * din lamba tha na?".
     */
    fun firstSpeakableEnd(text: String, from: Int): Int {
        val sentence = speakableEnd(text, from, minChars = 6)
        if (sentence > from) return sentence
        val window = text.substring(from)
        for (m in clauseEnd.findAll(window)) {
            val piece = window.substring(0, m.range.first + 1).trim()
            val words = piece.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } }
            if (words >= 2 && piece.length >= 8) return from + m.range.first + 1
        }
        return from
    }

    // A clause mark followed by a space (not "3,5" or "10:30").
    private val clauseEnd = Regex("[,;:،—](?=\\s)")

    fun speakableEnd(text: String, from: Int, minChars: Int = 12, longAt: Int = 140): Int {
        if (from >= text.length) return from
        var end = -1
        var i = from
        while (i < text.length) {
            val ch = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else null
            val endsSentence = ch == '\n' || ((ch == '.' || ch == '!' || ch == '?' || ch == '।' || ch == '|') && (next == null || next.isWhitespace()))
            // A sentence end at the very end of the text may still be followed by more (e.g. "3." of "3.5"): wait for the next character.
            if (endsSentence && next != null && i + 1 - from >= minChars) end = i + 1
            i++
        }
        if (end > from) return end
        if (text.length - from >= longAt) {
            val window = text.substring(from)
            val cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("، "), window.lastIndexOf(' '))
            if (cut > minChars) return from + cut + 1
        }
        return from
    }
}
