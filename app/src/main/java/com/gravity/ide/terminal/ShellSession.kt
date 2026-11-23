package com.gravity.ide.terminal

import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

enum class LineKind { STDOUT, STDERR, INPUT, SYSTEM }

data class TermLine(val kind: LineKind, val text: String)

data class CommandResult(val command: String, val exitCode: Int, val output: String, val timedOut: Boolean, val durationMs: Long)

/**
 * A real `/system/bin/sh` process. Commands are written to the shell's stdin followed by a
 * unique end marker that reports the exit code and working directory, so every command
 * gets its actual exit status. Output is never synthesised.
 *
 * Android limits (documented in the UI): this is the device's toybox/mksh shell running
 * with the app's own UID and SELinux domain — no root, no adb privileges, and binaries in
 * app storage cannot be executed directly on Android 10+.
 */
class ShellSession(
    val id: Int,
    val title: String,
    initialCwd: File,
    private val baseEnv: Map<String, String>,
    private val scope: CoroutineScope,
    private val events: EventLog,
    private val maxLines: Int = 5000,
) {
    private val _lines = MutableStateFlow<List<TermLine>>(emptyList())
    val lines: StateFlow<List<TermLine>> = _lines.asStateFlow()

    private val _cwd = MutableStateFlow(initialCwd.absolutePath)
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _running = MutableStateFlow<String?>(null)
    /** The command currently executing, or null when idle. */
    val running: StateFlow<String?> = _running.asStateFlow()

    private val _alive = MutableStateFlow(false)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    val history = ArrayList<String>()
    val env = LinkedHashMap(baseEnv)

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private val readers = ArrayList<Job>()
    private val commandMutex = Mutex()
    private val lock = Any()

    @Volatile private var pending: Pending? = null

    private class Pending(val token: String, val command: String, val startedAt: Long) {
        val output = StringBuilder()
        val done = CompletableDeferred<CommandResult>()
    }

    fun start() {
        synchronized(lock) {
            if (process?.isAlive == true) return
            val dir = File(_cwd.value).takeIf { it.isDirectory } ?: File("/")
            val pb = ProcessBuilder("/system/bin/sh").directory(dir).redirectErrorStream(false)
            pb.environment().putAll(env)
            val p = pb.start()
            process = p
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            _alive.value = true
            readers += scope.launch(Dispatchers.IO) { pump(BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)), LineKind.STDOUT) }
            readers += scope.launch(Dispatchers.IO) { pump(BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)), LineKind.STDERR) }
            scope.launch(Dispatchers.IO) {
                val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
                if (process === p) {
                    _alive.value = false
                    append(TermLine(LineKind.SYSTEM, "[shell exited with code $code]"))
                    pending?.let { pend ->
                        pend.done.complete(CommandResult(pend.command, code, pend.output.toString(), false, System.currentTimeMillis() - pend.startedAt))
                        pending = null
                        _running.value = null
                    }
                }
            }
        }
    }

    private fun pump(reader: BufferedReader, kind: LineKind) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (kind == LineKind.STDOUT) {
                    val markerIdx = line.indexOf(MARKER_PREFIX)
                    val pend = pending
                    if (markerIdx >= 0 && pend != null && line.startsWith(MARKER_PREFIX + pend.token, markerIdx)) {
                        val before = line.substring(0, markerIdx)
                        if (before.isNotEmpty()) {
                            append(TermLine(kind, before))
                            pend.output.append(before).append('\n')
                        }
                        val rest = line.substring(markerIdx + MARKER_PREFIX.length + pend.token.length + 1)
                        val code = rest.substringBefore(':').toIntOrNull() ?: -1
                        val dir = rest.substringAfter(':', "")
                        if (dir.isNotEmpty()) _cwd.value = dir
                        pending = null
                        _running.value = null
                        pend.done.complete(CommandResult(pend.command, code, pend.output.toString().trimEnd('\n'), false, System.currentTimeMillis() - pend.startedAt))
                        if (code != 0) append(TermLine(LineKind.SYSTEM, "[exit $code]"))
                        continue
                    }
                }
                append(TermLine(kind, line))
                pending?.output?.append(line)?.append('\n')
            }
        } catch (_: Exception) {
            // Stream closed when the process is destroyed.
        }
    }

    private fun append(line: TermLine) {
        // Called from both the stdout and stderr pump threads: update atomically.
        _lines.update { current ->
            if (current.size >= maxLines) current.drop(current.size - maxLines + 1) + line else current + line
        }
    }

    /** Runs a command and waits for its real exit code. */
    suspend fun run(command: String, timeoutMs: Long = 120_000, echo: Boolean = true): CommandResult = commandMutex.withLock {
        start()
        val token = UUID.randomUUID().toString().replace("-", "").take(16)
        val pend = Pending(token, command, System.currentTimeMillis())
        pending = pend
        _running.value = command
        if (echo) append(TermLine(LineKind.INPUT, "$ $command"))
        history.remove(command)
        history.add(command)
        // The marker line is printed by the shell itself after the command finishes.
        val script = "$command\nprintf '$MARKER_PREFIX%s:%s:%s\\n' '$token' \"\$?\" \"\$(pwd)\"\n"
        try {
            val w = writer ?: throw IllegalStateException("Shell is not running")
            w.write(script)
            w.flush()
        } catch (e: Exception) {
            pending = null
            _running.value = null
            restart()
            return@withLock CommandResult(command, -1, "Failed to write to shell: ${e.message}", false, 0)
        }
        try {
            val result = withTimeout(timeoutMs) { pend.done.await() }
            events.log(EventCategory.TERMINAL, "$ ${command.take(120)}", "exit ${result.exitCode} in ${result.durationMs} ms\n${result.output.takeLast(4000)}")
            result
        } catch (e: TimeoutCancellationException) {
            val out = pend.output.toString()
            pending = null
            _running.value = null
            append(TermLine(LineKind.SYSTEM, "[timed out after ${timeoutMs / 1000}s — shell restarted]"))
            restart()
            events.log(EventCategory.TERMINAL, "Timed out: ${command.take(120)}", out.takeLast(4000))
            CommandResult(command, -1, out, true, timeoutMs)
        }
    }

    /** UI entry point: fire-and-forget. */
    fun submit(command: String) {
        if (command.isBlank()) return
        if (_running.value != null) {
            sendInput(command)
            return
        }
        scope.launch { run(command, timeoutMs = 24L * 3600 * 1000) }
    }

    /** Sends a line to the running program's stdin (interactive programs). */
    fun sendInput(text: String) {
        append(TermLine(LineKind.INPUT, text))
        try {
            writer?.apply { write(text + "\n"); flush() }
        } catch (_: Exception) {
        }
    }

    /**
     * Stops the running command. mksh cannot interrupt a foreground child without a TTY,
     * so the whole shell is destroyed and restarted in the same directory with the same env.
     */
    fun stop() {
        val pend = pending
        restart()
        append(TermLine(LineKind.SYSTEM, "[stopped — shell restarted in ${_cwd.value}]"))
        pend?.done?.complete(CommandResult(pend.command, 130, pend.output.toString(), false, System.currentTimeMillis() - pend.startedAt))
        pending = null
        _running.value = null
    }

    private fun restart() {
        synchronized(lock) {
            val p = process
            process = null
            readers.forEach { it.cancel() }
            readers.clear()
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            p?.destroy()
            if (p?.isAlive == true) p.destroyForcibly()
        }
        _alive.value = false
        start()
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun setCwd(dir: File) {
        if (dir.isDirectory) {
            _cwd.value = dir.absolutePath
            if (process?.isAlive == true) submit("cd '${dir.absolutePath.replace("'", "'\\''")}'")
        }
    }

    fun close() {
        synchronized(lock) {
            readers.forEach { it.cancel() }
            try { writer?.close() } catch (_: Exception) {}
            process?.destroy()
            process = null
        }
        _alive.value = false
    }

    fun transcript(): String = _lines.value.joinToString("\n") { it.text }

    companion object {
        const val MARKER_PREFIX = "__GRAVITY_END_"
    }
}
