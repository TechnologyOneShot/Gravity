package com.gravity.ide.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LogLine(val raw: String, val level: Char, val tag: String, val pid: Int?)

data class CrashReport(val packageName: String?, val exception: String, val stackTrace: String)

/**
 * Reads the real device log with the `logcat` binary. Without READ_LOGS (grantable only via
 * adb) Android returns Gravity's own log lines only; the UI says so.
 */
class LogcatReader {

    private val threadtime = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+([VDIWEFA])\s+([^:]*?)\s*:\s?(.*)$""")

    suspend fun dump(lines: Int = 1000, since: Long? = null, filter: String? = null): List<LogLine> = withContext(Dispatchers.IO) {
        val args = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (since != null) {
            args += listOf("-T", SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(since)))
        } else {
            args += listOf("-t", lines.toString())
        }
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(15, TimeUnit.SECONDS)
        text.lineSequence()
            .filter { it.isNotBlank() }
            .filter { filter.isNullOrBlank() || it.contains(filter, ignoreCase = true) }
            .map { raw ->
                val m = threadtime.find(raw)
                if (m != null) LogLine(raw, m.groupValues[2][0], m.groupValues[3], m.groupValues[1].toIntOrNull()) else LogLine(raw, 'I', "", null)
            }
            .toList()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ProcessBuilder("logcat", "-c").start().waitFor(5, TimeUnit.SECONDS)
    }

    /** Finds "FATAL EXCEPTION" blocks (AndroidRuntime) in real log output. */
    fun findCrashes(lines: List<LogLine>, packageName: String? = null): List<CrashReport> {
        val out = mutableListOf<CrashReport>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.tag == "AndroidRuntime" && l.raw.contains("FATAL EXCEPTION")) {
                val block = StringBuilder()
                var pkg: String? = null
                var exception = ""
                var j = i
                while (j < lines.size && lines[j].tag == "AndroidRuntime") {
                    val msg = lines[j].raw.substringAfter("AndroidRuntime:", lines[j].raw).trim()
                    block.append(msg).append('\n')
                    if (msg.startsWith("Process:")) pkg = msg.removePrefix("Process:").substringBefore(',').trim()
                    if (exception.isEmpty() && (msg.contains("Exception") || msg.contains("Error")) && !msg.startsWith("FATAL")) exception = msg
                    j++
                }
                if (packageName == null || pkg == packageName) out += CrashReport(pkg, exception.ifEmpty { "Fatal exception" }, block.toString())
                i = j
            } else {
                i++
            }
        }
        return out
    }
}
