package com.gravity.ide.build

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.gravity.ide.core.AxmlRewriter
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.security.AppSigningKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns a web app project into an installable APK on the phone, without Java, Gradle or Termux:
 * the bundled shell APK (module :shell) is copied, its assets/www is replaced by the project's
 * files, the manifest gets the app's own package name and label, and the result is signed
 * (APK Signature Scheme v2) with Gravity's App Builder key from the Android Keystore.
 */
class WebAppPackager(private val context: Context, private val events: EventLog) {

    data class Output(val apk: File, val packageName: String, val label: String, val webFiles: Int, val bytes: Long)

    suspend fun build(project: ProjectEntity): Output = withContext(Dispatchers.IO) {
        val root = File(project.rootPath)
        require(File(root, "index.html").isFile) { "This app has no index.html yet, so there is nothing to package." }
        val files = webFiles(root)
        val label = project.name.trim().take(50).ifEmpty { "My app" }
        val pkg = packageNameFor(project)

        val work = File(context.cacheDir, "appbuilder").apply { mkdirs() }
        val template = File(work, "webshell.apk")
        context.assets.open(SHELL_ASSET).use { input -> template.outputStream().use { input.copyTo(it) } }

        val unsigned = File(work, "${project.id}-unsigned.apk")
        ZipFile(template).use { zin ->
            ZipOutputStream(unsigned.outputStream().buffered()).use { zout ->
                for (entry in zin.entries()) {
                    val name = entry.name
                    if (name.startsWith("META-INF/") || name.startsWith("assets/www/")) continue
                    val data = zin.getInputStream(entry).use { it.readBytes() }
                    val bytes = if (name == "AndroidManifest.xml") {
                        AxmlRewriter.replaceStrings(data, mapOf(TEMPLATE_PACKAGE to pkg, TEMPLATE_LABEL to label))
                    } else data
                    zout.putEntry(name, bytes, stored = entry.method == ZipEntry.STORED)
                }
                for (f in files) zout.putEntry("assets/www/" + f.relativeTo(root).invariantSeparatorsPath, f.readBytes(), stored = false)
            }
        }

        val outDir = File(root, "dist").apply { mkdirs() }
        val signed = File(outDir, label.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "app" } + ".apk")
        val key = AppSigningKey.getOrCreate()
        ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("GRAVITY", key.privateKey, listOf(key.certificate)).build()))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign()
        unsigned.delete()

        val check = ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(MIN_SDK).build().verify()
        if (!check.isVerified) {
            val problems = (check.errors + check.v2SchemeSigners.flatMap { it.errors }).joinToString("; ")
            signed.delete()
            throw IllegalStateException("Signing produced an APK Android would reject: $problems")
        }
        events.log(
            EventCategory.BUILD, "Packaged ${project.name} as an APK",
            "${signed.name}: ${signed.length()} bytes, $pkg, ${files.size} web files, signed with the App Builder key (v2)",
            projectId = project.id,
        )
        Output(signed, pkg, label, files.size, signed.length())
    }

    companion object {
        const val SHELL_ASSET = "shell/webshell.apk"
        const val TEMPLATE_PACKAGE = "com.gravity.webshell.app"
        const val TEMPLATE_LABEL = "Gravity Web App"
        const val SHELL_ACTIVITY = "com.gravity.webshell.MainActivity"
        private const val MIN_SDK = 26
        private val skippedDirs = setOf("dist", "build", "node_modules")

        /** Stable, valid package name per project, e.g. com.gravity.app.musicplayer.x3fa29c. */
        fun packageNameFor(project: ProjectEntity): String {
            val slug = project.name.lowercase().replace(Regex("[^a-z0-9]"), "").take(24).ifEmpty { "app" }
                .let { if (it[0].isDigit()) "a$it" else it }
            val suffix = "x" + project.id.replace("-", "").lowercase().take(6)
            return "com.gravity.app.$slug.$suffix"
        }

        /** Files that go into the APK: everything except hidden files, build output and Gravity notes. */
        fun webFiles(root: File): List<File> = root.walkTopDown()
            .onEnter { it == root || (it.name !in skippedDirs && !it.name.startsWith(".")) }
            .filter { it.isFile && !it.name.startsWith(".") && it.name != "GRAVITY.md" && !it.name.endsWith(".apk") }
            .toList()

        private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray, stored: Boolean) {
            val e = ZipEntry(name)
            if (stored) {
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
            } else {
                e.method = ZipEntry.DEFLATED
            }
            putNextEntry(e)
            write(bytes)
            closeEntry()
        }
    }
}
