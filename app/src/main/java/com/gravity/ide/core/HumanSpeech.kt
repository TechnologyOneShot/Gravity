package com.gravity.ide.core

import kotlin.random.Random

/**
 * Makes the phone's text-to-speech sound more like a person talking. A text-to-speech voice reads
 * every sentence at one speed, one pitch and one loudness. People do not: they stretch the word
 * that carries the feeling ("bahuuut achha"), rush through connecting bits, get brighter and louder
 * when excited, rise at a question, drop their voice for an aside, slow down at the end of a long
 * sentence, and pause in different places for different lengths.
 *
 * [plan] splits a text into phrases and gives each its own speed, pitch, loudness and pause after
 * it; the caller speaks them one by one (Android's TextToSpeech takes speed and pitch per call).
 * Pure Kotlin, unit tested; the same text always gets the same plan ([seed]).
 */
object HumanSpeech {

    /** [rate] and [pitch] multiply the voice's own settings; [volume] is 0..1. */
    data class Phrase(val text: String, val rate: Float, val pitch: Float, val volume: Float, val pauseAfterMs: Int)

    /** Words people stretch and stress (the feeling of the sentence). */
    private val emphasis = setOf(
        "bahut", "bohot", "bahot", "bilkul", "zaroor", "jaroor", "ekdum", "kitna", "kitni", "itna", "itni", "kamaal", "zabardast",
        "really", "very", "absolutely", "totally", "definitely", "extremely", "amazing", "awesome", "wonderful", "fantastic",
        "बहुत", "बिल्कुल", "बिलकुल", "ज़रूर", "जरूर", "एकदम", "कितना", "कितनी", "इतना", "इतनी", "बेहद", "कमाल", "ज़बरदस्त", "जबरदस्त", "शानदार",
    )

    /** Words a person says on their own at the start, then a breath: "Arre, …", "Achha! …". */
    private val interjections = setOf(
        "arre", "arey", "achha", "accha", "acha", "haan", "hmm", "hm", "oh", "ohh", "oho", "wow", "waah", "wah", "oops", "okay", "ok",
        "well", "hey", "hello", "namaste", "अरे", "अच्छा", "हाँ", "हां", "हम्म", "ओह", "ओहो", "वाह", "नमस्ते",
    )

    /** Joining words: a long sentence breathes before them. */
    private val conjunctions = setOf(
        "aur", "lekin", "par", "magar", "kyunki", "kyonki", "jab", "agar", "phir", "fir", "and", "but", "because", "when", "if", "then",
        "which", "और", "लेकिन", "पर", "मगर", "क्योंकि", "जब", "अगर", "फिर",
    )

    /** Bits people say quickly. */
    private val fillers = setOf("matlab", "waise", "actually", "basically", "i mean", "you know", "मतलब", "यानी", "वैसे")

    private val sentenceSplit = Regex("(?<=[.!?।…])\\s+")
    private val clauseSplit = Regex("(?<=[,;:—–])\\s+|\\s+(?=[—–]\\s)")

    private fun bare(word: String) = word.lowercase().trim { !it.isLetterOrDigit() }

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private enum class Kind { NORMAL, EMPHASIS, INTERJECTION, ASIDE, FILLER }

    private class Piece(val text: String, val kind: Kind, val pauseMs: Int)

    /**
     * The phrases to speak for [text]. [sentencePauseMs] is the pause between sentences (the
     * speaking style's own pause, at least a breath).
     */
    fun plan(text: String, seed: Long = text.hashCode().toLong(), sentencePauseMs: Int = 320): List<Phrase> {
        val rnd = Random(seed)
        fun jitter(range: Float) = (rnd.nextFloat() * 2 - 1) * range
        fun jitterMs(from: Int, to: Int) = from + rnd.nextInt((to - from).coerceAtLeast(1))
        val out = ArrayList<Phrase>()
        val sentences = text.trim().split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }
        for ((si, sentence) in sentences.withIndex()) {
            val end = sentence.trimEnd().lastOrNull { !it.isWhitespace() && it != '"' && it != '\'' && it != ')' }
            val exclaim = end == '!'
            val question = end == '?'
            val trailing = sentence.trimEnd().endsWith("...") || end == '…'
            val pieces = ArrayList<Piece>()
            var rest = sentence
            // "Arre, …" / "Wow! …": the opening word on its own.
            val first = words(rest).firstOrNull()
            if (first != null && bare(first) in interjections && words(rest).size > 2) {
                pieces += Piece(first, Kind.INTERJECTION, jitterMs(120, 220))
                rest = rest.substringAfter(first).trim()
            }
            // Asides in brackets: softer and quicker.
            val parts = ArrayList<Pair<String, Boolean>>()
            var i = 0
            while (i < rest.length) {
                val open = rest.indexOf('(', i)
                val close = if (open >= 0) rest.indexOf(')', open) else -1
                if (open < 0 || close < 0) { parts += rest.substring(i) to false; break }
                if (open > i) parts += rest.substring(i, open) to false
                // Punctuation right after the bracket stays with the aside (no lone "," to speak).
                var stop = close + 1
                while (stop < rest.length && rest[stop] in ",;:.!?।") stop++
                parts += rest.substring(open, stop) to true
                i = stop
            }
            var stressed = false
            for ((part, aside) in parts) {
                if (part.isBlank()) continue
                if (aside) { pieces += Piece(part.trim(), Kind.ASIDE, jitterMs(90, 160)); continue }
                for (clause in part.trim().split(clauseSplit).map { it.trim() }.filter { it.isNotEmpty() }) {
                    for (chunk in splitAtConjunction(clause)) {
                        val ws = words(chunk)
                        if (bare(chunk) in fillers || ws.joinToString(" ") { bare(it) } in fillers) {
                            pieces += Piece(chunk, Kind.FILLER, jitterMs(60, 120)); continue
                        }
                        // One stretched word a sentence at most, and only in a sentence long enough to carry it.
                        val at = if (!stressed && words(sentence).size >= 4) ws.indexOfFirst { bare(it) in emphasis } else -1
                        if (at < 0) {
                            pieces += Piece(chunk, Kind.NORMAL, jitterMs(140, 240))
                            continue
                        }
                        stressed = true
                        // The stressed word with the word it lifts ("bahut achha"), as one drawn-out piece.
                        val take = if (at + 1 < ws.size && bare(ws[at + 1]).isNotEmpty() && bare(ws[at + 1]) !in conjunctions) 2 else 1
                        val before = ws.subList(0, at)
                        // A single word left after it goes with it ("bahut achha hai!"): tiny pieces sound choppy.
                        val end = if (ws.size - (at + take) <= 1) ws.size else at + take
                        val stress = ws.subList(at, end)
                        val after = ws.subList(end, ws.size)
                        if (before.isNotEmpty()) pieces += Piece(before.joinToString(" "), Kind.NORMAL, jitterMs(30, 70))
                        pieces += Piece(stress.joinToString(" "), Kind.EMPHASIS, if (after.isNotEmpty()) jitterMs(40, 90) else jitterMs(140, 240))
                        if (after.isNotEmpty()) pieces += Piece(after.joinToString(" "), Kind.NORMAL, jitterMs(140, 240))
                    }
                }
            }
            if (pieces.isEmpty()) continue
            // The sentence's own pause replaces the last piece's.
            val endPause = when {
                trailing -> sentencePauseMs + 180
                question -> sentencePauseMs + 80
                exclaim -> sentencePauseMs + 40
                else -> sentencePauseMs
            } + jitterMs(-60, 60)
            for ((pi, p) in pieces.withIndex()) {
                val last = pi == pieces.lastIndex
                var rate = 1f + jitter(0.06f)
                var pitch = 1f + jitter(0.035f)
                var volume = 0.93f + jitter(0.05f)
                when (p.kind) {
                    Kind.EMPHASIS -> { rate = 0.78f + jitter(0.04f); pitch = 1.07f + jitter(0.02f); volume = 1f }
                    Kind.INTERJECTION -> { rate = 0.84f + jitter(0.04f); pitch = 1.09f + jitter(0.03f); volume = 1f }
                    Kind.ASIDE -> { rate = 1.09f + jitter(0.03f); pitch = 0.95f; volume = 0.78f }
                    Kind.FILLER -> { rate = 1.13f + jitter(0.04f); volume = 0.86f }
                    Kind.NORMAL -> Unit
                }
                if (exclaim && p.kind != Kind.ASIDE) { pitch += 0.06f; rate += 0.04f; volume = maxOf(volume, 0.98f) }
                if (question && last) pitch += 0.07f
                if (trailing) rate -= 0.08f
                // A long sentence starts a little stronger and settles at its end, as people talk.
                if (pieces.size >= 3 && pi == 0 && p.kind == Kind.NORMAL) volume += 0.03f
                if (pieces.size >= 3 && last && !question && !exclaim) { rate -= 0.04f; volume -= 0.04f }
                out += Phrase(
                    p.text,
                    rate.coerceIn(0.7f, 1.3f),
                    pitch.coerceIn(0.85f, 1.2f),
                    volume.coerceIn(0.6f, 1f),
                    if (last) (if (si == sentences.lastIndex) 0 else endPause.coerceAtLeast(120)) else p.pauseMs,
                )
            }
        }
        return out
    }

    /** A long clause (over 12 words) breathes before a joining word, if both sides keep 3+ words. */
    private fun splitAtConjunction(clause: String): List<String> {
        val ws = words(clause)
        if (ws.size <= 12) return listOf(clause)
        for (i in 3..ws.size - 3) {
            if (bare(ws[i]) in conjunctions) {
                return listOf(ws.subList(0, i).joinToString(" ")) + splitAtConjunction(ws.subList(i, ws.size).joinToString(" "))
            }
        }
        return listOf(clause)
    }
}
