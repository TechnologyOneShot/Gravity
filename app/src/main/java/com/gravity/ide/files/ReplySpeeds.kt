package com.gravity.ide.files

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Speed lines of phone-model replies by message id (the last 300), kept in a small file of their
 * own (the chat database is unchanged). [changes] ticks when one is added, for the chat screen.
 */
class ReplySpeeds(dir: File) {
    private val file = File(dir, "reply_speeds.txt")
    private val lines = LinkedHashMap<Long, String>()
    private var loaded = false
    val changes = MutableStateFlow(0)

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.isFile) file.readLines().forEach { l ->
                val tab = l.indexOf('\t')
                if (tab > 0) l.substring(0, tab).toLongOrNull()?.let { lines[it] = l.substring(tab + 1) }
            }
        }
    }

    @Synchronized
    fun get(messageId: Long): String? {
        load()
        return lines[messageId]
    }

    @Synchronized
    fun put(messageId: Long, line: String) {
        load()
        lines[messageId] = line.replace('\n', ' ').replace('\t', ' ')
        while (lines.size > 300) lines.remove(lines.keys.first())
        runCatching { file.writeText(lines.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        changes.value++
    }
}
