package com.gravity.ide.backup

import android.content.Context
import android.net.Uri
import com.gravity.ide.core.BackupCrypto
import com.gravity.ide.core.ZipSafety
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.AgentTaskEntity
import com.gravity.ide.data.db.ChatEntity
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.db.TeamEntity
import com.gravity.ide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Full backup: project files + chats, messages, teams, task history and project metadata.
 * API keys are never included (they are bound to this device's Keystore). Local model files
 * are excluded because of their size; their metadata is listed so they can be re-downloaded.
 */
class BackupManager(
    private val context: Context,
    private val db: GravityDatabase,
    private val workspace: WorkspaceManager,
    private val events: EventLog,
) {
    data class BackupSummary(val projects: Int, val chats: Int, val messages: Int, val tasks: Int, val bytes: Long, val encrypted: Boolean)

    suspend fun createBackup(dest: Uri, password: CharArray?): BackupSummary = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "backup-${System.currentTimeMillis()}.zip")
        val projects = db.projects().all()
        val chats = db.chats().all()
        var messageCount = 0
        val tasks = db.tasks().withStatus(listOf("PENDING", "RUNNING", "PAUSED", "WAITING_APPROVAL", "WAITING_NETWORK", "INTERRUPTED", "COMPLETED", "FAILED", "CANCELLED"))
        ZipOutputStream(FileOutputStream(tmp)).use { zos ->
            val manifest = JSONObject()
            manifest.put("format", "gravity-backup")
            manifest.put("version", 1)
            manifest.put("createdAt", System.currentTimeMillis())
            manifest.put("projects", JSONArray().apply { projects.forEach { put(projectJson(it)) } })
            val chatArr = JSONArray()
            for (c in chats) {
                val msgs = db.chats().messages(c.id)
                messageCount += msgs.size
                chatArr.put(chatJson(c).put("messages", JSONArray().apply { msgs.forEach { put(messageJson(it)) } }))
            }
            manifest.put("chats", chatArr)
            manifest.put("tasks", JSONArray().apply { tasks.forEach { put(taskJson(it)) } })
            // Providers are intentionally excluded: their API keys cannot leave the Keystore.
            manifest.put("teams", JSONArray().apply { db.teams().all().forEach { put(teamJson(it)) } })
            manifest.put("models", JSONArray().apply {
                db.models().all().forEach { m -> put(JSONObject().put("name", m.name).put("sourceUrl", m.sourceUrl ?: "").put("sha256", m.sha256Actual ?: "")) }
            })
            zos.putNextEntry(ZipEntry("gravity-backup.json"))
            zos.write(manifest.toString(2).toByteArray())
            zos.closeEntry()
            for (p in projects) {
                val root = File(p.rootPath)
                if (root.isDirectory) ZipSafety.addTree(zos, root.canonicalFile, root.canonicalFile, ZipSafety.DEFAULT_EXCLUDES, "workspaces/${p.id}/")
            }
        }
        val out = context.contentResolver.openOutputStream(dest) ?: throw IOException("Cannot write backup destination")
        out.use { o ->
            if (password != null && password.isNotEmpty()) tmp.inputStream().use { BackupCrypto.encrypt(it, o, password) } else tmp.inputStream().use { it.copyTo(o) }
        }
        val size = tmp.length()
        tmp.delete()
        events.log(EventCategory.SYSTEM, "Backup created", "${projects.size} projects, ${chats.size} chats, $messageCount messages, ${tasks.size} tasks${if (password != null && password.isNotEmpty()) ", encrypted" else ""}")
        BackupSummary(projects.size, chats.size, messageCount, tasks.size, size, password != null && password.isNotEmpty())
    }

    suspend fun restore(source: Uri, password: CharArray?): BackupSummary = withContext(Dispatchers.IO) {
        val raw = File(context.cacheDir, "restore-${System.currentTimeMillis()}.bin")
        context.contentResolver.openInputStream(source)?.use { input -> raw.outputStream().use { input.copyTo(it) } } ?: throw IOException("Cannot read backup")
        val zip = if (BackupCrypto.isEncrypted(raw)) {
            if (password == null || password.isEmpty()) throw IOException("This backup is encrypted: enter its password")
            File(context.cacheDir, raw.name + ".zip").also { z -> z.outputStream().use { BackupCrypto.decrypt(raw, it, password) } }
        } else raw
        val staging = File(context.cacheDir, "restore-staging-${System.currentTimeMillis()}")
        zip.inputStream().use { ZipSafety.extract(it, staging) }
        val manifestFile = File(staging, "gravity-backup.json")
        if (!manifestFile.isFile) throw IOException("Not a Gravity backup (manifest missing)")
        val manifest = JSONObject(manifestFile.readText())
        if (manifest.optString("format") != "gravity-backup") throw IOException("Unknown backup format")

        var projectCount = 0
        val projects = manifest.optJSONArray("projects") ?: JSONArray()
        for (i in 0 until projects.length()) {
            val pj = projects.getJSONObject(i)
            val id = pj.getString("id")
            val src = File(staging, "workspaces/$id")
            val existing = db.projects().get(id)
            val targetId = if (existing != null) java.util.UUID.randomUUID().toString() else id
            val dest = File(workspace.workspacesRoot, "${pj.getString("name").replace(Regex("[^A-Za-z0-9._-]"), "_")}-restored-${targetId.take(8)}")
            if (src.isDirectory) src.copyRecursively(dest, overwrite = false) else dest.mkdirs()
            db.projects().upsert(projectFromJson(pj).copy(id = targetId, rootPath = dest.absolutePath, name = if (existing != null) pj.getString("name") + " (restored)" else pj.getString("name")))
            projectCount++
        }
        var chatCount = 0
        var msgCount = 0
        val chats = manifest.optJSONArray("chats") ?: JSONArray()
        for (i in 0 until chats.length()) {
            val cj = chats.getJSONObject(i)
            if (db.chats().get(cj.getString("id")) != null) continue
            db.chats().upsert(chatFromJson(cj))
            val msgs = cj.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until msgs.length()) {
                db.chats().insertMessage(messageFromJson(msgs.getJSONObject(j)))
                msgCount++
            }
            chatCount++
        }
        val teams = manifest.optJSONArray("teams") ?: JSONArray()
        for (i in 0 until teams.length()) {
            val t = teamFromJson(teams.getJSONObject(i))
            if (db.teams().get(t.id) == null) db.teams().upsert(t)
        }
        var taskCount = 0
        val tasks = manifest.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until tasks.length()) {
            val t = taskFromJson(tasks.getJSONObject(i))
            if (db.tasks().get(t.id) != null || db.projects().get(t.projectId) == null) continue
            val status = if (t.status in setOf("RUNNING", "WAITING_APPROVAL", "PENDING")) "INTERRUPTED" else t.status
            db.tasks().upsert(t.copy(status = status))
            taskCount++
        }
        staging.deleteRecursively()
        raw.delete()
        if (zip != raw) zip.delete()
        events.log(EventCategory.SYSTEM, "Backup restored", "$projectCount projects, $chatCount chats, $msgCount messages, $taskCount tasks")
        BackupSummary(projectCount, chatCount, msgCount, taskCount, 0, password != null)
    }

    // --- JSON mapping -------------------------------------------------------------------

    private fun projectJson(p: ProjectEntity) = JSONObject()
        .put("id", p.id).put("name", p.name).put("type", p.type).put("createdAt", p.createdAt).put("updatedAt", p.updatedAt)
        .put("pinned", p.pinned).put("favorite", p.favorite).put("archived", p.archived).put("localOnly", p.localOnly)
        .put("buildCommand", p.buildCommand ?: "").put("artifactPattern", p.artifactPattern ?: "").put("testCommand", p.testCommand ?: "")
        .put("buildBackend", p.buildBackend ?: "")

    private fun projectFromJson(o: JSONObject) = ProjectEntity(
        id = o.getString("id"), name = o.getString("name"), type = o.optString("type", "GENERIC"), rootPath = "",
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"), pinned = o.optBoolean("pinned"),
        favorite = o.optBoolean("favorite"), archived = o.optBoolean("archived"), localOnly = o.optBoolean("localOnly"),
        buildCommand = o.optString("buildCommand").ifEmpty { null }, artifactPattern = o.optString("artifactPattern").ifEmpty { null },
        testCommand = o.optString("testCommand").ifEmpty { null },
        buildBackend = o.optString("buildBackend").ifEmpty { null },
    )

    private fun chatJson(c: ChatEntity) = JSONObject()
        .put("id", c.id).put("title", c.title).put("projectId", c.projectId ?: "").put("providerId", c.providerId ?: "")
        .put("model", c.model ?: "").put("reasoning", c.reasoning).put("pinned", c.pinned).put("archived", c.archived)
        .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)

    private fun chatFromJson(o: JSONObject) = ChatEntity(
        id = o.getString("id"), title = o.getString("title"), projectId = o.optString("projectId").ifEmpty { null },
        providerId = o.optString("providerId").ifEmpty { null }, model = o.optString("model").ifEmpty { null },
        reasoning = o.optString("reasoning", "BALANCED"), pinned = o.optBoolean("pinned"), archived = o.optBoolean("archived"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )

    private fun messageJson(m: MessageEntity) = JSONObject()
        .put("chatId", m.chatId).put("role", m.role).put("content", m.content).put("attachments", m.attachments)
        .put("model", m.model ?: "").put("inputTokens", m.inputTokens).put("outputTokens", m.outputTokens)
        .put("sources", m.sources).put("createdAt", m.createdAt)

    private fun messageFromJson(o: JSONObject) = MessageEntity(
        chatId = o.getString("chatId"), role = o.getString("role"), content = o.optString("content"),
        attachments = o.optString("attachments", "[]"), model = o.optString("model").ifEmpty { null },
        inputTokens = o.optInt("inputTokens"), outputTokens = o.optInt("outputTokens"), sources = o.optString("sources", "[]"),
        createdAt = o.optLong("createdAt"),
    )

    private fun taskJson(t: AgentTaskEntity) = JSONObject()
        .put("id", t.id).put("projectId", t.projectId).put("title", t.title).put("instruction", t.instruction)
        .put("status", t.status).put("providerId", t.providerId ?: "").put("model", t.model ?: "").put("reasoning", t.reasoning)
        .put("teamId", t.teamId ?: "").put("conversation", t.conversation).put("plan", t.plan ?: "").put("summary", t.summary ?: "")
        .put("stepCount", t.stepCount).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    private fun taskFromJson(o: JSONObject) = AgentTaskEntity(
        id = o.getString("id"), projectId = o.getString("projectId"), title = o.getString("title"), instruction = o.getString("instruction"),
        status = o.getString("status"), providerId = o.optString("providerId").ifEmpty { null }, model = o.optString("model").ifEmpty { null },
        reasoning = o.optString("reasoning", "BALANCED"), teamId = o.optString("teamId").ifEmpty { null }, conversation = o.optString("conversation", "[]"),
        plan = o.optString("plan").ifEmpty { null }, summary = o.optString("summary").ifEmpty { null }, stepCount = o.optInt("stepCount"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )

    private fun teamJson(t: TeamEntity) = JSONObject().put("id", t.id).put("name", t.name).put("members", t.members)
        .put("reviewRounds", t.reviewRounds).put("runTests", t.runTests).put("createdAt", t.createdAt)

    private fun teamFromJson(o: JSONObject) = TeamEntity(
        id = o.getString("id"), name = o.getString("name"), members = o.optString("members", "[]"),
        reviewRounds = o.optInt("reviewRounds", 1), runTests = o.optBoolean("runTests", true), createdAt = o.optLong("createdAt"),
    )
}
