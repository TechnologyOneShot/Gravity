package com.gravity.ide.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gravity.ide.core.PathGuard
import com.gravity.ide.core.ZipSafety
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.db.TrashEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FileNode(
    val file: File,
    val name: String,
    val relPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val childCount: Int,
)

data class SearchHit(val relPath: String, val line: Int, val text: String)

enum class SortMode { NAME, SIZE, MODIFIED, TYPE }

/**
 * Real filesystem workspace. Projects live in app-private storage so the terminal and the
 * agent can use ordinary file paths; Storage Access Framework folders are imported
 * (copied in) and can be exported back. Deletes go to a recoverable trash.
 */
class WorkspaceManager(
    private val context: Context,
    private val db: GravityDatabase,
    private val events: EventLog,
) {
    val workspacesRoot: File = File(context.filesDir, "workspaces").apply { mkdirs() }
    private val trashRoot: File = File(context.filesDir, "trash").apply { mkdirs() }
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /** Coordinates concurrent writers (user editor, parallel agents) per file. */
    suspend fun <T> withFileLock(file: File, block: suspend () -> T): T {
        val mutex = fileLocks.getOrPut(file.canonicalPath) { Mutex() }
        return mutex.withLock { block() }
    }

    fun projectRoot(project: ProjectEntity): File = File(project.rootPath)

    suspend fun createProject(name: String, template: ProjectTemplate): ProjectEntity = withContext(Dispatchers.IO) {
        val clean = PathGuard.validateName(name)
        val id = UUID.randomUUID().toString()
        val dirName = clean.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + id.take(8)
        val root = File(workspacesRoot, dirName)
        if (!root.mkdirs()) throw IOException("Could not create ${root.path}")
        template.write(root, clean)
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = id,
            name = clean,
            type = template.type,
            rootPath = root.absolutePath,
            createdAt = now,
            updatedAt = now,
            lastOpenedAt = now,
            buildCommand = template.buildCommand,
            artifactPattern = template.artifactPattern,
            testCommand = template.testCommand,
        )
        db.projects().upsert(project)
        events.log(EventCategory.WORKSPACE, "Created project $clean", "Template: ${template.label}\nPath: ${root.path}", projectId = id)
        project
    }

    suspend fun touch(project: ProjectEntity) {
        db.projects().upsert(project.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    fun list(dir: File, root: File, showHidden: Boolean, sort: SortMode = SortMode.NAME): List<FileNode> {
        val children = dir.listFiles()?.toList() ?: emptyList()
        val nodes = children
            .filter { showHidden || !it.name.startsWith(".") }
            .map {
                FileNode(
                    file = it,
                    name = it.name,
                    relPath = PathGuard.relativize(root, it),
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0,
                    lastModified = it.lastModified(),
                    childCount = if (it.isDirectory) it.list()?.size ?: 0 else 0,
                )
            }
        val dirsFirst = compareByDescending<FileNode> { it.isDirectory }
        return nodes.sortedWith(
            when (sort) {
                SortMode.NAME -> dirsFirst.thenBy { it.name.lowercase() }
                SortMode.SIZE -> dirsFirst.thenByDescending { it.size }
                SortMode.MODIFIED -> dirsFirst.thenByDescending { it.lastModified }
                SortMode.TYPE -> dirsFirst.thenBy { it.name.substringAfterLast('.', "") }.thenBy { it.name.lowercase() }
            }
        )
    }

    fun createFile(dir: File, name: String, content: String = ""): File {
        val f = File(dir, PathGuard.validateName(name))
        if (f.exists()) throw IOException("${f.name} already exists")
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    fun createDirectory(dir: File, name: String): File {
        val f = File(dir, PathGuard.validateName(name))
        if (f.exists()) throw IOException("${f.name} already exists")
        if (!f.mkdirs()) throw IOException("Could not create ${f.name}")
        return f
    }

    fun rename(file: File, newName: String): File {
        val target = File(file.parentFile, PathGuard.validateName(newName))
        if (target.exists()) throw IOException("${target.name} already exists")
        if (!file.renameTo(target)) throw IOException("Rename failed")
        return target
    }

    /** Moves into the trash; returns the trash record id so the delete can be undone. */
    suspend fun delete(file: File, projectId: String?): Long = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IOException("${file.name} does not exist")
        val dest = File(trashRoot, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_${file.name}")
        val isDir = file.isDirectory
        if (!file.renameTo(dest)) {
            // Different filesystem (unlikely inside filesDir): copy then delete.
            file.copyRecursively(dest, overwrite = false)
            file.deleteRecursively()
        }
        val id = db.trash().insert(
            TrashEntity(originalPath = file.absolutePath, trashPath = dest.absolutePath, projectId = projectId, isDirectory = isDir, deletedAt = System.currentTimeMillis())
        )
        events.log(EventCategory.WORKSPACE, "Moved to trash: ${file.name}", file.absolutePath, projectId = projectId)
        id
    }

    suspend fun restoreFromTrash(trashId: Long): File = withContext(Dispatchers.IO) {
        val item = db.trash().get(trashId) ?: throw IOException("Trash item not found")
        val src = File(item.trashPath)
        var dest = File(item.originalPath)
        if (dest.exists()) dest = File(dest.parentFile, "${dest.nameWithoutExtension}_restored${if (dest.extension.isNotEmpty()) "." + dest.extension else ""}")
        dest.parentFile?.mkdirs()
        if (!src.renameTo(dest)) {
            src.copyRecursively(dest, overwrite = false)
            src.deleteRecursively()
        }
        db.trash().delete(trashId)
        events.log(EventCategory.WORKSPACE, "Restored ${dest.name} from trash", dest.absolutePath, projectId = item.projectId)
        dest
    }

    suspend fun purgeTrash(trashId: Long) = withContext(Dispatchers.IO) {
        val item = db.trash().get(trashId) ?: return@withContext
        File(item.trashPath).deleteRecursively()
        db.trash().delete(trashId)
    }

    fun copy(src: File, destDir: File): File {
        var target = File(destDir, src.name)
        if (target.canonicalPath == src.canonicalPath || target.exists()) target = uniqueSibling(target)
        if (src.isDirectory) {
            if (PathGuard.isInside(src, destDir)) throw IOException("Cannot copy a folder into itself")
            src.copyRecursively(target, overwrite = false)
        } else {
            src.copyTo(target, overwrite = false)
        }
        return target
    }

    fun move(src: File, destDir: File): File {
        if (src.isDirectory && PathGuard.isInside(src, destDir)) throw IOException("Cannot move a folder into itself")
        val target = File(destDir, src.name)
        if (target.exists()) throw IOException("${target.name} already exists in destination")
        if (!src.renameTo(target)) {
            src.copyRecursively(target, overwrite = false)
            src.deleteRecursively()
        }
        return target
    }

    private fun uniqueSibling(f: File): File {
        val base = f.nameWithoutExtension
        val ext = if (f.extension.isNotEmpty()) "." + f.extension else ""
        var i = 1
        var candidate: File
        do {
            candidate = File(f.parentFile, "$base copy${if (i > 1) " $i" else ""}$ext")
            i++
        } while (candidate.exists())
        return candidate
    }

    fun isProbablyText(file: File): Boolean {
        if (file.length() == 0L) return true
        val head = ByteArray(minOf(4096L, file.length()).toInt())
        file.inputStream().use { it.read(head) }
        return head.none { it == 0.toByte() }
    }

    fun search(
        root: File,
        query: String,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        maxResults: Int = 300,
        fileFilter: (File) -> Boolean = { true },
    ): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val pattern = if (regex) {
            Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } else {
            Regex(Regex.escape(query), if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }
        val hits = ArrayList<SearchHit>()
        val skipDirs = setOf(".git", ".gradle", "build", "node_modules", ".cxx", ".idea")
        root.walkTopDown()
            .onEnter { it == root || it.name !in skipDirs }
            .filter { it.isFile && it.length() < 2_000_000 && fileFilter(it) }
            .forEach { f ->
                if (hits.size >= maxResults) return@forEach
                if (!isProbablyText(f)) return@forEach
                var lineNo = 0
                f.useLines { lines ->
                    for (line in lines) {
                        lineNo++
                        if (pattern.containsMatchIn(line)) {
                            hits += SearchHit(PathGuard.relativize(root, f), lineNo, line.trim().take(300))
                            if (hits.size >= maxResults) break
                        }
                    }
                }
            }
        return hits
    }

    /** Compact tree listing used as agent context. */
    fun treeSummary(root: File, maxEntries: Int = 400): String {
        val sb = StringBuilder()
        var count = 0
        val skipDirs = setOf(".git", ".gradle", "build", "node_modules", ".cxx", ".idea")
        root.walkTopDown()
            .onEnter { it == root || it.name !in skipDirs }
            .forEach { f ->
                if (f == root || count >= maxEntries) return@forEach
                val rel = PathGuard.relativize(root, f)
                val depth = rel.count { it == File.separatorChar }
                sb.append("  ".repeat(depth)).append(f.name)
                if (f.isDirectory) sb.append('/') else sb.append(" (").append(f.length()).append(" B)")
                sb.append('\n')
                count++
            }
        if (count >= maxEntries) sb.append("… (truncated at $maxEntries entries)\n")
        return sb.toString()
    }

    suspend fun importZip(uri: Uri, destDir: File): ZipSafety.ExtractResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open archive")
        val result = input.use { ZipSafety.extract(it, destDir) }
        events.log(
            EventCategory.WORKSPACE, "Extracted ZIP into ${destDir.name}",
            "${result.files} files, ${result.bytes} bytes" + if (result.rejected.isNotEmpty()) "\nRejected unsafe entries: ${result.rejected.joinToString()}" else ""
        )
        result
    }

    suspend fun exportZip(dir: File, uri: Uri, includeBuildOutputs: Boolean = false): Int = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open destination")
        out.use {
            ZipSafety.zipDirectory(dir, it, exclude = if (includeBuildOutputs) ZipSafety.NO_EXCLUDES else ZipSafety.DEFAULT_EXCLUDES)
        }
    }

    suspend fun zipToFile(dir: File, dest: File, includeBuildOutputs: Boolean = false): Int = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        dest.outputStream().use {
            ZipSafety.zipDirectory(dir, it, exclude = if (includeBuildOutputs) ZipSafety.NO_EXCLUDES else ZipSafety.DEFAULT_EXCLUDES)
        }
    }

    /** Copies a SAF tree into [destDir] (Android only exposes user folders through content URIs). */
    suspend fun importTree(treeUri: Uri, destDir: File): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Folder is not accessible")
        destDir.mkdirs()
        copyFromDocument(root, destDir)
    }

    private fun copyFromDocument(doc: DocumentFile, dest: File): Int {
        var count = 0
        for (child in doc.listFiles()) {
            val name = child.name ?: continue
            val safeName = try { PathGuard.validateName(name) } catch (e: IllegalArgumentException) { continue }
            val target = File(dest, safeName)
            if (child.isDirectory) {
                target.mkdirs()
                count += copyFromDocument(child, target)
            } else {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                count++
            }
        }
        return count
    }

    /** Copies [dir] into a user-selected SAF folder. */
    suspend fun exportTree(dir: File, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Folder is not accessible")
        val target = root.findFile(dir.name) ?: root.createDirectory(dir.name) ?: throw IOException("Cannot create folder")
        copyToDocument(dir, target)
    }

    private fun copyToDocument(src: File, dest: DocumentFile): Int {
        var count = 0
        for (child in src.listFiles().orEmpty()) {
            if (child.isDirectory) {
                if (child.name in setOf("build", ".gradle", "node_modules")) continue
                val d = dest.findFile(child.name) ?: dest.createDirectory(child.name) ?: continue
                count += copyToDocument(child, d)
            } else {
                dest.findFile(child.name)?.delete()
                val f = dest.createFile("application/octet-stream", child.name) ?: continue
                context.contentResolver.openOutputStream(f.uri)?.use { out -> child.inputStream().use { it.copyTo(out) } }
                count++
            }
        }
        return count
    }

    fun storageUsage(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
