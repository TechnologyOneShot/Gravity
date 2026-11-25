package com.gravity.ide.core

/** Decides whether text taken from a PDF's text layer is usable (pure Kotlin, unit tested). */
object TextQuality {
    private val wordSplit = Regex("\\s+")
    private val legacyFontMarks = Regex("[;|~\\[\\]{}]")

    /**
     * True when a PDF's text layer is missing or unreadable, so the pages should be read with OCR
     * instead: almost no text per page (scanned pages), many replacement or private-use characters
     * (fonts without a Unicode map), or "words" full of ; | ~ [ ] inside them, which is how Hindi
     * typed in legacy fonts such as Kruti Dev comes out ("fo|ky; ds fu;e").
     */
    fun pdfTextLooksBroken(text: String, pages: Int): Boolean {
        val t = text.trim()
        if (pages > 0 && t.length < 40 * pages) return true
        if (t.isEmpty()) return true
        val odd = t.count { it == '�' || it in ''..'' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }
        if (odd * 100 > t.length) return true
        val words = t.split(wordSplit).filter { it.length >= 3 }
        if (words.size >= 20) {
            val legacy = words.count { w -> legacyFontMarks.containsMatchIn(w.dropLast(1)) || w.startsWith(",") }
            if (legacy * 100 > words.size * 6) return true
        }
        return false
    }
}
