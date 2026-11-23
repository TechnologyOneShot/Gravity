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
    /** Creates the app project and starts the build run. Returns the project and the task id. */
    suspend fun create(request: String): Pair<ProjectEntity, String> {
        val text = request.trim()
        require(text.isNotEmpty()) { "Describe the app you want." }
        val project = workspace.createProject(nameFor(text), ProjectTemplate.WEB_APP)
        File(project.rootPath, "GRAVITY.md").writeText(projectNotes(project.name, text))
        val taskId = agent.create(project.id, buildInstruction(text), null, null, ReasoningMode.BALANCED, null)
        events.log(EventCategory.AGENT, "App Builder: building ${project.name}", text, taskId = taskId, projectId = project.id)
        agent.start(taskId)
        return project to taskId
    }

    /** Starts a run that applies [request] to an existing app. Returns the task id. */
    suspend fun change(project: ProjectEntity, request: String): String {
        val text = request.trim()
        require(text.isNotEmpty()) { "Describe the change." }
        File(project.rootPath, "GRAVITY.md").appendText("\n- Change requested: ${text.replace('\n', ' ')}\n")
        val taskId = agent.create(project.id, changeInstruction(text), null, null, ReasoningMode.BALANCED, null)
        events.log(EventCategory.AGENT, "App Builder: change to ${project.name}", text, taskId = taskId, projectId = project.id)
        agent.start(taskId)
        return taskId
    }

    /** Runs a failed or stopped instruction again as a fresh run. Returns the new task id. */
    suspend fun retry(project: ProjectEntity, task: AgentTaskEntity): String {
        val taskId = agent.create(project.id, task.instruction, null, null, ReasoningMode.BALANCED, null)
        agent.start(taskId)
        return taskId
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

        fun buildInstruction(request: String): String = """
            Build this app for the user: $request

            Deliver a complete, working app that runs inside an Android WebView on a phone:
            - Entry point index.html; styles in style.css; logic in app.js (add more files only if they help). Plain HTML/CSS/JavaScript: no build step, no frameworks from a CDN, no network requests unless the app's purpose needs online data.
            - Mobile first: portrait phone screen, large touch targets, readable text, a clean modern look, and it must respect light/dark mode (prefers-color-scheme).
            - Fully functional, no placeholders, TODOs or fake data presented as real. Save the user's data in localStorage where it makes sense.
            - Files on the device (music, photos, documents) are opened with <input type="file"> (allow multiple where useful) and read with object URLs / FileReader.
            - Replace the starter files completely. Keep index.html valid (doctype, viewport meta, title).
            When everything is written, call finish with a short summary in plain words: what the app does and how to use it.
        """.trimIndent()

        fun changeInstruction(request: String): String = """
            Change request for this app: $request

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
