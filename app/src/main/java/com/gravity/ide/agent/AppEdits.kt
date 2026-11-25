package com.gravity.ide.agent

/**
 * Changes to an existing single-file app as small SEARCH/REPLACE edits instead of the whole
 * file. A phone model's memory (context) must hold the current app plus its answer; rewriting a
 * big app does not fit, but a few edits do. Pure Kotlin (no Android) so it is unit tested.
 */
object AppEdits {

    /** The reply is started with this, so the model writes an edit block at once. */
    const val PREFIX = "<<<<<<< SEARCH\n"

    data class Edit(val search: String, val replace: String)

    /** [applied] edits went in; [failed] holds the first line of each edit whose SEARCH text was not found. */
    data class Result(val html: String, val applied: Int, val failed: List<String>)

    fun prompt(request: String, currentHtml: String): Pair<String, String> {
        val system = "You are an expert web developer. You change an existing single-file HTML app by writing edit blocks only: " +
            "no explanations and no other text."
        val user = "Here is the current app (index.html):\n```html\n$currentHtml\n```\n\n" +
            "Change request: $request\n\n" +
            "Reply ONLY with one or more edit blocks in exactly this format:\n" +
            "<<<<<<< SEARCH\n(a few lines copied exactly from the current file)\n=======\n(the new lines that replace them)\n>>>>>>> REPLACE\n\n" +
            "Rules: copy the SEARCH lines exactly from the file above, and pick lines that appear only once; keep each SEARCH short. " +
            "To add CSS, SEARCH the line with </style> and write the new CSS followed by </style> in REPLACE. " +
            "To add HTML, SEARCH a nearby line or </body>. To add JavaScript, SEARCH the line with </script> and write the new code followed by </script>. " +
            "Every new button and feature must really work: define every function you call. No external libraries."
        return system to user
    }

    private val BLOCK = Regex("""<{5,9}[ \t]*SEARCH[^\n]*\n([\s\S]*?)\n?={5,9}[ \t]*\n([\s\S]*?)\n?>{5,9}[ \t]*REPLACE""")

    /** The edit blocks in a reply (the reply includes [PREFIX] when the model was started with it). */
    fun parse(reply: String): List<Edit> =
        BLOCK.findAll(reply.replace("\r\n", "\n"))
            .map { Edit(it.groupValues[1], it.groupValues[2]) }
            .filter { it.search.isNotBlank() }
            .toList()

    /** Applies [edits] in order; an edit whose SEARCH text is not in the file is skipped and listed in [Result.failed]. */
    fun apply(html: String, edits: List<Edit>): Result {
        var text = html.replace("\r\n", "\n")
        var applied = 0
        val failed = ArrayList<String>()
        for (e in edits) {
            val next = replaceOnce(text, e.search, e.replace)
            if (next != null) {
                text = next
                applied++
            } else {
                failed += e.search.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(60) ?: "(empty)"
            }
        }
        return Result(text, applied, failed)
    }

    /** Exact text first; then line by line ignoring indentation and trailing spaces (small models often change those). */
    internal fun replaceOnce(text: String, search: String, replace: String): String? {
        if (search.isBlank()) return null
        val at = text.indexOf(search)
        if (at >= 0) return text.substring(0, at) + replace + text.substring(at + search.length)
        val want = search.lines().map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (want.isEmpty()) return null
        val lines = text.split("\n")
        val trimmed = lines.map { it.trim() }
        for (start in 0..(trimmed.size - want.size)) {
            var match = true
            for (k in want.indices) {
                if (trimmed[start + k] != want[k]) {
                    match = false
                    break
                }
            }
            if (match) {
                val replaced = replace.split("\n").let { if (it.lastOrNull()?.isEmpty() == true && replace.endsWith("\n")) it.dropLast(1) else it }
                return (lines.subList(0, start) + replaced + lines.subList(start + want.size, lines.size)).joinToString("\n")
            }
        }
        return null
    }
}
