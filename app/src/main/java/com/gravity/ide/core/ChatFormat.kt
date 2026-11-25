package com.gravity.ide.core

/** Small text decisions for showing chat replies (pure Kotlin, unit tested). */
object ChatFormat {
    private val proseLanguages = setOf("", "text", "txt", "plaintext", "plain", "lyrics", "poem", "song", "markdown", "md", "hindi", "hinglish")

    /**
     * True when a fenced block is really prose (lyrics, a poem, a letter) that a model put in a
     * code fence: no language or a prose one, and almost none of the characters code is made of.
     * Such blocks are shown as normal text instead of a monospaced code box.
     */
    fun isProse(language: String?, body: String): Boolean {
        if (language != null && language.lowercase() !in proseLanguages) return false
        val text = body.trim()
        if (text.isEmpty()) return false
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.any { l -> val t = l.trimEnd(); t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.startsWith("$ ") || t.startsWith("#!") }) return false
        val codeChars = text.count { it in "{}();=<>[]|\\_\$&*/#@" }
        val letters = text.count { it.isLetter() }
        return letters >= 20 && codeChars * 50 < text.length
    }
}
