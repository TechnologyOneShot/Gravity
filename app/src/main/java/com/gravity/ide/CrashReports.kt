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

    /** Called at start: looks at how the last run ended and prepares a report if it was not normal. */
    fun check() {
        val saved = lastCrash.takeIf { it.isFile }?.readText()
        lastCrash.delete()
        var exit: ApplicationExitInfo? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val seen = prefs.getLong("seen", 0L)
            exit = runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            }.getOrNull()?.takeIf { it.timestamp > seen }
            exit?.let { prefs.edit().putLong("seen", it.timestamp).apply() }
        }
        val report = when {
            saved != null -> Report(
                "Gravity crashed last time",
                "A mistake in Gravity's code closed it. The report below shows exactly where; please share it so it can be fixed.",
                saved,
            )
            exit != null && Build.VERSION.SDK_INT >= 30 -> describe(exit)
            else -> null
        }
        pending.value = report
    }

    private fun describe(e: ApplicationExitInfo): Report? {
        if (Build.VERSION.SDK_INT < 30) return null
        val details = buildString {
            append("Reason: ").append(reasonName(e.reason)).append(" (").append(e.reason).append(")\n")
            append("Description: ").append(e.description ?: "-").append('\n')
            append("Time: ").append(java.util.Date(e.timestamp)).append('\n')
            append("Memory used then: ").append(e.pss / 1024).append(" MB (PSS), ").append(e.rss / 1024).append(" MB (RSS)\n")
            append(header())
            if (e.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()?.let { append("\n\n").append(it.take(15_000)) }
            }
        }
        val desc = (e.description ?: "").lowercase()
        return when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> Report("Gravity crashed last time", "A mistake in Gravity's code closed it. Please share the report.", details)
            ApplicationExitInfo.REASON_CRASH_NATIVE -> Report(
                "Gravity crashed last time (phone-model engine)",
                "The on-phone AI engine crashed. Gravity switches to the safer engine by itself if it happens again. Please share the report.",
                details,
            )
            ApplicationExitInfo.REASON_ANR -> Report("Gravity froze last time", "It stopped responding and Android closed it. Please share the report.", details)
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> Report(
                "Android closed Gravity: the phone ran out of memory",
                "Phone models need a lot of memory (a 7B model about 5 GB). Use a smaller model (Gemma 3 4B, Llama 3.2 3B), close other apps, or use Gemini.",
                details,
            )
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_DEPENDENCY_DIED -> Report("Gravity could not start last time", "Please share the report.", details)
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER ->
                // Phones' own battery savers (MIUI/HyperOS "powerkeeper" and others) show up here.
                if (listOf("powerkeeper", "freez", "lmk", "oom", "low memory", "lowmem").any { it in desc }) Report(
                    "The phone closed Gravity in the background",
                    "Your phone's battery saver closed it. Allow it in Settings → General → Keep running in the background.",
                    details,
                ) else null
            else -> null // closed normally: swiped away by the user, updated, stopped from Settings…
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
