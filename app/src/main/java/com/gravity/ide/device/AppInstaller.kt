package com.gravity.ide.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.gravity.ide.GravityApp
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class ApkInfo(val packageName: String, val versionName: String?, val versionCode: Long, val label: String?, val minSdk: Int?)

/**
 * Installs, launches and uninstalls apps on this device through public Android APIs.
 * The system shows its own confirmation for every install and uninstall.
 */
class AppInstaller(private val context: Context, private val events: EventLog) {

    sealed interface InstallOutcome {
        data class Success(val packageName: String?) : InstallOutcome
        data class Failure(val status: Int, val message: String) : InstallOutcome
    }

    @Suppress("DEPRECATION")
    fun inspect(apk: File): ApkInfo? {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        info.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        val label = info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) info.applicationInfo?.minSdkVersion else null
        return ApkInfo(info.packageName, info.versionName, code, label, minSdk)
    }

    /** Streams the APK into a PackageInstaller session and waits for the real result. */
    suspend fun install(apk: File): InstallOutcome = withContext(Dispatchers.IO) {
        if (!apk.isFile) throw IOException("APK not found: ${apk.path}")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        inspect(apk)?.let { params.setAppPackageName(it.packageName) }
        val sessionId = installer.createSession(params)
        val deferred = CompletableDeferred<InstallOutcome>()
        pending[sessionId] = deferred
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_SESSION, sessionId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
        events.log(EventCategory.DEVICE, "Installing ${apk.name}", "PackageInstaller session $sessionId")
        val outcome = deferred.await()
        when (outcome) {
            is InstallOutcome.Success -> events.log(EventCategory.DEVICE, "Installed ${outcome.packageName ?: apk.name}")
            is InstallOutcome.Failure -> events.log(EventCategory.DEVICE, "Install failed (status ${outcome.status})", outcome.message, EventLevel.ERROR)
        }
        outcome
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        events.log(EventCategory.DEVICE, "Launched $packageName")
        return true
    }

    /** Opens the system uninstall confirmation. */
    fun uninstall(packageName: String) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        events.log(EventCategory.DEVICE, "Requested uninstall of $packageName")
    }

    fun openAppInfo(packageName: String) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    internal fun onResult(sessionId: Int, status: Int, message: String?, packageName: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System confirmation UI; the final result arrives in a later broadcast.
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> pending.remove(sessionId)?.complete(InstallOutcome.Success(packageName))
            else -> pending.remove(sessionId)?.complete(InstallOutcome.Failure(status, message ?: statusName(status)))
        }
    }

    companion object {
        const val EXTRA_SESSION = "gravity_session"
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        fun statusName(status: Int) = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Aborted by user"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Conflicts with an installed app (signature mismatch?)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Incompatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
            else -> "Failure"
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? GravityApp ?: return
        val session = intent.getIntExtra(AppInstaller.EXTRA_SESSION, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        @Suppress("DEPRECATION")
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        app.container.installer.onResult(session, status, message, pkg, confirm)
    }
}
