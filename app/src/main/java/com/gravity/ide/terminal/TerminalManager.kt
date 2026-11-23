package com.gravity.ide.terminal

import android.content.Context
import com.gravity.ide.data.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Owns terminal tabs (they survive screen changes) and short-lived agent shells. */
class TerminalManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val events: EventLog,
) {
    private val nextId = AtomicInteger(1)
    private val _sessions = MutableStateFlow<List<ShellSession>>(emptyList())
    val sessions: StateFlow<List<ShellSession>> = _sessions.asStateFlow()

    val homeDir: File = File(context.filesDir, "home").apply { mkdirs() }

    fun baseEnv(projectRoot: File? = null): Map<String, String> = buildMap {
        put("HOME", homeDir.absolutePath)
        put("TMPDIR", context.cacheDir.absolutePath)
        put("TERM", "dumb")
        put("GRAVITY", "1")
        if (projectRoot != null) put("GRAVITY_PROJECT", projectRoot.absolutePath)
        System.getenv("PATH")?.let { put("PATH", it) }
    }

    fun open(cwd: File, title: String? = null): ShellSession {
        val id = nextId.getAndIncrement()
        val session = ShellSession(id, title ?: "Shell $id", cwd, baseEnv(cwd), scope, events)
        session.start()
        _sessions.value = _sessions.value + session
        return session
    }

    fun close(id: Int) {
        _sessions.value.firstOrNull { it.id == id }?.close()
        _sessions.value = _sessions.value.filterNot { it.id == id }
    }

    /** A dedicated shell for an agent/build run, not shown as a tab unless requested. */
    fun ephemeral(cwd: File, title: String): ShellSession =
        ShellSession(nextId.getAndIncrement(), title, cwd, baseEnv(cwd), scope, events).also { it.start() }
}
