package com.gravity.ide.build

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs commands inside Termux through its documented RUN_COMMAND intent and receives the
 * real stdout/stderr/exit code through a PendingIntent. This is the companion path for
 * same-phone Android builds: Termux can host openjdk-17, Gradle and aapt2, which Android
 * does not allow Gravity itself to execute from app storage.
 *
 * Requirements (shown in Build Center): Termux installed (F-Droid/GitHub build),
 * `allow-external-apps = true` in ~/.termux/termux.properties, the "Run commands in Termux
 * environment" permission granted to Gravity, and `termux-setup-storage` run once.
 */
class TermuxBridge(private val context: Context, private val events: EventLog) {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String, val err: Int, val errmsg: String?) {
        val pluginError: String? get() = errmsg?.takeIf { it.isNotBlank() && err != ERR_SUCCESS && err != 0 }
    }

    data class Status(val installed: Boolean, val version: String?, val permissionGranted: Boolean)

    fun status(): Status {
        val version = try {
            context.packageManager.getPackageInfo(PACKAGE, 0).versionName ?: "installed"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        return Status(version != null, version, granted)
    }

    /**
     * Maps a Storage Access Framework tree on shared storage to the filesystem path Termux
     * sees after `termux-setup-storage` (e.g. primary:Download/GravityBuild →
     * /storage/emulated/0/Download/GravityBuild). Returns null for non-filesystem providers.
     */
    fun sharePathFor(treeUri: Uri): String? {
        if (treeUri.authority != "com.android.externalstorage.documents") return null
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return null }
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return if (path.isEmpty()) base else "$base/$path"
    }

    suspend fun run(script: String, timeoutMs: Long, workDir: String = HOME): Result {
        val st = status()
        if (!st.installed) throw IllegalStateException("Termux is not installed")
        if (!st.permissionGranted) throw IllegalStateException("Grant Yamini the \"Run commands in Termux environment\" permission (Build Center → Termux)")
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<Result>()
        pending[id] = deferred
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val resultIntent = PendingIntent.getBroadcast(context, id, Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_REQUEST_ID, id), flags)
        val intent = Intent()
            .setClassName(PACKAGE, SERVICE)
            .setAction(ACTION_RUN_COMMAND)
            .putExtra(EXTRA_PATH, BASH)
            .putExtra(EXTRA_ARGUMENTS, arrayOf("-c", script))
            .putExtra(EXTRA_WORKDIR, workDir)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_PENDING_INTENT, resultIntent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException("Could not reach Termux (${e.javaClass.simpleName}: ${e.message}). Is allow-external-apps enabled?")
        }
        events.log(EventCategory.BUILD, "Sent command to Termux", script.take(2000))
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            events.log(EventCategory.BUILD, "Termux command timed out", "The command may still be running inside Termux.", EventLevel.WARN)
            throw IllegalStateException("No result from Termux after ${timeoutMs / 60_000} min (the command may still be running there)")
        } finally {
            pending.remove(id)
        }
    }

    /** Probes the Termux toolchain. Keys: java, gradle, aapt2, sdk, storage, cache, share. */
    suspend fun toolchain(sharePath: String?): Map<String, String> {
        val shareCheck = sharePath?.let { "[ -d ${shellQuote(it)} ] && echo \"share=ok\" || echo \"share=missing\"" } ?: "echo share=unset"
        val script = """
            [ -f "${'$'}HOME/.gravity-env" ] && . "${'$'}HOME/.gravity-env"
            echo "arch=$(uname -m)"
            command -v java >/dev/null && echo "java=$(java -version 2>&1 | head -1)"
            command -v gradle >/dev/null && echo "gradle=$(gradle --version 2>/dev/null | grep -m1 Gradle)"
            command -v aapt2 >/dev/null && echo "aapt2=$(command -v aapt2)"
            [ -n "${'$'}ANDROID_HOME" ] && echo "sdk=${'$'}ANDROID_HOME"
            [ -d "${'$'}HOME/storage/shared" ] && echo "storage=ok" || echo "storage=missing"
            if [ -d "${'$'}HOME/.gradle/caches/modules-2" ]; then echo "cache=$(du -sh ${'$'}HOME/.gradle/caches 2>/dev/null | cut -f1)"; else echo "cache=none"; fi
            $shareCheck
        """.trimIndent()
        val r = run(script, 120_000)
        r.pluginError?.let { throw IllegalStateException("Termux: $it") }
        return r.stdout.lines().mapNotNull { line ->
            val k = line.substringBefore('=', "")
            if (k.isEmpty()) null else k to line.substringAfter('=').trim()
        }.toMap()
    }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val HOME = "/data/data/com.termux/files/home"
        private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        /** termux-shared Errno.ERRNO_SUCCESS */
        private const val ERR_SUCCESS = -1
        internal const val EXTRA_REQUEST_ID = "gravity_termux_request"

        private val nextId = AtomicInteger(7000)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Result>>()

        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        internal fun complete(id: Int, result: Result) {
            pending.remove(id)?.complete(result)
        }
    }
}

/** Receives Termux's execution result (bundle "result": stdout, stderr, exitCode, err, errmsg). */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(TermuxBridge.EXTRA_REQUEST_ID, -1)
        val bundle = intent.getBundleExtra("result")
        val result = TermuxBridge.Result(
            exitCode = bundle?.getInt("exitCode", -1) ?: -1,
            stdout = bundle?.getString("stdout").orEmpty(),
            stderr = bundle?.getString("stderr").orEmpty(),
            err = bundle?.getInt("err", -1) ?: -1,
            errmsg = bundle?.getString("errmsg"),
        )
        TermuxBridge.complete(id, result)
    }
}
