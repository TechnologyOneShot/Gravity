package com.gravity.ide.build

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gravity.ide.core.Glob
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.BuildRecordEntity
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.terminal.ShellSession
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.terminal.TerminalManager
import com.gravity.ide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class BuildStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class BuildKind(val label: String) {
    COMMAND("Gravity shell"),
    STATIC_WEB("Static web build"),
    TERMUX("Termux companion"),
}

/**
 * Build backends. A build is SUCCEEDED only when the process returned exit code 0 AND the
 * expected artifact exists on disk; otherwise it is FAILED with the real log.
 *
 * - STATIC_WEB: assembles `dist/` from a web/game project and zips it (fully on-device).
 * - COMMAND: runs the project's build command in the device shell (e.g. `./gradlew assembleDebug`).
 *   Whether that works depends on a toolchain being present — see the feasibility report.
 */
class BuildEngine(
    private val context: Context,
    private val db: GravityDatabase,
    private val terminals: TerminalManager,
    private val events: EventLog,
    private val workspace: WorkspaceManager,
    private val settings: SettingsRepository,
    val termux: TermuxBridge,
) {
    val artifactsDir: File = File(context.filesDir, "builds").apply { mkdirs() }
    private val shells = ConcurrentHashMap<String, ShellSession>()
    private val _running = MutableStateFlow<Map<String, Long>>(emptyMap())
    val running: StateFlow<Map<String, Long>> = _running.asStateFlow()

    fun defaultKind(project: ProjectEntity, termuxConfigured: Boolean = false): BuildKind {
        project.buildBackend?.let { b -> BuildKind.entries.firstOrNull { it.name == b }?.let { return it } }
        return when {
            (project.type == "WEB" || project.type == "GAME" || project.type == "APP") && project.buildCommand.isNullOrBlank() -> BuildKind.STATIC_WEB
            project.type == "ANDROID" && termuxConfigured -> BuildKind.TERMUX
            else -> BuildKind.COMMAND
        }
    }

    /** True when Termux is installed, permitted and a shared build folder is chosen. */
    suspend fun termuxConfigured(): Boolean {
        val st = termux.status()
        val share = settings.current().termuxShareUri
        return st.installed && st.permissionGranted && share != null && termux.sharePathFor(Uri.parse(share)) != null
    }

    suspend fun build(project: ProjectEntity, kind: BuildKind = defaultKind(project), clean: Boolean = false): BuildRecordEntity {
        if (_running.value.containsKey(project.id)) throw IllegalStateException("A build is already running for ${project.name}")
        val root = File(project.rootPath)
        val command = when (kind) {
            BuildKind.STATIC_WEB -> "static-web → dist/"
            BuildKind.COMMAND, BuildKind.TERMUX -> project.buildCommand?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No build command configured for ${project.name} (Project settings).")
        }
        var record = BuildRecordEntity(projectId = project.id, kind = kind.name, status = BuildStatus.RUNNING.name, command = command, startedAt = System.currentTimeMillis())
        val id = db.builds().insert(record)
        record = record.copy(id = id)
        _running.update { it + (project.id to id) }
        events.log(EventCategory.BUILD, "Build started: ${project.name}", command, projectId = project.id)
        try {
            if (clean && kind != BuildKind.TERMUX) cleanOutputs(root)
            record = when (kind) {
                BuildKind.STATIC_WEB -> staticWeb(project, root, record)
                BuildKind.COMMAND -> commandBuild(project, root, record, command)
                BuildKind.TERMUX -> termuxBuild(project, root, record, command, clean)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            record = record.copy(status = BuildStatus.CANCELLED.name, finishedAt = System.currentTimeMillis())
            withContext(kotlinx.coroutines.NonCancellable) { db.builds().update(record) }
            throw e
        } catch (e: Exception) {
            record = record.copy(status = BuildStatus.FAILED.name, error = e.message, finishedAt = System.currentTimeMillis())
        } finally {
            _running.update { it - project.id }
            shells.remove(project.id)?.close()
        }
        db.builds().update(record)
        val ok = record.status == BuildStatus.SUCCEEDED.name
        events.log(
            EventCategory.BUILD, "Build ${if (ok) "succeeded" else "failed"}: ${project.name}",
            (record.error ?: "") + (record.artifactPath?.let { "\nArtifact: $it" } ?: "") + "\n" + record.log.takeLast(3000),
            if (ok) EventLevel.INFO else EventLevel.ERROR, projectId = project.id,
        )
        return record
    }

    /** Newest existing artifact of a successful build for [projectId] with the given extension. */
    suspend fun latestArtifact(projectId: String, extension: String): File? = withContext(Dispatchers.IO) {
        db.builds().forProject(projectId)
            .filter { it.status == BuildStatus.SUCCEEDED.name && it.artifactPath?.endsWith(extension) == true }
            .mapNotNull { r -> r.artifactPath?.let { File(it) }?.takeIf { it.isFile } }
            .firstOrNull()
    }

    fun cancel(projectId: String) {
        shells[projectId]?.stop()
    }

    private fun cleanOutputs(root: File) {
        listOf("build", "app/build", "dist", ".gradle").map { File(root, it) }.filter { it.exists() }.forEach { it.deleteRecursively() }
    }

    private suspend fun commandBuild(project: ProjectEntity, root: File, record: BuildRecordEntity, command: String): BuildRecordEntity {
        val shell = terminals.ephemeral(root, "Build ${project.name}")
        shells[project.id] = shell
        val result = shell.run(command, timeoutMs = 60 * 60_000L)
        val log = result.output.takeLast(200_000)
        val pattern = project.artifactPattern
        val artifacts = if (pattern.isNullOrBlank()) emptyList() else withContext(Dispatchers.IO) { Glob.find(root, pattern) }
        val newest = artifacts.maxByOrNull { it.lastModified() }?.takeIf { it.lastModified() >= record.startedAt - 1000 }
        return when {
            result.timedOut -> record.copy(status = BuildStatus.FAILED.name, exitCode = result.exitCode, log = log, error = "Build timed out", finishedAt = System.currentTimeMillis())
            result.exitCode != 0 -> record.copy(status = BuildStatus.FAILED.name, exitCode = result.exitCode, log = log, error = explainFailure(result.exitCode, log), finishedAt = System.currentTimeMillis())
            !pattern.isNullOrBlank() && newest == null -> record.copy(
                status = BuildStatus.FAILED.name, exitCode = 0, log = log,
                error = "Command exited 0 but no fresh artifact matched '$pattern' — not counted as a successful build.", finishedAt = System.currentTimeMillis()
            )
            else -> {
                val stored = newest?.let { copyArtifact(project, it) }
                record.copy(status = BuildStatus.SUCCEEDED.name, exitCode = 0, log = log, artifactPath = stored?.absolutePath, finishedAt = System.currentTimeMillis())
            }
        }
    }

    private fun explainFailure(code: Int, log: String): String = when {
        code == 127 || log.contains("not found") && (log.contains("java") || log.contains("gradlew")) ->
            "Exit $code: a required tool is missing on this device (see Build Center → Feasibility). Android apps cannot run desktop JDK/Gradle from app storage without a companion toolchain."
        log.contains("Could not resolve") || log.contains("offline mode") -> "Exit $code: dependencies are not in the offline cache. Connect to the internet or prefill the Gradle cache."
        else -> "Exit code $code"
    }

    /**
     * Same-phone APK build through Termux:
     * 1. copy the project (sources only) into the user-chosen shared folder;
     * 2. in Termux, sync it to ~/gravity-builds/<project>, apply the aapt2/SDK settings Termux
     *    needs, run the build command, copy matching artifacts to <share>/<project>/.gravity-out;
     * 3. import the APK back through SAF and verify that Android can parse it.
     * SUCCEEDED requires exit code 0 and a parseable artifact.
     */
    private suspend fun termuxBuild(project: ProjectEntity, root: File, record: BuildRecordEntity, command: String, clean: Boolean): BuildRecordEntity {
        val shareUri = settings.current().termuxShareUri?.let { Uri.parse(it) }
            ?: throw IllegalStateException("Choose a shared build folder first (Build Center → Termux companion).")
        val sharePath = termux.sharePathFor(shareUri) ?: throw IllegalStateException("The chosen folder is not on device storage; pick a folder under internal storage.")
        val dirName = root.name
        val copied = workspace.exportTree(root, shareUri)
        val pattern = project.artifactPattern?.takeIf { it.isNotBlank() } ?: "app/build/outputs/apk/debug/*.apk"
        val script = com.gravity.ide.core.TermuxScript.build(sharePath, dirName, command, pattern, clean)
        val result = termux.run(script, timeoutMs = 90 * 60_000L)
        val log = buildString {
            append("[gravity] copied $copied source files to $sharePath/$dirName\n")
            append(result.stdout)
            if (result.stderr.isNotBlank()) append("\n--- stderr ---\n").append(result.stderr)
        }.takeLast(200_000)
        result.pluginError?.let {
            return record.copy(status = BuildStatus.FAILED.name, exitCode = result.exitCode, log = log, error = "Termux could not run the command: $it", finishedAt = System.currentTimeMillis())
        }
        if (result.exitCode != 0) {
            return record.copy(status = BuildStatus.FAILED.name, exitCode = result.exitCode, log = log, error = explainFailure(result.exitCode, log), finishedAt = System.currentTimeMillis())
        }
        val imported = withContext(Dispatchers.IO) {
            val out = DocumentFile.fromTreeUri(context, shareUri)?.findFile(dirName)?.findFile(".gravity-out")
            val doc = out?.listFiles()?.filter { (it.name ?: "").let { n -> n.endsWith(".apk") || n.endsWith(".aab") || n.endsWith(".zip") || n.endsWith(".jar") } }?.maxByOrNull { it.lastModified() }
            doc?.let { d ->
                val dest = File(artifactsDir, "${project.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${System.currentTimeMillis()}-${d.name}")
                context.contentResolver.openInputStream(d.uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                dest.takeIf { it.length() > 0 }
            }
        } ?: return record.copy(
            status = BuildStatus.FAILED.name, exitCode = 0, log = log,
            error = "Termux build exited 0 but no artifact matched '$pattern' — not counted as a successful build.", finishedAt = System.currentTimeMillis(),
        )
        if (imported.name.endsWith(".apk")) {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(imported.absolutePath, 0)
            if (info == null) {
                return record.copy(status = BuildStatus.FAILED.name, exitCode = 0, log = log, artifactPath = imported.absolutePath, error = "The APK produced by Termux could not be parsed by Android.", finishedAt = System.currentTimeMillis())
            }
        }
        return record.copy(status = BuildStatus.SUCCEEDED.name, exitCode = 0, log = log, artifactPath = imported.absolutePath, finishedAt = System.currentTimeMillis())
    }

    private suspend fun staticWeb(project: ProjectEntity, root: File, record: BuildRecordEntity): BuildRecordEntity = withContext(Dispatchers.IO) {
        val dist = File(root, "dist")
        dist.deleteRecursively()
        dist.mkdirs()
        val log = StringBuilder()
        val skip = setOf("dist", ".git", "node_modules", "GRAVITY.md", ".gitignore")
        var files = 0
        root.listFiles().orEmpty().filter { it.name !in skip }.forEach { src ->
            src.copyRecursively(File(dist, src.name), overwrite = true)
            files += if (src.isDirectory) src.walkTopDown().count { it.isFile } else 1
            log.append("copied ").append(src.name).append('\n')
        }
        val index = File(dist, "index.html")
        if (!index.isFile) {
            return@withContext record.copy(status = BuildStatus.FAILED.name, exitCode = 1, log = log.toString(), error = "dist/index.html missing: a web project needs an index.html at its root", finishedAt = System.currentTimeMillis())
        }
        // Reference check: local src/href targets must exist.
        val missing = Regex("""(?:src|href)\s*=\s*["']([^"'#?]+)["']""").findAll(index.readText())
            .map { it.groupValues[1] }
            .filter { !it.contains("://") && !it.startsWith("data:") && !it.startsWith("mailto:") && !it.startsWith("//") }
            .filter { !File(dist, it.trimStart('/')).exists() }
            .toList()
        if (missing.isNotEmpty()) log.append("WARNING: index.html references missing files: ").append(missing.joinToString()).append('\n')
        val zip = File(artifactsDir, "${project.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-web-${System.currentTimeMillis()}.zip")
        zip.outputStream().use { com.gravity.ide.core.ZipSafety.zipDirectory(dist, it) }
        log.append("$files files → dist/, packaged ${zip.name} (${zip.length()} bytes)\n")
        record.copy(status = BuildStatus.SUCCEEDED.name, exitCode = 0, log = log.toString(), artifactPath = zip.absolutePath, finishedAt = System.currentTimeMillis())
    }

    private fun copyArtifact(project: ProjectEntity, artifact: File): File {
        val dest = File(artifactsDir, "${project.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}-${System.currentTimeMillis()}-${artifact.name}")
        artifact.copyTo(dest, overwrite = true)
        return dest
    }
}
