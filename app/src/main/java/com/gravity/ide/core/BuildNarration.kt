package com.gravity.ide.core

/**
 * Turns the App Builder's real task log into short first-person lines for the chat ("I'm writing
 * the app…", "Tested it: tapped 6 buttons, no errors", "Found 1 error. Fixing it…"), like an
 * assistant explaining its work. Pure Kotlin, unit tested.
 *
 * Only events that really happened are shown (each line comes from one log entry); internal ones
 * (model calls, routing) are left out. Nothing is added that the builder did not do.
 */
object BuildNarration {
    private val testResult = Regex("^App test: (\\d+) error\\(s\\), (\\d+) button\\(s\\) tapped")
    private val testAfterFix = Regex("^App test after the fix: (\\d+) error\\(s\\)")
    private val wrote = Regex("^Wrote (.+?) \\(([\\d,]+) characters\\)")
    private val foundErrors = Regex("^The test found (\\d+) error\\(s\\); fixing them")
    private val noPage = Regex("^AI reply \\((\\d+) tokens, no complete page\\)")
    private val noEdits = Regex("^AI reply \\((\\d+) tokens, no edits\\)")

    /** The line for one log entry, or null when it is internal. */
    fun line(category: String, title: String, detail: String, level: String): String? {
        val t = title.trim()
        testResult.find(t)?.let { m ->
            val errors = m.groupValues[1].toInt()
            val taps = m.groupValues[2].toInt()
            val first = detail.lineSequence().firstOrNull { it.isNotBlank() }?.take(140)
            return if (errors == 0) "Tested it on this phone: opened the app and tapped $taps button${s(taps)} — no errors."
            else "Tested it: tapped $taps button${s(taps)} and found $errors error${s(errors)}" + (first?.let { ": $it" } ?: ".")
        }
        testAfterFix.find(t)?.let { m ->
            val errors = m.groupValues[1].toInt()
            return if (errors == 0) "Tested the fixed version: no errors now." else "Tested the fixed version: still $errors error${s(errors)}."
        }
        foundErrors.find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            return "Found $n error${s(n)}. Fixing ${if (n == 1) "it" else "them"}…"
        }
        wrote.find(t)?.let { m ->
            return when {
                detail.contains("first version restored") -> "The fix made it worse, so I put the first version back."
                detail.contains("fix after test") -> "Saved the fixed version (${m.groupValues[2]} characters)."
                else -> "Wrote ${m.groupValues[1]} (${m.groupValues[2]} characters)."
            }
        }
        noPage.find(t)?.let { return "The AI stopped before finishing the page (${it.groupValues[1]} tokens)." }
        // The agent's own file and shell tools, said as what it does.
        toolLine(t, detail)?.let { return it }
        noEdits.find(t)?.let { return "The AI answered without any edits I could apply (${it.groupValues[1]} tokens)." }
        return when {
            t.startsWith("Reading your design picture") -> "I'm studying your design picture: layout, positions, colors and text…"
            t.startsWith("Design read as text") -> "The phone model can't see pictures, so I read the text in your design and placed it as best I can. " +
                "For an exact copy of the design, use Gemini."
            t.startsWith("Writing the app") -> "I'm writing the app" + who(t) + "…"
            t.startsWith("Rewriting the app with your change") -> "I'm rewriting the app with your change" + who(t) + "…"
            t.startsWith("Changing the app in small edits") -> "The app is big, so I'm making your change in small edits…"
            t.startsWith("Testing the app") -> "Now testing it: opening the app and tapping every button…"
            t.startsWith("Testing the fixed app") -> "Testing the fixed version…"
            t.startsWith("Cloud AI limit reached") -> "The cloud AI's free limit ran out, so I'm continuing with the phone model."
            t.startsWith("The fix was not a complete page") -> "My fix wasn't a complete page, so I kept the first version."
            t.startsWith("Could not ask for a fix") -> "I couldn't ask for a fix: the AI's free limit is used up."
            t.startsWith("Primary provider failed") -> "The AI didn't answer, so I switched to a backup AI."
            t.startsWith("Waiting for network") -> "Waiting for the internet…"
            t.startsWith("Connection lost") -> "The connection dropped; I'll continue when you're online."
            t == "Architect planning" || t.endsWith(" planning") -> "Planning the app…"
            t == "Plan ready" -> "Plan ready."
            t.startsWith("Developer implementing") -> "Writing the code…"
            t.startsWith("Developer applying review round") -> "Applying the review's feedback…"
            t.startsWith("Testing Agent running tests") -> "Running the tests…"
            t.startsWith("Reviewing") -> "Reviewing the code…"
            t.startsWith("Model call failed") -> "The AI call failed" + (detail.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { ": ${it.take(140)}" } ?: ".")
            t == "Task paused" -> "Paused."
            t == "Task cancelled" -> "Stopped."
            t.startsWith("Task failed") -> "Stopped" + (detail.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" } ?: ".")
            t.startsWith("App Builder: nothing was written") || t.startsWith("App Builder: index.html was not written") -> "Nothing was saved, so the app is unchanged."
            else -> null
        }
    }

    private val created = Regex("^Created (?!folder )(.+)$")
    private val updated = Regex("^Updated (.+)$")
    private val edited = Regex("^Edited (.+)$")
    private val reading = Regex("^Reading (?!your design)(.+)$")
    private val listing = Regex("^Listing (.+)$")
    private val searched = Regex("^Searched \"(.+)\"$")
    private val running = Regex("^Running: (.+)$")
    private val exit = Regex("^Exit (-?\\d+): (.+)$")
    private val folder = Regex("^Created folder (.+)$")
    private val moved = Regex("^Moved (.+) → (.+)$")

    /** Lines for the agent's tools (AgentTools): reading, creating, modifying files, running commands. */
    private fun toolLine(t: String, detail: String): String? {
        created.find(t)?.let { return "Created ${it.groupValues[1]}" + chars(detail) + "." }
        updated.find(t)?.let { return "Modified ${it.groupValues[1]} (rewrote it" + chars(detail).replace(" (", ", ").removeSuffix(")") + ")." }
        edited.find(t)?.let { return "Modified ${it.groupValues[1]}: ${detail.lowercase().ifBlank { "changed a part" }}." }
        folder.find(t)?.let { return "Created the folder ${it.groupValues[1]}." }
        moved.find(t)?.let { return "Moved ${it.groupValues[1]} to ${it.groupValues[2]}." }
        reading.find(t)?.let { return "Reading ${it.groupValues[1]}…" }
        listing.find(t)?.let { return "Looking at the files in ${it.groupValues[1].ifBlank { "the project" }}…" }
        searched.find(t)?.let { return "Searching the code for \"${it.groupValues[1]}\"" + (detail.takeIf { d -> d.isNotBlank() }?.let { d -> ": $d" } ?: "") + "." }
        running.find(t)?.let { return "Running: ${it.groupValues[1]}" }
        exit.find(t)?.let {
            val code = it.groupValues[1].toInt()
            return if (code == 0) "Finished: ${it.groupValues[2]} (OK)." else "Failed (exit $code): ${it.groupValues[2]}."
        }
        return null
    }

    /** " (1,234 characters)" from a tool log detail "1234 chars". */
    private fun chars(detail: String): String =
        Regex("^(\\d+) chars").find(detail.trim())?.let { " (" + "%,d".format(it.groupValues[1].toLong()) + " characters)" } ?: ""

    /** All lines for a task's log, without immediate repeats. */
    fun lines(entries: List<Entry>): List<String> {
        val out = ArrayList<String>()
        for (e in entries) {
            val l = line(e.category, e.title, e.detail, e.level) ?: continue
            if (out.lastOrNull() != l) out += l
        }
        return out
    }

    data class Entry(val category: String, val title: String, val detail: String, val level: String)

    private fun s(n: Int) = if (n == 1) "" else "s"

    /** " with gemini-3-flash" or " with the phone model", from a stage label "… · who". */
    private fun who(stage: String): String {
        val w = stage.substringAfter(" · ", "").trim()
        return when {
            w.isEmpty() -> ""
            w.startsWith("phone model") -> " with the phone model"
            else -> " with $w"
        }
    }
}
