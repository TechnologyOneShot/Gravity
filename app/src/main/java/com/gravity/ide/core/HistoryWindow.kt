package com.gravity.ide.core

/**
 * Which earlier messages a chat reply includes. Two jobs:
 *
 *  - Fit the AI's context, counted in tokens on the safe side. Hindi in Devanagari takes several
 *    times more tokens per letter than English, so a budget in letters let a long Hindi chat
 *    overflow a phone model's context and fail.
 *  - Keep the start the same from one reply to the next. A phone model keeps what it has already
 *    read and reads only what comes after the first difference. A window that drops the oldest
 *    message on every reply changes the start every time, so once a chat was long the model
 *    re-read all of it (a minute or more on a phone) before every answer. Here the start moves
 *    only at fixed points, [step] tokens apart, so most replies read only the new message.
 */
object HistoryWindow {

    /** Tokens for [text], rounded up: about 3 letters a token for Latin script, 1 for other scripts, plus 4 for the message's own markers. */
    fun tokens(text: String): Int {
        var latin = 0
        var other = 0
        for (ch in text) if (ch.code < 0x250) latin++ else other++
        return (latin + 2) / 3 + other + 4
    }

    /**
     * The index of the first message to keep. [sizes] are the messages' tokens (oldest first),
     * [isUser] whether each is from the user, [budget] the tokens they may use together.
     * The fixed points depend only on older messages and [step], never on newer messages or
     * [budget], so they do not move as the chat grows. The last message is always kept.
     */
    fun start(sizes: List<Int>, isUser: List<Boolean>, budget: Int, step: Int): Int {
        val n = sizes.size
        if (n == 0) return 0
        val suffix = LongArray(n + 1)
        for (i in n - 1 downTo 0) suffix[i] = suffix[i + 1] + sizes[i]
        if (suffix[0] <= budget) return 0
        // Fixed points: the first user message after every [step] tokens, counted from the chat's start.
        val gap = step.coerceAtLeast(1)
        var before = 0L
        var lastPointAt = 0L
        for (i in 0 until n) {
            if (i > 0 && isUser[i] && before - lastPointAt >= gap) {
                lastPointAt = before
                if (suffix[i] <= budget) return i
            }
            before += sizes[i]
        }
        // No fixed point fits (very long messages): the newest messages that fit, from a user
        // message if one is among them, and at least the last message.
        var s = n - 1
        while (s > 0 && suffix[s - 1] <= budget) s--
        val user = (s until n).firstOrNull { isUser[it] }
        return user ?: s
    }
}
