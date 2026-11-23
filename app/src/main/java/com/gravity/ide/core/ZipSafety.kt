package com.gravity.ide.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ZIP import/export that refuses path traversal ("zip slip") and decompression bombs.
 */
object ZipSafety {

    data class Limits(
        val maxEntries: Int = 50_000,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxEntryBytes: Long = 1L * 1024 * 1024 * 1024,
    )

    /**
     * [stoppedEarly] is true when the platform itself refused an entry (Android 14+ throws
     * on "../" names while reading the archive), so the remaining entries were not read.
     */
    data class ExtractResult(val files: Int, val directories: Int, val bytes: Long, val rejected: List<String>, val stoppedEarly: Boolean = false)

    class ZipLimitException(message: String) : IOException(message)

    fun extract(input: InputStream, destDir: File, limits: Limits = Limits()): ExtractResult {
        destDir.mkdirs()
        val root = destDir.canonicalFile
        var files = 0
        var dirs = 0
        var total = 0L
        var entries = 0
        val rejected = mutableListOf<String>()
        val buffer = ByteArray(64 * 1024)
        var stoppedEarly = false
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry: ZipEntry = try {
                    zis.nextEntry ?: break
                } catch (e: java.util.zip.ZipException) {
                    // Android 14+ (targetSdk 34+) validates entry names inside ZipInputStream and
                    // throws for traversal paths. The stream cannot be read past that point, so
                    // record the entry as rejected and stop; nothing was written outside destDir.
                    val msg = e.message.orEmpty()
                    if (!msg.startsWith("Invalid zip entry path")) throw e
                    rejected += msg.substringAfter(": ", msg)
                    stoppedEarly = true
                    break
                }
                entries++
                if (entries > limits.maxEntries) throw ZipLimitException("Archive has more than ${limits.maxEntries} entries")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
                    rejected += name
                    continue
                }
                val target = File(root, name).canonicalFile
                if (!PathGuard.isInside(root, target) || target == root) {
                    rejected += name
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    dirs++
                    continue
                }
                target.parentFile?.mkdirs()
                var entryBytes = 0L
                FileOutputStream(target).use { out ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > limits.maxEntryBytes) throw ZipLimitException("Entry '$name' exceeds the per-file limit")
                        if (total > limits.maxTotalBytes) throw ZipLimitException("Archive exceeds the total size limit")
                        out.write(buffer, 0, read)
                    }
                }
                files++
            }
        }
        return ExtractResult(files, dirs, total, rejected, stoppedEarly)
    }

    /** Zips [dir] into [output]. Paths inside the zip are relative to [dir]. */
    fun zipDirectory(
        dir: File,
        output: OutputStream,
        exclude: (relativePath: String, file: File) -> Boolean = { _, _ -> false },
        prefix: String = "",
    ): Int {
        var count = 0
        val root = dir.canonicalFile
        ZipOutputStream(output).use { zos ->
            count = addTree(zos, root, root, exclude, prefix)
        }
        return count
    }

    fun addTree(
        zos: ZipOutputStream,
        root: File,
        current: File,
        exclude: (String, File) -> Boolean,
        prefix: String,
    ): Int {
        var count = 0
        val children = current.listFiles()?.sortedBy { it.name } ?: return 0
        for (child in children) {
            val rel = PathGuard.relativize(root, child).replace(File.separatorChar, '/')
            if (exclude(rel, child)) continue
            // Never follow symlinks that leave the tree.
            if (!PathGuard.isInside(root, child)) continue
            if (child.isDirectory) {
                zos.putNextEntry(ZipEntry("$prefix$rel/"))
                zos.closeEntry()
                count += addTree(zos, root, child, exclude, prefix)
            } else {
                val entry = ZipEntry("$prefix$rel")
                entry.time = child.lastModified()
                zos.putNextEntry(entry)
                FileInputStream(child).use { it.copyTo(zos, 64 * 1024) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    private val EXCLUDED_SEGMENTS = setOf(".gradle", "build", "node_modules", ".cxx", ".idea", ".externalNativeBuild")

    val NO_EXCLUDES: (String, File) -> Boolean = { _, _ -> false }

    /** Source exports skip build outputs and dependency caches. */
    val DEFAULT_EXCLUDES: (String, File) -> Boolean = { rel, _ ->
        rel.split('/').any { it in EXCLUDED_SEGMENTS }
    }
}
