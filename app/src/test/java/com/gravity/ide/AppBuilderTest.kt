package com.gravity.ide

import com.gravity.ide.agent.AppBuilder
import com.gravity.ide.build.WebAppPackager
import com.gravity.ide.core.AxmlRewriter
import com.gravity.ide.data.db.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AppBuilderNameTest {
    @Test fun namesFromPlainRequests() {
        assertEquals("Music Player", AppBuilder.nameFor("music player bana do"))
        assertEquals("Music Player", AppBuilder.nameFor("Make a music player with playlists"))
        assertEquals("Todo List App", AppBuilder.nameFor("please build me a todo list app"))
        assertEquals("Notes App", AppBuilder.nameFor("mujhe ek notes app bana do"))
        assertEquals("Music Player", AppBuilder.nameFor("ek music player bana do jisme shuffle ho"))
        assertEquals("Calculator", AppBuilder.nameFor("Calculator"))
        assertEquals("My App", AppBuilder.nameFor("!!!"))
    }

    @Test fun packageNamesAreValidAndStable() {
        val p = ProjectEntity(id = "3fa29c11-aaaa-bbbb-cccc-000000000000", name = "Music Player", type = "APP", rootPath = "/x", createdAt = 0, updatedAt = 0)
        val pkg = WebAppPackager.packageNameFor(p)
        assertEquals("com.gravity.app.musicplayer.x3fa29c", pkg)
        assertTrue(pkg.split('.').all { Regex("[a-z][a-z0-9_]*").matches(it) })
        val digits = WebAppPackager.packageNameFor(p.copy(name = "2048 Game"))
        assertTrue(digits, digits.split('.').all { Regex("[a-z][a-z0-9_]*").matches(it) })
        val hindi = WebAppPackager.packageNameFor(p.copy(name = "म्यूज़िक प्लेयर"))
        assertEquals("com.gravity.app.app.x3fa29c", hindi)
    }
}

/** Runs against the real shell APK that the build bundles into the app's assets. */
class AxmlRewriterTest {
    private fun shellManifest(): ByteArray {
        val apk = File("build/generated/webshell/shell/webshell.apk")
        assertTrue("shell APK not built at ${apk.absolutePath}", apk.isFile)
        return ZipFile(apk).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
    }

    @Test fun shellManifestHasTheTemplateStrings() {
        val strings = AxmlRewriter.readStrings(shellManifest())
        assertTrue(strings.toString(), WebAppPackager.TEMPLATE_PACKAGE in strings)
        assertTrue(strings.toString(), WebAppPackager.TEMPLATE_LABEL in strings)
        assertTrue(strings.toString(), WebAppPackager.SHELL_ACTIVITY in strings)
    }

    @Test fun replacesPackageAndLabelKeepingEverythingElse() {
        val original = shellManifest()
        val before = AxmlRewriter.readStrings(original)
        val out = AxmlRewriter.replaceStrings(original, mapOf(WebAppPackager.TEMPLATE_PACKAGE to "com.gravity.app.musicplayer.x3fa29c", WebAppPackager.TEMPLATE_LABEL to "Music Player ♫"))
        val after = AxmlRewriter.readStrings(out)
        assertEquals(before.size, after.size)
        for (i in before.indices) {
            when (before[i]) {
                WebAppPackager.TEMPLATE_PACKAGE -> assertEquals("com.gravity.app.musicplayer.x3fa29c", after[i])
                WebAppPackager.TEMPLATE_LABEL -> assertEquals("Music Player ♫", after[i])
                else -> assertEquals(before[i], after[i])
            }
        }
        assertTrue(WebAppPackager.SHELL_ACTIVITY in after)
        // Header size field matches the new file length; the file stays 4-byte aligned.
        val size = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or ((out[6].toInt() and 0xFF) shl 16) or ((out[7].toInt() and 0xFF) shl 24)
        assertEquals(out.size, size)
        assertEquals(0, out.size % 4)
        // Rewriting back restores identical strings.
        val back = AxmlRewriter.replaceStrings(out, mapOf("com.gravity.app.musicplayer.x3fa29c" to WebAppPackager.TEMPLATE_PACKAGE, "Music Player ♫" to WebAppPackager.TEMPLATE_LABEL))
        assertEquals(before, AxmlRewriter.readStrings(back))
    }

    @Test fun refusesWhenTemplateStringIsMissing() {
        val r = runCatching { AxmlRewriter.replaceStrings(shellManifest(), mapOf("not.in.manifest" to "x")) }
        assertTrue(r.isFailure)
        assertFalse(r.exceptionOrNull()?.message.isNullOrBlank())
    }
}
