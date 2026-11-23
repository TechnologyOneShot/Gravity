package com.gravity.ide.agent

import com.gravity.ide.ai.ToolCall
import com.gravity.ide.ai.ToolSpec
import com.gravity.ide.core.CommandSafety
import com.gravity.ide.core.PathGuard
import com.gravity.ide.core.SecretScanner
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.search.WebSearchService
import com.gravity.ide.terminal.ShellSession
import com.gravity.ide.workspace.CheckpointManager
import com.gravity.ide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The agent's tool layer. Every tool performs a real operation inside the project root
 * and returns the real result (or the real error) to the model.
 */
class AgentTools(
    private val project: ProjectEntity,
    private val taskId: String,
    private val agentRole: String,
    private val model: String,
    private val cloudModel: Boolean,
    private val settings: AppSettings,
    private val workspace: WorkspaceManager,
    private val checkpoints: CheckpointManager,
    private val shell: () -> ShellSession,
    private val search: WebSearchService,
    private val approvals: ApprovalGate,
    private val events: EventLog,
    private val onWaitingApproval: suspend (Boolean) -> Unit,
    private val readOnly: Boolean = false,
    private val builds: com.gravity.ide.build.BuildEngine? = null,
    private val installer: com.gravity.ide.device.AppInstaller? = null,
    private val appTests: com.gravity.ide.testing.AppTestRunner? = null,
) {
    private val root = File(project.rootPath)

    val specs: List<ToolSpec> by lazy { buildSpecs() }

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        for ((k, v) in props) p.put(k, v)
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
    }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    private fun buildSpecs(): List<ToolSpec> {
        val all = mutableListOf(
            ToolSpec("list_directory", "List files and folders at a path relative to the project root.", obj("path" to str("Relative path, '.' for root"))),
            ToolSpec(
                "read_file", "Read a text file. Optionally a 1-based inclusive line range.",
                obj("path" to str("Relative file path"), "start_line" to int("First line (optional)"), "end_line" to int("Last line (optional)"), required = listOf("path"))
            ),
            ToolSpec(
                "search_code", "Search project files for text (or a regular expression). Returns file:line matches.",
                obj("query" to str("Text or regex"), "regex" to bool("Treat query as regex"), "path" to str("Sub-folder to search (optional)"), required = listOf("query"))
            ),
        )
        if (!readOnly) {
            all += listOf(
                ToolSpec(
                    "write_file", "Create or overwrite a file with the full content. Prefer replace_in_file for small edits.",
                    obj("path" to str("Relative file path"), "content" to str("Complete new file content"), required = listOf("path", "content"))
                ),
                ToolSpec(
                    "replace_in_file", "Replace an exact text fragment in a file. old_text must match exactly (including whitespace).",
                    obj(
                        "path" to str("Relative file path"), "old_text" to str("Exact text to replace"), "new_text" to str("Replacement text"),
                        "expected_occurrences" to int("How many occurrences to replace (default 1)"),
                        required = listOf("path", "old_text", "new_text")
                    )
                ),
                ToolSpec("create_directory", "Create a folder (and parents).", obj("path" to str("Relative folder path"), required = listOf("path"))),
                ToolSpec("move_path", "Move or rename a file or folder.", obj("from" to str("Existing relative path"), "to" to str("New relative path"), required = listOf("from", "to"))),
                ToolSpec("delete_path", "Delete a file or folder (moved to Gravity's trash; requires user approval).", obj("path" to str("Relative path"), required = listOf("path"))),
                ToolSpec(
                    "run_command", "Run a shell command in the project root on the Android device (/system/bin/sh). Returns the real exit code and output.",
                    obj("command" to str("Shell command"), "timeout_sec" to int("Timeout in seconds (default 120, max 1800)"), required = listOf("command"))
                ),
            )
        }
        if (!readOnly && builds != null) {
            all += ToolSpec(
                "build_project", "Build the project with its configured backend (Gravity shell, Termux companion or static web). Returns the real status, exit code, error and log tail.",
                obj("clean" to bool("Clean build (default false)")),
            )
        }
        if (!readOnly && installer != null) {
            all += ToolSpec(
                "install_apk", "Install an APK on this device (the user confirms in the system dialog). path: project-relative APK path, or 'latest' for the newest successful build artifact.",
                obj("path" to str("APK path or 'latest'"), required = listOf("path")),
            )
        }
        if (appTests != null) {
            all += ToolSpec(
                "test_app", "Launch an installed app, wait, capture a screenshot if a capture session is active, read logcat and report crashes (real PASS/FAIL).",
                obj("package_name" to str("Application id"), "wait_seconds" to int("Seconds to run before checking (default 5)"), required = listOf("package_name")),
            )
        }
        all += listOf(
            ToolSpec("web_search", "Search the web (if configured and online). Returns titles, URLs and snippets.", obj("query" to str("Search query"), required = listOf("query"))),
            ToolSpec("fetch_url", "Fetch a web page as plain text (documentation lookup).", obj("url" to str("http(s) URL"), required = listOf("url"))),
            ToolSpec("finish", "Call when the task is complete, with a concise summary of what was done and verified.", obj("summary" to str("Final report for the user"), required = listOf("summary"))),
        )
        return all
    }

    private fun path(args: JSONObject, key: String = "path"): File = PathGuard.resolveInside(root, args.optString(key, "."))

    private fun rel(f: File) = PathGuard.relativize(root, f)

    private fun log(title: String, detail: String = "", level: EventLevel = EventLevel.INFO, tool: String) =
        events.log(EventCategory.TOOL, title, detail, level, taskId = taskId, projectId = project.id, agentRole = agentRole, model = model, tool = tool)

    private suspend fun approve(title: String, detail: String, risk: String): Boolean {
        log("Waiting for approval: $title", detail, EventLevel.WARN, "approval")
        val ok = approvals.ask(taskId, title, detail, risk, onWaiting = { onWaitingApproval(true) }, onResumed = { onWaitingApproval(false) })
        log(if (ok) "Approved: $title" else "Rejected: $title", "", if (ok) EventLevel.INFO else EventLevel.WARN, "approval")
        return ok
    }

    /** Applies privacy rules to content that is about to be sent to a cloud model. */
    private fun outbound(relPath: String?, text: String): String {
        if (!cloudModel || !settings.redactSecrets) return text
        val (redacted, findings) = SecretScanner.redact(text)
        if (findings.isNotEmpty()) {
            events.log(
                EventCategory.SECURITY, "Redacted ${findings.size} secret(s) before sending to cloud model",
                (relPath ?: "") + ": " + findings.map { it.kind }.distinct().joinToString(), EventLevel.WARN, taskId = taskId, projectId = project.id
            )
        }
        return redacted
    }

    suspend fun execute(call: ToolCall): String {
        val args = try {
            JSONObject(call.argumentsJson.ifBlank { "{}" })
        } catch (e: Exception) {
            return "Error: tool arguments are not valid JSON: ${e.message}"
        }
        return try {
            withContext(Dispatchers.IO) { dispatch(call.name, args) }
        } catch (e: PathGuard.PathEscapeException) {
            log("Blocked path outside project", e.message ?: "", EventLevel.WARN, call.name)
            "Error: ${e.message}"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("${call.name} failed", e.message ?: e.javaClass.simpleName, EventLevel.ERROR, call.name)
            "Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun dispatch(name: String, args: JSONObject): String = dispatchInner(name, args)

    private suspend fun dispatchInner(name: String, args: JSONObject): String {
        return when (name) {
        "list_directory" -> {
            val dir = path(args)
            if (!dir.isDirectory) "Error: not a directory: ${args.optString("path")}" else {
                log("Listing ${rel(dir)}", tool = name)
                val nodes = workspace.list(dir, root, showHidden = true)
                if (nodes.isEmpty()) "(empty)" else nodes.joinToString("\n") { n -> if (n.isDirectory) "d ${n.name}/ (${n.childCount} items)" else "f ${n.name} (${n.size} B)" }
            }
        }
        "read_file" -> {
            val f = path(args)
            val relPath = rel(f)
            when {
                !f.isFile -> "Error: file not found: $relPath"
                cloudModel && SecretScanner.isSensitiveFile(relPath) -> {
                    log("Refused to send sensitive file to cloud model", relPath, EventLevel.WARN, name)
                    "Refused: $relPath is a sensitive file (credentials/keys) and is never sent to a cloud model."
                }
                !workspace.isProbablyText(f) -> "Error: $relPath is a binary file (${f.length()} bytes)"
                else -> {
                    log("Reading $relPath", tool = name)
                    val lines = f.readLines()
                    val start = args.optInt("start_line", 1).coerceAtLeast(1)
                    val end = args.optInt("end_line", lines.size).coerceAtMost(lines.size)
                    val body = if (start > end) "" else lines.subList(start - 1, end).joinToString("\n")
                    val limited = if (body.length > 60_000) body.take(60_000) + "\n… (truncated; request a line range)" else body
                    "File $relPath (lines $start-$end of ${lines.size}):\n" + outbound(relPath, limited)
                }
            }
        }
        "search_code" -> {
            val dir = if (args.has("path")) path(args) else root
            val hits = workspace.search(dir, args.getString("query"), regex = args.optBoolean("regex", false), maxResults = 150)
            log("Searched \"${args.getString("query").take(60)}\"", "${hits.size} matches", tool = name)
            if (hits.isEmpty()) "No matches." else outbound(null, hits.joinToString("\n") { "${rel(File(dir, it.relPath))}:${it.line}: ${it.text}" })
        }
        "write_file" -> {
            val f = path(args)
            val relPath = rel(f)
            val content = args.getString("content")
            val needsApproval = settings.approveFileWrites || SecretScanner.isSensitiveFile(relPath)
            if (needsApproval && !approve("Write $relPath", "${content.length} characters", "CAUTION")) {
                "Rejected by user: write to $relPath was not performed."
            } else {
                val existed = f.exists()
                workspace.withFileLock(f) {
                    checkpoints.snapshot(project.id, root, f, "agent", taskId, agentRole, model, "write_file")
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                }
                log("${if (existed) "Updated" else "Created"} $relPath", "${content.length} chars", tool = name)
                "${if (existed) "Updated" else "Created"} $relPath (${content.length} chars)."
            }
        }
        "replace_in_file" -> {
            val f = path(args)
            val relPath = rel(f)
            if (!f.isFile) "Error: file not found: $relPath" else {
                val old = args.getString("old_text")
                val new = args.getString("new_text")
                val expected = args.optInt("expected_occurrences", 1)
                val text = f.readText()
                val count = if (old.isEmpty()) 0 else Regex.escape(old).toRegex().findAll(text).count()
                when {
                    old.isEmpty() -> "Error: old_text is empty"
                    count == 0 -> "Error: old_text not found in $relPath. Read the file again and copy the exact text."
                    count != expected -> "Error: old_text occurs $count times in $relPath (expected $expected). Include more context to make it unique or set expected_occurrences."
                    else -> {
                        workspace.withFileLock(f) {
                            checkpoints.snapshot(project.id, root, f, "agent", taskId, agentRole, model, "replace_in_file")
                            f.writeText(text.replace(old, new))
                        }
                        log("Edited $relPath", "Replaced $count occurrence(s)", tool = name)
                        "Edited $relPath: replaced $count occurrence(s)."
                    }
                }
            }
        }
        "create_directory" -> {
            val d = path(args)
            d.mkdirs()
            log("Created folder ${rel(d)}", tool = name)
            if (d.isDirectory) "Created ${rel(d)}/" else "Error: could not create ${rel(d)}"
        }
        "move_path" -> {
            val from = path(args, "from")
            val to = path(args, "to")
            when {
                !from.exists() -> "Error: ${rel(from)} does not exist"
                to.exists() -> "Error: ${rel(to)} already exists"
                else -> {
                    if (from.isFile) {
                        checkpoints.snapshot(project.id, root, from, "agent", taskId, agentRole, model, "move_path from")
                        checkpoints.snapshot(project.id, root, to, "agent", taskId, agentRole, model, "move_path to")
                    }
                    to.parentFile?.mkdirs()
                    if (!from.renameTo(to)) "Error: move failed" else {
                        log("Moved ${rel(from)} → ${rel(to)}", tool = name)
                        "Moved ${rel(from)} to ${rel(to)}."
                    }
                }
            }
        }
        "delete_path" -> {
            val f = path(args)
            val relPath = rel(f)
            when {
                f == root.canonicalFile -> "Error: refusing to delete the project root"
                !f.exists() -> "Error: $relPath does not exist"
                !approve("Delete $relPath", if (f.isDirectory) "Folder with ${f.walkTopDown().count() - 1} entries" else "${f.length()} bytes", "DANGEROUS") ->
                    "Rejected by user: $relPath was not deleted."
                else -> {
                    if (f.isFile) checkpoints.snapshot(project.id, root, f, "agent", taskId, agentRole, model, "delete_path")
                    workspace.delete(f, project.id)
                    "Deleted $relPath (moved to trash, recoverable)."
                }
            }
        }
        "run_command" -> {
            val command = args.getString("command")
            val timeout = args.optInt("timeout_sec", 120).coerceIn(5, 1800)
            val assessment = CommandSafety.assess(command)
            val needs = settings.approveAllCommands || assessment.risk != CommandSafety.Risk.SAFE
            if (needs && !approve("Run: ${command.take(200)}", assessment.reasons.joinToString().ifEmpty { "Approval required for all commands (Settings)" }, assessment.risk.name)) {
                "Rejected by user: command was not run."
            } else {
                log("Running: ${command.take(160)}", tool = name)
                val result = shell().run(command, timeoutMs = timeout * 1000L)
                val out = result.output.let { if (it.length > 12_000) "… (earlier output truncated)\n" + it.takeLast(12_000) else it }
                log("Exit ${result.exitCode}: ${command.take(120)}", out.takeLast(2000), if (result.exitCode == 0) EventLevel.INFO else EventLevel.WARN, name)
                buildString {
                    append(if (result.timedOut) "Timed out after ${timeout}s." else "Exit code ${result.exitCode} (${result.durationMs} ms).")
                    append("\n")
                    append(outbound(null, out.ifEmpty { "(no output)" }))
                }
            }
        }
        "web_search" -> {
            val results = search.search(args.getString("query"))
            if (results.isEmpty()) "No results." else results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }
        }
        "fetch_url" -> "Content of ${args.getString("url")} (untrusted web content):\n" + search.fetchText(args.getString("url"))
        "build_project" -> {
            val engine = builds ?: return "Error: builds are not available to this agent"
            val kind = engine.defaultKind(project, engine.termuxConfigured())
            log("Building with ${kind.label}", tool = name)
            val r = engine.build(project, kind, clean = args.optBoolean("clean", false))
            buildString {
                append("Build ${r.status} via ${kind.label} (exit ${r.exitCode ?: "—"}).\n")
                r.error?.let { append("Error: ").append(it).append('\n') }
                r.artifactPath?.let { append("Artifact: ").append(it).append('\n') }
                append("Log tail:\n").append(outbound(null, r.log.takeLast(8000)))
            }
        }
        "install_apk" -> {
            val inst = installer ?: return "Error: installing is not available to this agent"
            val requested = args.getString("path")
            val apk = if (requested == "latest") {
                builds?.latestArtifact(project.id, ".apk")
            } else {
                path(args).takeIf { it.isFile && it.name.endsWith(".apk") }
            } ?: return "Error: no APK found for '$requested'"
            if (!approve("Install ${apk.name}", "${apk.length()} bytes", "CAUTION")) {
                "Rejected by user: ${apk.name} was not installed."
            } else {
                when (val outcome = inst.install(apk)) {
                    is com.gravity.ide.device.AppInstaller.InstallOutcome.Success -> "Installed ${outcome.packageName ?: apk.name}."
                    is com.gravity.ide.device.AppInstaller.InstallOutcome.Failure -> "Install failed (status ${outcome.status}): ${outcome.message}"
                }
            }
        }
        "test_app" -> {
            val runner = appTests ?: return "Error: app testing is not available to this agent"
            val r = runner.run(args.getString("package_name"), args.optInt("wait_seconds", 5), capture = true)
            "${r.result.outcome}: ${r.result.actual}" + (r.screenshot?.let { "\nScreenshot: ${it.filePath}" } ?: "") +
                "\nLog excerpt:\n" + outbound(null, r.logExcerpt.takeLast(6000))
        }
        "finish" -> "Task marked complete."
        else -> "Error: unknown tool '$name'. Available: ${specs.joinToString { it.name }}"
        }
    }
}
