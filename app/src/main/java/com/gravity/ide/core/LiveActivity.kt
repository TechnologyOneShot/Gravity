package com.gravity.ide.core

/**
 * What the AI is doing right now, read from the text it has written so far while it builds or
 * changes an app: which file it is creating or changing, how far it is, which part (layout,
 * styles, logic), and the last lines it wrote. Shown live in the chat ("Creating index.html ·
 * 142 lines · writing the styles (CSS)"), like a coding assistant that says what it does.
 * Pure Kotlin, unit tested. Everything comes from the real text; nothing is guessed ahead.
 */
object LiveActivity {
    enum class Verb(val label: String) { PLANNING("Planning"), CREATING("Creating"), MODIFYING("Modifying") }

    data class Activity(
        val verb: Verb,
        /** The file being written ("index.html"), or null while the AI is still explaining/planning. */
        val file: String?,
        /** Lines of code written so far in the current file (or edit). */
        val lines: Int,
        /** Which part of a web page: "layout (HTML)", "styles (CSS)", "logic (JavaScript)"; or the edit step. */
        val part: String?,
        /** Edits finished so far (changes made as edits); 0 otherwise. */
        val editsDone: Int,
        /** The last few lines written, for a live view of the code. */
        val tail: List<String>,
        /** True when the change is made as small edits (SEARCH/REPLACE blocks). */
        val asEdits: Boolean = false,
    ) {
        /** One line for the chat: "Creating index.html · 142 lines · writing the styles (CSS)". */
        fun headline(): String = buildString {
            append(verb.label)
            file?.let { append(' ').append(it) }
            if (asEdits) {
                append(" · change ").append(editsDone + if (part != null) 1 else 0)
            } else if (lines > 0) {
                append(" · ").append(lines).append(if (lines == 1) " line" else " lines")
            }
            part?.let { append(" · ").append(it) }
            if (verb == Verb.PLANNING) append("…")
        }
    }

    private val fileMarker = Regex("(?im)^\\s*(?:\\*\\*)?(?:File|फ़ाइल|फाइल)\\s*:\\s*(?:\\*\\*)?\\s*`?([\\w./-]+\\.[A-Za-z0-9]+)`?")
    private val fence = Regex("```([A-Za-z0-9+#-]*)")
    private const val SEARCH = "<<<<<<< SEARCH"
    private const val DIVIDER = "======="
    private const val REPLACE = ">>>>>>> REPLACE"

    /**
     * The activity for [written] (the AI's reply so far). [defaultFile] is the file an app is
     * written to when the reply does not name one (index.html for App Builder pages).
     */
    fun of(written: String, defaultFile: String = "index.html", change: Boolean = false): Activity {
        if (written.isBlank()) return Activity(Verb.PLANNING, null, 0, null, 0, emptyList())
        // Changes as edits: SEARCH / REPLACE blocks.
        if (written.contains(SEARCH)) {
            val done = Regex(Regex.escape(REPLACE)).findAll(written).count()
            val lastStart = written.lastIndexOf(SEARCH)
            val lastEnd = written.lastIndexOf(REPLACE)
            val open = lastStart > lastEnd
            val file = fileMarker.findAll(written).lastOrNull()?.groupValues?.get(1) ?: defaultFile
            val part: String?
            val body: String
            if (open) {
                val block = written.substring(lastStart + SEARCH.length)
                val div = block.indexOf(DIVIDER)
                if (div < 0) {
                    part = "finding the lines to change"
                    body = block
                } else {
                    part = "writing the new lines"
                    body = block.substring(div + DIVIDER.length)
                }
            } else {
                part = null
                body = written.substring(maxOf(0, lastEnd - 400), maxOf(0, lastEnd))
            }
            val lines = body.lines().filter { it.isNotBlank() }
            return Activity(Verb.MODIFYING, file, lines.size, part, done, lines.takeLast(TAIL).map { it.take(120) }, asEdits = true)
        }
        // A whole file in a code block (or a page with no fence at all).
        val fences = fence.findAll(written).toList()
        val insideCode = fences.size % 2 == 1
        val pageStart = Regex("(?i)<!doctype html|<html").find(written)
        if (!insideCode && fences.isEmpty() && pageStart == null) {
            // Still explaining or planning before any code.
            return Activity(Verb.PLANNING, null, 0, null, 0, emptyList())
        }
        val codeStart = when {
            insideCode -> fences.last().range.last + 1
            pageStart != null -> pageStart.range.first
            else -> fences.lastOrNull()?.range?.last?.plus(1) ?: 0
        }
        val language = if (insideCode) fences.last().groupValues[1].lowercase() else ""
        val before = written.substring(0, if (insideCode) fences.last().range.first else codeStart)
        val file = fileMarker.findAll(before).lastOrNull()?.groupValues?.get(1)
            ?: when (language) {
                "css" -> "style.css"
                "js", "javascript" -> "app.js"
                else -> defaultFile
            }
        val code = written.substring(codeStart).let { if (!insideCode && fences.size >= 2) it.substringBefore("```") else it }
        val lines = code.lines().dropWhile { it.isBlank() }
        val count = lines.count { it.isNotBlank() }
        val part = if (file.endsWith(".html", ignoreCase = true) || language == "html") partOfPage(code) else null
        val finished = !insideCode && fences.size >= 2
        return Activity(
            if (change) Verb.MODIFYING else Verb.CREATING, file, count,
            if (finished) "done" else part, 0, lines.filter { it.isNotBlank() }.takeLast(TAIL).map { it.take(120) },
        )
    }

    private const val TAIL = 4

    /** Which part of a web page the end of [code] is in. */
    fun partOfPage(code: String): String {
        val lower = code.lowercase()
        val lastStyle = lower.lastIndexOf("<style")
        val endStyle = lower.lastIndexOf("</style")
        val lastScript = lower.lastIndexOf("<script")
        val endScript = lower.lastIndexOf("</script")
        return when {
            lastScript > endScript -> "writing the logic (JavaScript)"
            lastStyle > endStyle -> "writing the styles (CSS)"
            else -> "writing the layout (HTML)"
        }
    }
}
