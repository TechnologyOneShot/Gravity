package com.gravity.ide.core

/**
 * Notices when a phone model gets stuck repeating itself (the same lines again and again until the
 * token limit, as Qwen2.5-Coder 7B did in the CI chat check). Pure Kotlin, unit tested.
 */
object LoopGuard {
    /**
     * If [text] ends with the same block written [times] times in a row, the index where the
     * repetition starts (just after the first copy, which is kept); otherwise -1. Blocks need at
     * least [minChars] visible characters and some variety, so "-----" rules, "}" lines or a
     * chorus sung twice are not loops.
     */
    fun loopStart(text: CharSequence, times: Int = 3, minChars: Int = 20, maxBlock: Int = 600): Int {
        val n = text.length
        val longest = minOf(maxBlock, n / times)
        for (p in minChars..longest) {
            val start = n - p * times
            var same = true
            for (i in 0 until p * (times - 1)) {
                if (text[start + i] != text[start + i + p]) { same = false; break }
            }
            if (!same) continue
            val block = text.subSequence(n - p, n)
            val visible = block.count { !it.isWhitespace() }
            val distinct = block.filter { !it.isWhitespace() }.toSet().size
            if (visible >= minChars && distinct >= 8) return start + p
        }
        return -1
    }
}
