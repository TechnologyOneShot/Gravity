package com.gravity.ide

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Why Gravity closed unexpectedly last time, told to the user on the next start (with a report
 * they can share): a crash (its stack trace is saved when it happens), a native crash, the app
 * freezing (ANR), Android closing it for memory, or the phone's battery saver closing it.
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

    /** Called at start: looks at how the last run ended and prepares a report if it was not normal. */
    fun check() {
        val saved = lastCrash.takeIf { it.isFile }?.readText()
        lastCrash.delete()
        // What was running when it closed: noted work, and the phone-model engine's own note.
        val doing = buildList {
            prefs.getStringSet(KEY_WORK, emptySet()).orEmpty().sorted().forEach { add(it.substringAfter('|')) }
            context.getSharedPreferences("local_model_host", Context.MODE_PRIVATE).getString("native_busy", null)?.split('|')?.let { p ->
                val what = p.getOrNull(3).orEmpty().ifBlank { "working" }
                add("phone-model engine (${p.getOrNull(0) ?: "?"}): $what")
            }
        }.distinct()
        prefs.edit().remove(KEY_WORK).apply()
        var exit: ApplicationExitInfo? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val seen = prefs.getLong("seen", 0L)
            exit = runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            }.getOrNull()?.takeIf { it.timestamp > seen }
            exit?.let { prefs.edit().putLong("seen", it.timestamp).apply() }
        }
        val whileLine = if (doing.isEmpty()) "" else "While: " + doing.joinToString("; ") + "\n"
        val report = when {
            saved != null -> Report(
                "Gravity crashed last time",
                "A mistake in Gravity's code closed it. The report below shows exactly where; please share it so it can be fixed.",
                whileLine + saved,
            )
            exit != null && Build.VERSION.SDK_INT >= 30 -> describe(exit, doing)
            // No exit record (older Android, or none written), but it closed in the middle of work.
            doing.isNotEmpty() -> reportFor(-1, null, 0, doing, whileLine + memoryLine() + header())
            else -> null
        }
        pending.value = report
    }

    private fun describe(e: ApplicationExitInfo, doing: List<String>): Report? {
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
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let { append("\n\n").append(it.take(15_000)) }
            }
        }
        return reportFor(e.reason, e.description, e.status, doing, details)
    }

    private fun memoryLine(): String = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        "Phone memory: ${info.totalMem / (1024 * 1024)} MB in all, ${info.availMem / (1024 * 1024)} MB available now\n"
    }.getOrDefault("")

    companion object {
        private const val KEY_WORK = "work"

        /**
         * The report for an exit ([reason] is an ApplicationExitInfo reason, -1 when Android gave
         * none) while Gravity was [doing] something, or null for a normal exit.
         */
        fun reportFor(reason: Int, description: String?, status: Int, doing: List<String>, details: String): Report? {
            val desc = (description ?: "").lowercase()
            val was = doing.firstOrNull()?.let { " It was $it." } ?: ""
            val oem = listOf("powerkeeper", "freez", "lmk", "oom", "low memory", "lowmem").any { it in desc }
            return when (reason) {
                ApplicationExitInfo.REASON_CRASH -> Report("Gravity crashed last time", "A mistake in Gravity's code closed it.$was Please share the report.", details)
                ApplicationExitInfo.REASON_CRASH_NATIVE -> Report(
                    "Gravity crashed last time (phone-model engine)",
                    "The on-phone AI engine crashed.$was Gravity switches to the safer engine by itself if it happens again. Please share the report.",
                    details,
                )
                ApplicationExitInfo.REASON_ANR -> Report("Gravity froze last time", "It stopped responding and Android closed it.$was Please share the report.", details)
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Report(
                    "Android closed Gravity: the phone ran out of memory",
                    "Phone models need a lot of memory (a 7B model about 5 GB).$was Use a smaller model (Gemma 3 4B, Llama 3.2 3B), close other apps, or use Gemini. Please share the report.",
                    details,
                )
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Report("Gravity could not start last time", "Please share the report.", details)
                ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_UNKNOWN, -1 -> when {
                    // Phones' own battery savers (MIUI/HyperOS "powerkeeper" and others) show up here.
                    oem && doing.isEmpty() -> Report(
                        "The phone closed Gravity in the background",
                        "Your phone's battery saver closed it. Allow it in Settings → General → Keep running in the background.",
                        details,
                    )
                    // Closed in the middle of work with no crash: the phone stopped it (signal 9 is a kill).
                    doing.isNotEmpty() || oem -> Report(
                        "The phone closed Gravity while it was working",
                        "Android shows no crash, so the phone itself stopped Gravity" + (if (status == 9) " (a kill signal)" else "") +
                            ", most often for memory: a phone model needs a lot while it writes.$was Try a smaller phone model, close other apps, " +
                            "and allow Gravity in Settings → General → Keep running in the background. Please share the report so it can be fixed.",
                        details,
                    )
                    else -> null
                }
                // The phone's own task cleaners can count as "stopped by the user": only with their name.
                ApplicationExitInfo.REASON_USER_REQUESTED -> if (oem && doing.isNotEmpty()) Report(
                    "The phone closed Gravity while it was working",
                    "The phone's battery saver or cleaner stopped it.$was Allow Gravity in Settings → General → Keep running in the background.",
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

    private fun header() = "Gravity ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.FINGERPRINT}"
}
