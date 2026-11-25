package com.gravity.ide.agent

import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.AgentTaskEntity
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.workspace.CheckpointManager
import com.gravity.ide.workspace.ProjectTemplate
import com.gravity.ide.workspace.WorkspaceManager
import java.io.File

/**
 * App Builder: "make a music player" → a project, an agent run that writes a complete mobile
 * web app, a live preview, and later "add a shuffle button" → another run on the same app.
 * Everything goes through the normal agent (real model calls, real file writes, checkpoints),
 * so each change can be reviewed in Activity and undone.
 */
class AppBuilder(
    private val db: GravityDatabase,
    private val workspace: WorkspaceManager,
    private val agent: AgentEngine,
    private val checkpoints: CheckpointManager,
    private val events: EventLog,
) {
    /**
     * Creates the app project and starts the build run with the given AI ([providerId]/[model]:
     * the model chosen in the chat; null = the default AI). Returns the project and the task id.
     */
    suspend fun create(
        request: String, providerId: String? = null, model: String? = null,
        /** Pictures of how the app must look (a design from another app, a screenshot, a sketch). */
        designs: List<com.gravity.ide.ai.ImagePart> = emptyList(),
        /** Attached files with details for the app (name to text). */
        references: List<Pair<String, String>> = emptyList(),
    ): Pair<ProjectEntity, String> {
        val text = request.trim().ifEmpty { if (designs.isNotEmpty()) "Build the app shown in the design picture." else "" }
        require(text.isNotEmpty()) { "Describe the app you want." }
        val project = workspace.createProject(nameFor(text), ProjectTemplate.WEB_APP)
        File(project.rootPath, "GRAVITY.md").writeText(projectNotes(project.name, text))
        val saved = saveDesigns(project, designs)
        val taskId = agent.create(project.id, withDesigns(buildInstruction(withReferences(text, references)), saved), providerId, model, ReasoningMode.BALANCED, null)
        events.log(EventCategory.AGENT, "App Builder: building ${project.name}", text, taskId = taskId, projectId = project.id)
        agent.start(taskId)
        return project to taskId
    }

    /** Starts a run that applies [request] to an existing app. Returns the task id. */
    suspend fun change(
        project: ProjectEntity, request: String, providerId: String? = null, model: String? = null,
        designs: List<com.gravity.ide.ai.ImagePart> = emptyList(), references: List<Pair<String, String>> = emptyList(),
    ): String {
        val text = request.trim().ifEmpty { if (designs.isNotEmpty()) "Make the app look like the design picture." else "" }
        require(text.isNotEmpty()) { "Describe the change." }
        File(project.rootPath, "GRAVITY.md").appendText("\n- Change requested: ${text.replace('\n', ' ')}\n")
        val saved = saveDesigns(project, designs)
        val taskId = agent.create(project.id, withDesigns(changeInstruction(withReferences(text, references)), saved), providerId, model, ReasoningMode.BALANCED, null)
        events.log(EventCategory.AGENT, "App Builder: change to ${project.name}", text, taskId = taskId, projectId = project.id)
        agent.start(taskId)
        return taskId
    }

    /** Runs a failed or stopped instruction again as a fresh run. Returns the new task id. */
    suspend fun retry(project: ProjectEntity, task: AgentTaskEntity): String {
        // Same AI as the run being retried (e.g. the phone model the user chose).
        val taskId = agent.create(project.id, task.instruction, task.providerId, task.model, ReasoningMode.BALANCED, null)
        agent.start(taskId)
        return taskId
    }

    /** Saves design pictures in the app's folder (design/…), so retries and later changes can use them. */
    private fun saveDesigns(project: ProjectEntity, designs: List<com.gravity.ide.ai.ImagePart>): List<String> {
        if (designs.isEmpty()) return emptyList()
        val dir = File(project.rootPath, "design").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        return designs.mapIndexed { i, img ->
            val ext = if (img.mimeType.contains("png")) "png" else "jpg"
            val f = File(dir, "design-$stamp-${i + 1}.$ext")
            f.writeBytes(android.util.Base64.decode(img.base64, android.util.Base64.DEFAULT))
            "design/${f.name}"
        }
    }

    /** Most recent run on this app (build or change). */
    suspend fun latestTask(projectId: String): AgentTaskEntity? = db.tasks().forProject(projectId).maxByOrNull { it.createdAt }

    /** Reverts every file the latest finished run changed. Returns the number of files restored. */
    suspend fun undoLast(project: ProjectEntity): Int {
        val last = db.tasks().forProject(project.id).filter { it.status in FINISHED && it.stage != UNDONE }.maxByOrNull { it.createdAt }
            ?: throw IllegalStateException("There is no finished change to undo.")
        val restored = checkpoints.rollbackTask(last.id, File(project.rootPath), project.id)
        db.tasks().upsert(last.copy(stage = UNDONE, updatedAt = System.currentTimeMillis()))
        events.log(EventCategory.AGENT, "App Builder: undid \"${last.title}\"", "$restored file(s) restored", taskId = last.id, projectId = project.id)
        return restored
    }

    companion object {
        const val UNDONE = "Undone"
        private val FINISHED = setOf(TaskStatus.COMPLETED.name, TaskStatus.FAILED.name, TaskStatus.PAUSED.name, TaskStatus.CANCELLED.name)

        private val leadingFiller = Regex(
            "^(?:(?:please|pls|can you|could you|i want|i need|mujhe|mere liye|ek|make|build|create|generate|design|develop|write|code|me|for me|a|an|the)\\s+)+",
            RegexOption.IGNORE_CASE,
        )
        private val trailingFiller = Regex(
            "\\s+(?:bana do|bana de|banado|banao|bnao|bana dijiye|bana sakte ho|banaiye|kar do|karo|please|pls|for me|chahiye|do)\\s*[.!?]*$",
            RegexOption.IGNORE_CASE,
        )
        private val cutWords = Regex("\\s+(?:with|that|which|where|jisme|jismein|jo|using|for|ke saath|mein)\\s+.*$", RegexOption.IGNORE_CASE)

        /** "make a music player with playlists" → "Music Player"; "ek music player bana do" → "Music Player". */
        fun nameFor(request: String): String {
            var t = request.lineSequence().first().trim().trimEnd('.', '!', '?')
            t = cutWords.replace(t, "")
            repeat(3) { t = trailingFiller.replace(t, "") }
            t = leadingFiller.replaceFirst(t, "").trim().ifEmpty { t }
            val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
            val name = words.joinToString(" ") { w -> w.replace(Regex("[^\\p{L}\\p{N}_-]"), "").replaceFirstChar { it.uppercase() } }.trim()
            return name.take(40).ifBlank { "My App" }
        }

        const val BUILD_PREFIX = "Build this app for the user: "
        const val CHANGE_PREFIX = "Change request for this app: "
        /** In the starter page until the AI writes the app; packaging refuses pages that still have it. */
        const val PLACEHOLDER_MARKER = "gravity:placeholder"

        /** True for the starter page (also the wording of starter pages made by older versions). */
        fun isPlaceholder(html: String) = html.contains(PLACEHOLDER_MARKER) || html.contains("<p>Gravity is building this app…</p>")

        // trimStart: tasks saved by the earlier versions may begin with spaces (see buildInstruction).
        fun isBuilderInstruction(instruction: String) = instruction.trimStart().let { it.startsWith(BUILD_PREFIX) || it.startsWith(CHANGE_PREFIX) }

        /** Marks the design pictures of a build or change (paths in the app folder), at the end of the instruction. */
        const val DESIGN_PREFIX = "Design pictures to match (in the app folder): "

        fun withDesigns(instruction: String, paths: List<String>): String =
            if (paths.isEmpty()) instruction else instruction.trimEnd() + "\n\n" + DESIGN_PREFIX + paths.joinToString(", ")

        fun designsFrom(instruction: String): List<String> =
            instruction.lineSequence().firstOrNull { it.startsWith(DESIGN_PREFIX) }?.removePrefix(DESIGN_PREFIX)
                ?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() && !it.contains("..") }.orEmpty()

        /** Adds attached files (specs, data, text) to the request, kept short enough for a prompt. */
        fun withReferences(request: String, references: List<Pair<String, String>>, maxChars: Int = 8000): String {
            if (references.isEmpty()) return request
            var room = maxChars
            val parts = references.mapNotNull { (name, text) ->
                if (room <= 200) return@mapNotNull null
                val t = text.take(room)
                room -= t.length
                "Attached file $name (details for the app):\n```\n$t\n```"
            }
            return request + "\n\n" + parts.joinToString("\n\n")
        }
        fun isChangeInstruction(instruction: String) = instruction.trimStart().startsWith(CHANGE_PREFIX)

        /** Where the rules start after the request (tasks from earlier versions have them indented). */
        private val rulesStart = Regex("\\n\\s*\\n\\s*(Deliver a complete|Read the relevant files first)")

        /** The user's own words inside a build/change instruction. */
        fun requestFrom(instruction: String): String {
            val body = instruction.trimStart().removePrefix(BUILD_PREFIX).removePrefix(CHANGE_PREFIX)
            return body.split(rulesStart).first().substringBefore("\n\n$DESIGN_PREFIX").trim()
        }

        private val fileWords = Regex(
            "(?i)\\b(music|songs?|audio|mp3|playlists?|podcasts?|photos?|pictures?|images?|gallery|videos?|movies?|films?|files?|documents?|pdfs?|" +
                "gaan[ae]?|gana|gane|geet|tasve?er)\\b|player|फ़ोटो|फोटो|गाना|गाने|वीडियो|संगीत|तस्वीर",
        )

        private val appNouns = "app|apps|application|game|calculator|player|tracker|timer|stopwatch|todo|to-do|counter|quiz"
        private val appVerbs = "bana|banao|banado|banaiye|banaye|banana|build|make|create|chahiye"
        private val appIntent = Regex(
            "(?i)\\b($appNouns)\\b[\\s\\S]*\\b($appVerbs)\\b|\\b($appVerbs)\\b[\\s\\S]*\\b($appNouns)\\b|(ऐप|गेम|कैलकुलेटर)[\\s\\S]*बना|बना[\\s\\S]*(ऐप|गेम)",
        )

        /** True for messages that ask Gravity to build an app ("calculator bana do", "make a quiz game"). */
        fun looksLikeAppRequest(text: String) = appIntent.containsMatchIn(text)

        /** True when the app works with the user's own media or documents (e.g. a music player). */
        fun usesUserFiles(request: String) = fileWords.containsMatchIn(request)

        /**
         * Prompt for on-device models: one self-contained HTML file in a single reply. Small
         * models cannot follow the multi-step tool protocol, but many can write one file.
         */
        fun directPrompt(
            request: String, currentHtml: String?, compact: Boolean = true,
            /** How many design pictures are attached to the message (cloud AI that can see them). */
            designImages: Int = 0,
            /** For a phone model that cannot see pictures: the text read from the design, with positions. */
            designText: String? = null,
        ): Pair<String, String> {
            val system = if (compact) {
                "You are an expert web developer. You write small, complete, working mobile web apps as ONE self-contained HTML file " +
                    "with inline <style> and <script>. No external libraries, no CDNs, no comments, no explanations: output only the code."
            } else {
                "You are an expert web developer and designer. You write complete, polished, working mobile web apps as ONE self-contained " +
                    "HTML file with inline <style> and <script>. No external libraries or CDNs. Reply with the code only, in one ```html code block."
            }
            val size = if (compact) {
                "a clean modern look; keep it compact (about 100–200 lines, short CSS)"
            } else {
                "a beautiful modern design that follows the phone's light or dark mode (prefers-color-scheme), with smooth, app-like screens"
            }
            val rules = "Rules: one HTML file with a viewport meta tag; large touch-friendly buttons; $size; every button and feature must " +
                "really work: define every function you call, and show results in an element that is on screen. Use localStorage only if the app must remember data." +
                if (usesUserFiles(request) || currentHtml?.contains("type=\"file\"") == true) {
                    " The user picks their own files with <input type=\"file\"> (multiple where useful); use URL.createObjectURL to play or show " +
                        "them. Never use made-up file names or URLs."
                } else {
                    ""
                }
            val design = when {
                designImages > 0 ->
                    "\n\nThe user attached ${if (designImages == 1) "a design picture" else "$designImages design pictures"} of exactly how the app must look. " +
                        "Recreate it as exactly as you can: the same screens, layout and positions, sizes, spacing and rounded corners, the exact colors " +
                        "(use the hex values you see), the closest fonts and font weights, look-alike icons (inline SVG or emoji), pictures as CSS/SVG " +
                        "shapes of the same size, and every visible text word for word. Fit it to a phone screen. Then make every control in the design " +
                        "really work. If the request text and the picture disagree, follow the request text."
                !designText.isNullOrBlank() ->
                    "\n\nThe user's design picture contains these texts (top to bottom, with where they are on the screen):\n$designText\n" +
                        "Use these exact texts and place them where the design has them."
                else -> ""
            }
            val user = if (currentHtml == null) {
                "Build this app: $request$design\n\n$rules"
            } else {
                "Here is the current app:\n```html\n$currentHtml\n```\n\nChange request: $request$design\n\n" +
                    "Reply with the COMPLETE updated file (not a diff), keeping everything else working. $rules"
            }
            return system to user
        }

        /** Asks the model to fix the errors Gravity's test of the app found (see PageChecker). */
        fun repairPrompt(html: String, errors: List<String>, compact: Boolean = true): Pair<String, String> {
            val (system, _) = directPrompt("", null, compact)
            val user = "Here is an app:\n```html\n$html\n```\n\nWhen it was opened and its buttons were tapped, the browser reported these errors:\n" +
                errors.joinToString("\n") { "- $it" } +
                "\n\nFix the cause of every error (for example a name declared twice, or a function that is called but never defined) " +
                "and keep everything else working. Reply with the COMPLETE fixed file."
            return system to user
        }

        /** The reply is started with this, so the model writes code at once instead of an introduction. */
        const val DIRECT_PREFIX = "```html\n<!DOCTYPE html>\n<html"

        /** Extracts a complete HTML document from a model reply, or null if there is none. */
        fun extractHtml(reply: String): String? {
            val fenced = Regex("```[a-zA-Z]*[ \\t]*\\r?\\n([\\s\\S]*?)```").findAll(reply).map { it.groupValues[1] }
                .firstOrNull { it.contains("<html", true) || it.contains("<body", true) }
            val raw = fenced ?: run {
                val start = reply.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: reply.indexOf("<html", ignoreCase = true)
                val end = reply.lastIndexOf("</html>", ignoreCase = true)
                if (start < 0 || end < start) return null
                reply.substring(start, end + "</html>".length)
            }
            val html = raw.trim()
            val looksLikeHtml = html.contains("<html", true) || html.contains("<body", true)
            val complete = html.contains("</html>", true) || html.contains("</body>", true)
            return if (looksLikeHtml && complete && html.length >= 200 && !html.contains(PLACEHOLDER_MARKER)) html else null
        }

        // The user's request is added after trimIndent: a request of several lines (a pasted plan) has
        // no indentation, and inside the template it stopped trimIndent from removing any, so the
        // instruction began with spaces, was not seen as an app build, and went the slow tool route.
        fun buildInstruction(request: String): String = BUILD_PREFIX + request.trim() + "\n\n" + BUILD_RULES

        private val BUILD_RULES = """
            Deliver a complete, working app that runs inside an Android WebView on a phone:
            - Entry point index.html; styles in style.css; logic in app.js (add more files only if they help). Plain HTML/CSS/JavaScript: no build step, no frameworks from a CDN, no network requests unless the app's purpose needs online data.
            - Mobile first: portrait phone screen, large touch targets, readable text, a clean modern look, and it must respect light/dark mode (prefers-color-scheme).
            - Fully functional, no placeholders, TODOs or fake data presented as real. Save the user's data in localStorage where it makes sense.
            - Files on the device (music, photos, documents) are opened with <input type="file"> (allow multiple where useful) and read with object URLs / FileReader.
            - Replace the starter files completely. Keep index.html valid (doctype, viewport meta, title).
            When everything is written, call finish with a short summary in plain words: what the app does and how to use it.
        """.trimIndent()

        fun changeInstruction(request: String): String = CHANGE_PREFIX + request.trim() + "\n\n" + CHANGE_RULES

        private val CHANGE_RULES = """
            Read the relevant files first, make the change, and keep everything else working (same rules as the original build: plain HTML/CSS/JS in an Android WebView, mobile first, no placeholders).
            When done, call finish with a short summary of what changed.
        """.trimIndent()

        fun projectNotes(name: String, request: String): String = """
            # $name

            Built with Gravity App Builder. It runs as a web app inside an Android WebView and can be
            packaged as an APK on the phone (App → Install on phone).

            Rules for agents working on this project:
            - Plain HTML/CSS/JavaScript; entry point index.html. No build step, no CDN frameworks.
            - Mobile first, touch friendly, respects light/dark mode. Keep it fully working after every change.
            - Device files are opened with <input type="file">; data is kept in localStorage.

            ## What the user asked for
            $request

            ## Change history
        """.trimIndent() + "\n"
    }
}
