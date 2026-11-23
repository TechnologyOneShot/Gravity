package com.gravity.ide.workspace

import android.content.Context
import com.gravity.ide.core.LineDiff
import com.gravity.ide.core.PathGuard
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.FileVersionEntity
import com.gravity.ide.data.db.GravityDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Saves the previous content of a file before any editor save or agent write, records
 * who made the change (user / agent role / model) and restores on demand.
 */
class CheckpointManager(
    context: Context,
    private val db: GravityDatabase,
    private val events: EventLog,
) {
    private val blobRoot = File(context.filesDir, "checkpoints").apply { mkdirs() }

    data class Change(
        val version: FileVersionEntity,
        val before: String?,
        val after: String?,
        val diff: List<LineDiff.Line>,
    )

    /** Snapshot [file] (relative to [projectRoot]) before it is modified. */
    suspend fun snapshot(
        projectId: String,
        projectRoot: File,
        file: File,
        source: String,
        taskId: String? = null,
        agentRole: String? = null,
        model: String? = null,
        note: String = "",
    ): Long = withContext(Dispatchers.IO) {
        val rel = PathGuard.relativize(projectRoot, file)
        val blob = if (file.exists() && file.isFile) {
            val dir = File(blobRoot, projectId).apply { mkdirs() }
            val dest = File(dir, UUID.randomUUID().toString())
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        } else {
            null
        }
        db.fileVersions().insert(
            FileVersionEntity(
                projectId = projectId, relPath = rel, blobPath = blob, source = source, taskId = taskId,
                agentRole = agentRole, model = model, note = note, createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun readBlob(version: FileVersionEntity): String? = version.blobPath?.let { p -> File(p).takeIf { it.exists() }?.readText() }

    /** Changes made by a task: first snapshot of each file (state before the task) vs current content. */
    suspend fun changesForTask(taskId: String, projectRoot: File): List<Change> = withContext(Dispatchers.IO) {
        val versions = db.fileVersions().forTask(taskId)
        versions.groupBy { it.relPath }.map { (rel, list) ->
            val first = list.minBy { it.id }
            val current = File(projectRoot, rel)
            val before = readBlob(first)
            val after = if (current.isFile) current.readText() else null
            Change(first, before, after, LineDiff.diff(before ?: "", after ?: ""))
        }
    }

    suspend fun unifiedDiffForTask(taskId: String, projectRoot: File, maxChars: Int = 60_000): String {
        val sb = StringBuilder()
        for (c in changesForTask(taskId, projectRoot)) {
            val d = LineDiff.unified("a/${c.version.relPath}", "b/${c.version.relPath}", c.before ?: "", c.after ?: "")
            if (c.after == null) sb.append("deleted: ${c.version.relPath}\n")
            sb.append(d)
            if (sb.length > maxChars) {
                sb.setLength(maxChars)
                sb.append("\n… diff truncated\n")
                break
            }
        }
        return sb.toString()
    }

    /** Restore a single version: write its content back (or delete if the file did not exist). */
    suspend fun restore(versionId: Long, projectRoot: File, projectId: String): String = withContext(Dispatchers.IO) {
        val v = db.fileVersions().get(versionId) ?: throw IOException("Checkpoint not found")
        val target = PathGuard.resolveInside(projectRoot, v.relPath)
        // Keep the current state restorable too.
        snapshot(projectId, projectRoot, target, source = "restore", note = "Before restoring checkpoint #$versionId")
        if (v.blobPath == null) {
            target.delete()
        } else {
            target.parentFile?.mkdirs()
            File(v.blobPath).copyTo(target, overwrite = true)
        }
        events.log(EventCategory.WORKSPACE, "Restored ${v.relPath}", "Checkpoint #$versionId", projectId = projectId)
        v.relPath
    }

    /** Roll back every file a task touched to its state before the task. */
    suspend fun rollbackTask(taskId: String, projectRoot: File, projectId: String): Int {
        val firsts = db.fileVersions().forTask(taskId).groupBy { it.relPath }.map { (_, l) -> l.minBy { it.id } }
        for (v in firsts) restore(v.id, projectRoot, projectId)
        events.log(EventCategory.AGENT, "Rolled back task changes", "${firsts.size} files restored", taskId = taskId, projectId = projectId)
        return firsts.size
    }
}
