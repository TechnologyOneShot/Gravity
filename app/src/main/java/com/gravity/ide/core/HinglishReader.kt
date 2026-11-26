package com.gravity.ide.core

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Hindi voices read Roman letters by English rules, so a Hinglish reply ("Main aapke liye ek gaana
 * bana sakti hoon") came out garbled. This writes the Hindi words of a Hinglish sentence in
 * Devanagari for the voice ("मैं आपके लिए एक गाना बना सकती हूँ") and leaves English words
 * ("app", "music player") as they are. The word list is assets/hinglish-words.bin, gzip (made by
 * tools/hinglish/make-words.py). Only what the voice reads changes; the reply on screen does not.
 */
object HinglishReader {
    data class Result(val text: String, val latinWords: Int, val hindiWords: Int) {
        /** True when the text was mostly Hindi in Roman letters. */
        val wasHinglish: Boolean get() = hindiWords > 0
    }

    private val WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    private val SENTENCE_END = Regex("(?<=[.!?।\\n])")

    /** Reads the word list (lines "spelling<TAB>Devanagari", '#' starts a comment), gzip or plain. */
    fun parse(input: InputStream): Map<String, String> {
        val map = HashMap<String, String>(60_000)
        val buffered = java.io.BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        (if (gzip) GZIPInputStream(buffered) else buffered).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty() || line[0] == '#') return@forEach
                val tab = line.indexOf('\t')
                if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        return map
    }

    /**
     * [text] with the Hindi words of each Hinglish sentence in Devanagari. A sentence counts as
     * Hinglish when at least 2 of its Roman words, and 40% of them, are Hindi words (a one-word
     * sentence: that word); English sentences stay as they are.
     */
    fun read(text: String, words: Map<String, String>): Result {
        var latin = 0
        var hindi = 0
        val out = StringBuilder(text.length + 16)
        for (part in text.split(SENTENCE_END)) {
            val found = WORD.findAll(part).map { it.value }.toList()
            latin += found.size
            val hits = found.count { words.containsKey(it.lowercase()) }
            val hinglish = found.isNotEmpty() && hits * 10 >= found.size * 4 && (hits >= 2 || found.size == 1)
            if (!hinglish) {
                out.append(part)
                continue
            }
            hindi += hits
            out.append(WORD.replace(part) { m -> words[m.value.lowercase()] ?: m.value })
        }
        return Result(out.toString(), latin, hindi)
    }

    @Volatile
    private var loaded: Map<String, String>? = null

    /** The app's word list (read once, about 40,000 spellings). */
    fun words(context: android.content.Context): Map<String, String> =
        loaded ?: synchronized(this) {
            // Not named .gz: the Android build treats .gz assets specially, and the list was not found.
            loaded ?: runCatching { context.assets.open("hinglish-words.bin").use(::parse) }
                .getOrDefault(emptyMap()).also { if (it.isNotEmpty()) loaded = it }
        }

    /** [text] as a Hindi voice should read it (see [read]). */
    fun forVoice(context: android.content.Context, text: String): Result = read(text, words(context))
}
