package com.gravity.ide.core

import java.io.File

/**
 * Keeps every agent / tool file operation inside a project root.
 * Paths are canonicalised, so `..` segments and symlinks that escape the root are rejected.
 */
object PathGuard {

    class PathEscapeException(message: String) : SecurityException(message)

    fun resolveInside(root: File, path: String): File {
        val rootCanon = root.canonicalFile
        val trimmed = path.trim()
        val candidate = when {
            trimmed.isEmpty() || trimmed == "." || trimmed == "/" -> rootCanon
            File(trimmed).isAbsolute && isInside(rootCanon, File(trimmed).canonicalFile) -> File(trimmed)
            else -> File(rootCanon, trimmed.trimStart('/'))
        }.canonicalFile
        if (!isInside(rootCanon, candidate)) {
            throw PathEscapeException("Path '$path' resolves outside the project root")
        }
        return candidate
    }

    fun isInside(root: File, file: File): Boolean {
        val r = root.canonicalFile.path
        val f = file.canonicalFile.path
        return f == r || f.startsWith(r + File.separator)
    }

    fun relativize(root: File, file: File): String {
        val r = root.canonicalFile.path
        val f = file.canonicalFile.path
        return when {
            f == r -> "."
            f.startsWith(r + File.separator) -> f.substring(r.length + 1)
            else -> f
        }
    }

    /** A file name for create/rename: no separators, not empty, not `.`/`..`. */
    fun validateName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty()) { "Name must not be empty" }
        require(n != "." && n != "..") { "Invalid name" }
        require(!n.contains('/') && !n.contains('\\') && !n.contains('\u0000')) { "Name must not contain path separators" }
        require(n.length <= 255) { "Name is too long" }
        return n
    }
}
