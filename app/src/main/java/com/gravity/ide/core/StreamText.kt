package com.gravity.ide.core

import java.io.ByteArrayOutputStream

/**
 * Server-sent events parser (used for Gemini `alt=sse` and OpenAI-compatible `stream=true`).
 * Feed it lines; it returns the payload of each completed event.
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** Returns a completed event's data when [line] terminates an event, otherwise null. */
    fun feed(line: String): String? {
        if (line.isEmpty()) {
            if (!hasData) return null
            val payload = data.toString()
            data.setLength(0)
            hasData = false
            return payload
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    /** Flush a trailing event when the stream ends without a blank line. */
    fun finish(): String? = if (hasData) feed("") else null
}

/**
 * Accumulates UTF-8 bytes from token-by-token native output and only releases complete
 * code points, so multi-byte characters split across tokens are never corrupted.
 */
class Utf8Accumulator {
    private val pending = ByteArrayOutputStream()

    fun append(bytes: ByteArray): String {
        pending.write(bytes)
        val all = pending.toByteArray()
        val cut = completePrefixLength(all)
        pending.reset()
        if (cut < all.size) pending.write(all, cut, all.size - cut)
        return String(all, 0, cut, Charsets.UTF_8)
    }

    fun flush(): String {
        val all = pending.toByteArray()
        pending.reset()
        return String(all, Charsets.UTF_8)
    }

    companion object {
        fun completePrefixLength(bytes: ByteArray): Int {
            var i = bytes.size - 1
            var continuation = 0
            // Walk back over at most 3 continuation bytes to find the last lead byte.
            while (i >= 0 && continuation < 4) {
                val b = bytes[i].toInt() and 0xFF
                if (b and 0xC0 == 0x80) {
                    continuation++
                    i--
                    continue
                }
                val needed = when {
                    b and 0x80 == 0 -> 0
                    b and 0xE0 == 0xC0 -> 1
                    b and 0xF0 == 0xE0 -> 2
                    b and 0xF8 == 0xF0 -> 3
                    else -> 0
                }
                return if (continuation >= needed) bytes.size else i
            }
            return bytes.size
        }
    }
}
