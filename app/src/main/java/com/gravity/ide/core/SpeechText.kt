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
        var t = text.replace("_(generation stopped)_", "")
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
}
