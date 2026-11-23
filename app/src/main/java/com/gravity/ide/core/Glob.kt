package com.gravity.ide.core

import java.io.File

/** Minimal glob for artifact patterns: `**` any depth, `*` within a segment, `?` one char. */
object Glob {
    fun toRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        val p = pattern.replace('\\', '/').trimStart('/')
        while (i < p.length) {
            val c = p[i]
            when {
                c == '*' && i + 1 < p.length && p[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < p.length && p[i] == '/') i++
                    continue
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                "\\.[]{}()+-^$|".indexOf(c) >= 0 -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString())
    }

    fun find(root: File, pattern: String, limit: Int = 50): List<File> {
        val regex = toRegex(pattern)
        return root.walkTopDown()
            .onEnter { it == root || (it.name != ".git" && it.name != "node_modules") }
            .filter { it.isFile && regex.matches(PathGuard.relativize(root, it).replace(File.separatorChar, '/')) }
            .take(limit)
            .toList()
    }
}
