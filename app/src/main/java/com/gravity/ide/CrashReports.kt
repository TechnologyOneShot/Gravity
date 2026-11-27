package com.gravity.ide

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Why Yamini closed unexpectedly last time, told to the user on the next start (with a report
 * they can share): a crash (its stack trace is saved when it happens), a native crash, the app
 * freezing (ANR), Android closing it for memory, the phone's battery saver closing it, or closing
 * while the user was using it with no crash at all. The report stays until the user dismisses it.
 * Nothing is sent anywhere by itself.
 */
class CrashReports(private val context: Context) {
    data class Report(val title: String, val explanation: String, val details: String)

    /** The report to show (null when the last exit was normal). */
    val pending = MutableStateFlow<Report?>(null)

    private val dir = File(context.filesDir, "crash").apply { mkdirs() }
    private val lastCrash = File(dir, "last.txt")
    private val prefs = context.getSharedPreferences("crash_reports", Context.MODE_PRIVATE)

    /** Saves the stack trace of a crash as it happens, then lets Android handle it as usual. */
    fun install() {
        watchForeground()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                lastCrash.writeText(
                    "Time: ${java.util.Date()}\nThread: ${thread.name}\n${header()}\n\n" + android.util.Log.getStackTraceString(e).take(20_000)
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * Notes work in progress (a reply, a picture, loading a model): if Gravity closes during it,
     * the next start says what it was doing, even when Android gives no crash (a phone that kills
     * it for memory). Returns the id to pass to [end].
     */
    fun begin(what: String): String = synchronized(this) {
        val id = "${System.nanoTime()}|${what.take(200)}"
        prefs.edit().putStringSet(KEY_WORK, prefs.getStringSet(KEY_WORK, emptySet()).orEmpty() + id).apply()
        id
    }

    fun end(id: String) = synchronized(this) {
        prefs.edit().putStringSet(KEY_WORK, prefs.getStringSet(KEY_WORK, emptySet()).orEmpty() - id).apply()
    }

    /** Runs [block] as noted work (see [begin]). */
    suspend fun <T> during(what: String, block: suspend () -> T): T {
        val id = begin(what)
        try {
            return block()
        } finally {
            end(id)
        }
    }

    /**
     * Whether Yamini is on the screen, kept on disk: a run that ends while the user is looking at
     * it, without them closing it, is a close to report even when Android gives no crash.
     */
    private fun watchForeground() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { prefs.edit().putBoolean(KEY_FG, true).commit() }
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { prefs.edit().putBoolean(KEY_FG, false).commit() }
                })
            }
        }
    }

    /** The user saw the report (OK): it is not shown again. */
    fun dismiss() {
        pending.value = null
        keptReport.delete()
    }

    private val keptReport = File(dir, "pending.txt")

    private fun publish(r: Report?) {
        val shown = r ?: runCatching {
            keptReport.takeIf { it.isFile }?.readText()?.split("\u0000", limit = 3)?.takeIf { it.size == 3 }?.let { Report(it[0], it[1], it[2]) }
        }.getOrNull()
        if (r != null) runCatching { keptReport.writeText(r.title + "\u0000" + r.explanation + "\u0000" + r.details) }
        pending.value = shown
    }

    /** Called at start: looks at how the last run ended and prepares a report if it was not normal. */
    fun check() {
        val saved = lastCrash.takeIf { it.isFile }?.readText()
        lastCrash.delete()
        val previousPid = prefs.getInt(KEY_PID, 0)
        val wasOnScreen = prefs.getBoolean(KEY_FG, false)
        prefs.edit().putInt(KEY_PID, android.os.Process.myPid()).putBoolean(KEY_FG, false).commit()
        // What was running when it closed: noted work, and the phone-model engine's own note.
        val doing = buildList {
            prefs.getStringSet(KEY_WORK, emptySet()).orEmpty().sorted().forEach { add(it.substringAfter('|')) }
            context.getSharedPreferences("local_model_host", Context.MODE_PRIVATE).getString("native_busy", null)?.split('|')?.let { p ->
                val what = p.getOrNull(3).orEmpty().ifBlank { "working" }
                add("phone-model engine (${p.getOrNull(0) ?: "?"}): $what")
            }
        }.distinct()
        prefs.edit().remove(KEY_WORK).commit()
        val whileLine = if (doing.isEmpty()) "" else "While: " + doing.joinToString("; ") + "\n"
        if (saved != null) {
            publish(Report(
                "Yamini crashed last time",
                "A mistake in Yamini's code closed it. The report below shows exactly where; please share it so it can be fixed.",
                whileLine + saved,
            ))
            return
        }
        val noRecord = { if (doing.isNotEmpty()) reportFor(-1, null, 0, doing, whileLine + memoryLine() + header(), wasOnScreen) else null }
        if (Build.VERSION.SDK_INT < 30) {
            publish(noRecord())
            return
        }
        // Android writes the exit record once the old process is fully gone, which after a native
        // crash (while its crash dump is written) can be a few seconds after this new start.
        val wait = previousPid != 0 && (doing.isNotEmpty() || wasOnScreen)
        Thread {
            var exit = findExit(previousPid)
            var waited = 0
            while (exit == null && wait && waited < 6_000) {
                Thread.sleep(300); waited += 300
                exit = findExit(previousPid)
            }
            exit?.let { prefs.edit().putLong("seen", it.timestamp).apply() }
            publish(if (exit != null) describe(exit, doing, wasOnScreen) else noRecord())
        }.apply { isDaemon = true; name = "crash-check" }.start()
    }

    /** The exit record of the run before this one ([pid]; 0 = the newest one not seen yet). */
    private fun findExit(pid: Int): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < 30) return null
        val seen = prefs.getLong("seen", 0L)
        return runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .firstOrNull { (pid == 0 || it.pid == pid) && it.timestamp > seen }
        }.getOrNull()
    }

    /** Readable lines of a native crash dump (Android 12+ keeps it as a binary tombstone). */
    private fun nativeTrace(e: ApplicationExitInfo): String? = runCatching {
        val bytes = e.traceInputStream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < 2 * 1024 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: return null
        val lines = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 6) lines += run.toString().trim()
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
        }
        flush()
        val keys = listOf("Abort message", "signal", "SIG", "fault", "llama", "ggml", "stable", "sd_", "gravity", "yamini", "webview", "chromium", "onnx", "sherpa", ".so")
        val useful = lines.filter { l -> keys.any { l.contains(it, ignoreCase = true) } }
        (lines.take(12) + useful).distinct().take(160).joinToString("\n").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun describe(e: ApplicationExitInfo, doing: List<String>, wasOnScreen: Boolean): Report? {
        if (Build.VERSION.SDK_INT < 30) return null
        val details = buildString {
            if (doing.isNotEmpty()) append("While: ").append(doing.joinToString("; ")).append('\n')
            append("Reason: ").append(reasonName(e.reason)).append(" (").append(e.reason).append(")")
            if (e.reason == ApplicationExitInfo.REASON_SIGNALED) append(", signal ").append(e.status)
            append('\n')
            append("Description: ").append(e.description ?: "-").append('\n')
            append("Time: ").append(java.util.Date(e.timestamp)).append('\n')
            append("Memory used then: ").append(e.pss / 1024).append(" MB (PSS), ").append(e.rss / 1024).append(" MB (RSS)\n")
            append(memoryLine())
            append(header())
            append("On the screen then: ").append(if (wasOnScreen) "yes" else "no").append('\n')
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let { append("\n\n").append(it.take(15_000)) }
            }
            if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                nativeTrace(e)?.let { append("\nNative crash dump (readable parts):\n").append(it.take(15_000)) }
            }
        }
        return reportFor(e.reason, e.description, e.status, doing, details, wasOnScreen)
    }

    private fun memoryLine(): String = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        "Phone memory: ${info.totalMem / (1024 * 1024)} MB in all, ${info.availMem / (1024 * 1024)} MB available now\n"
    }.getOrDefault("")

    companion object {
        private const val KEY_WORK = "work"
        private const val KEY_PID = "pid"
        private const val KEY_FG = "on_screen"

        /**
         * The report for an exit ([reason] is an ApplicationExitInfo reason, -1 when Android gave
         * none) while Gravity was [doing] something, or null for a normal exit.
         */
        fun reportFor(
            reason: Int, description: String?, status: Int, doing: List<String>, details: String, onScreen: Boolean = false,
        ): Report? {
            val desc = (description ?: "").lowercase()
            val was = doing.firstOrNull()?.let { " It was $it." } ?: ""
            val oem = listOf("powerkeeper", "freez", "lmk", "oom", "low memory", "lowmem").any { it in desc }
            // The user swiping Yamini away or stopping it is a normal close.
            val byUser = listOf("remove task", "swipe", "user request", "force stop", "stop app", "instr", "finished inst", "shutdown", "reboot").any { it in desc }
            val engineWork = doing.any { "on the phone" in it || "phone-model engine" in it || "loading" in it }
            // Getting a phone model ready in the background (it reads the prompt and writes 1 token):
            // nothing the user asked for was lost.
            val onlyWarmUp = doing.isNotEmpty() && doing.all { "up to 1 tokens" in it }
            return when (reason) {
                ApplicationExitInfo.REASON_CRASH -> Report("Yamini crashed last time", "A mistake in Yamini's code closed it.$was Please share the report.", details)
                ApplicationExitInfo.REASON_CRASH_NATIVE -> if (engineWork) Report(
                    "Yamini crashed last time (phone-model engine)",
                    "The on-phone AI engine crashed.$was Yamini switches to the safer engine by itself if it happens again. Please share the report.",
                    details,
                ) else Report(
                    "Yamini crashed last time",
                    "A part of Yamini written in native code (a phone AI engine, or the phone's web engine) crashed.$was Please share the report.",
                    details,
                )
                ApplicationExitInfo.REASON_ANR -> Report("Yamini froze last time", "It stopped responding and Android closed it.$was Please share the report.", details)
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Report(
                    "Android closed Yamini: the phone ran out of memory",
                    "Phone models need a lot of memory (a 7B model about 5 GB).$was Use a smaller model (Gemma 3 4B, Llama 3.2 3B), close other apps, or use Gemini. Please share the report.",
                    details,
                )
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> Report("Yamini could not start last time", "Please share the report.", details)
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Report(
                    "Android closed Yamini when a system app it used restarted",
                    "Something Yamini was connected to (often Google Play services, which updates itself) stopped, and Android closes the apps " +
                        "connected to it.$was This build no longer uses Play services' fonts, the usual cause. Please share the report if it happens again.",
                    details,
                )
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN,
                ApplicationExitInfo.REASON_EXIT_SELF, -1 -> when {
                    // Swiped away from the recent apps (HyperOS reports "SwipeUpClean"): the user closed it.
                    // A note only if a reply or a build was cut off; nothing for a background warm-up.
                    byUser && !oem -> if (doing.isNotEmpty() && !onlyWarmUp) Report(
                        "Yamini was closed from the recent apps",
                        "It was swiped away while it was working, so that was not finished.$was To let a reply or an app finish " +
                            "while you use other apps, leave Yamini in the recent apps (you can lock it there).",
                        details,
                    ) else null
                    onlyWarmUp && !oem && status != 9 -> null
                    // Phones' own battery savers (MIUI/HyperOS "powerkeeper" and others) show up here.
                    oem && doing.isEmpty() -> Report(
                        "The phone closed Yamini in the background",
                        "Your phone's battery saver closed it. Allow it in Settings → General → Keep running in the background.",
                        details,
                    )
                    // Closed in the middle of work with no crash: the phone stopped it (signal 9 is a kill).
                    doing.isNotEmpty() || oem -> Report(
                        "The phone closed Yamini while it was working",
                        "Android shows no crash, so the phone itself stopped Yamini" + (if (status == 9) " (a kill signal)" else "") +
                            ", most often for memory: a phone model needs a lot while it writes.$was Try a smaller phone model, close other apps, " +
                            "and allow Yamini in Settings → General → Keep running in the background. Please share the report so it can be fixed.",
                        details,
                    )
                    // Closed while the user was looking at it, without them closing it.
                    onScreen && !byUser -> Report(
                        "Yamini closed unexpectedly",
                        "It closed while you were using it, and Android gave no crash" + (if (status == 9) " (a kill signal: usually the phone freeing memory)" else "") +
                            ". Please share the report so it can be fixed.",
                        details,
                    )
                    else -> null
                }
                // The phone's own task cleaners can count as "stopped by the user": only with their name.
                ApplicationExitInfo.REASON_USER_REQUESTED -> if (oem && doing.isNotEmpty()) Report(
                    "The phone closed Yamini while it was working",
                    "The phone's battery saver or cleaner stopped it.$was Allow Yamini in Settings → General → Keep running in the background.",
                    details,
                ) else null
                else -> null // closed normally: swiped away by the user, updated, stopped from Settings…
            }
        }
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        else -> r.toString()
    }

    private fun header() = "Yamini ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.FINGERPRINT}"
}
