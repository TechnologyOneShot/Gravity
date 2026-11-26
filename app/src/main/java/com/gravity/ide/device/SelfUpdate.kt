package com.gravity.ide.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gravity.ide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Updates Yamini from its latest release, in place: Android keeps the app's data (chats, downloaded
 * phone models and voices) because the new APK is installed over the old one with Yamini's own
 * PackageInstaller session. Before installing it checks that the APK is newer, matches the
 * release's SHA-256, and is signed with the same key as the installed app (otherwise Android
 * refuses the update and would only install it after an uninstall, which deletes the models).
 */
object SelfUpdate {
    private const val RELEASE_API = "https://api.github.com/repos/TechnologyOneShot/Gravity/releases/tags/debug-latest"
    private const val ASSET = "yamini-debug.apk"

    data class Release(val versionCode: Int, val versionName: String, val url: String, val size: Long, val sha256: String?)

    /** The latest published build (GitHub release "debug-latest"). */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RELEASE_API).header("Accept", "application/vnd.github+json").header("User-Agent", "Yamini").build()
        val json = com.gravity.ide.ai.Http.base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub answered HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
        parse(json, phone = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
    }

    /**
     * Reads a release (its body says "build N (version x.y.N)" and each APK's SHA-256). On a phone
     * ([phone], arm64) the phone-only APK under this build's own name is used: about half the
     * download, and its address never gets a newer file while it downloads (a download that
     * mixed two builds gave a broken APK).
     */
    fun parse(json: JSONObject, phone: Boolean = false): Release {
        val body = json.optString("body")
        val code = Regex("build (\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: throw IOException("The release does not say its build number")
        val name = Regex("version ([0-9.]+)").find(body)?.groupValues?.get(1) ?: "0.1.$code"
        fun sha(asset: String) = Regex("${Regex.escape(asset)}[^\\n]*?SHA-256 ([0-9a-f]{64})").find(body)?.groupValues?.get(1)
        val assets = json.optJSONArray("assets") ?: throw IOException("The release has no files")
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
        if (phone) {
            byName["yamini-$name-phone.apk"]?.let { a -> return Release(code, name, a.getString("browser_download_url"), a.optLong("size"), sha("yamini-phone.apk")) }
        }
        val a = byName[ASSET] ?: throw IOException("The release has no $ASSET")
        return Release(code, name, a.getString("browser_download_url"), a.optLong("size"), sha(ASSET))
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    /** What the update is doing, for the About screen (kept while the screen is closed). */
    val status = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val found = kotlinx.coroutines.flow.MutableStateFlow<Release?>(null)

    /** Looks for a newer build (in [scope], so leaving the screen does not stop it). */
    fun check(scope: kotlinx.coroutines.CoroutineScope) {
        if (busy.value) return
        busy.value = true
        status.value = "Checking…"
        scope.launch {
            try {
                val r = latest()
                found.value = r
                status.value = if (isNewer(r)) "Version ${r.versionName} is ready (${r.size / 1_000_000} MB). You have ${BuildConfig.VERSION_NAME}."
                else "You have the newest version (${BuildConfig.VERSION_NAME})."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.value = "Could not check: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Downloads [r], checks it and installs it over this app with [installer] (in [scope], so the
     * download goes on when the screen is left).
     */
    fun install(scope: kotlinx.coroutines.CoroutineScope, context: Context, installer: AppInstaller, r: Release) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            try {
                val apk = download(context, r) { got, total -> status.value = "Downloading… ${if (total > 0) got * 100 / total else 0}%" }
                val problem = withContext(Dispatchers.IO) { problem(context, apk) }
                if (problem != null) {
                    status.value = problem
                } else {
                    status.value = "Installing… Android asks you to confirm; Yamini closes and you open it again."
                    status.value = when (val o = installer.install(apk)) {
                        is AppInstaller.InstallOutcome.Success -> "Updated."
                        is AppInstaller.InstallOutcome.Failure -> "Android did not install it: ${o.message}"
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status.value = e.message ?: "Update failed"
            } finally {
                busy.value = false
            }
        }
    }

    /** Downloads [r] into the app's cache (resumes a partial download). */
    suspend fun download(context: Context, r: Release, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != "yamini-${r.versionCode}.apk.part" && it.name != "yamini-${r.versionCode}.apk" }?.forEach { it.delete() }
        val done = File(dir, "yamini-${r.versionCode}.apk")
        if (done.isFile && done.length() == r.size && (r.sha256 == null || sha256(done) == r.sha256)) return@withContext done
        val part = File(dir, "yamini-${r.versionCode}.apk.part")
        var have = if (part.isFile) part.length() else 0L
        val req = Request.Builder().url(r.url).header("User-Agent", "Yamini").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        com.gravity.ide.ai.Http.base.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            if (resp.code != 206) have = 0
            val body = resp.body ?: throw IOException("Empty download")
            java.io.RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val buf = ByteArray(1 shl 16)
                val input = body.byteStream()
                var got = have
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (got - last > 1_000_000) { last = got; progress(got, r.size) }
                }
                progress(got, r.size)
            }
        }
        if (r.size > 0 && part.length() != r.size) throw IOException("Download incomplete: ${part.length()} of ${r.size} bytes (tap again to resume)")
        r.sha256?.let { want ->
            val got = sha256(part)
            if (got != want) {
                part.delete()
                throw IOException("The download is damaged (SHA-256 does not match the release); it was deleted, try again")
            }
        }
        if (!part.renameTo(done)) throw IOException("Could not keep the download")
        done
    }

    /**
     * Why Android would refuse [apk] as an update of this app, or null if it can be installed
     * over it (same package, newer, same signing key).
     */
    fun problem(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file is not a valid APK."
        if (info.packageName != context.packageName) return "The APK is a different app (${info.packageName})."
        val newCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (newCode <= BuildConfig.VERSION_CODE) return "It is not newer than the installed version (${BuildConfig.VERSION_CODE})."
        if (Build.VERSION.SDK_INT >= 28) {
            val mine = signers(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
            val theirs = signers(info.signingInfo)
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) {
                return "The new APK is signed with a different key than the installed Yamini, so Android will not update it in place " +
                    "(the installed copy came from another build). Only uninstalling first would work, and that deletes the downloaded models."
            }
        }
        return null
    }

    private fun signers(s: android.content.pm.SigningInfo?): Set<String> {
        if (s == null) return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { c -> MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
