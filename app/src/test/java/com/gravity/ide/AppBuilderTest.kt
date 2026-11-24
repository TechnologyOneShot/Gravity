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

/** The single-file path used when App Builder runs on an on-device model. */
class AppBuilderLocalPathTest {
    private val page = """
        <!DOCTYPE html>
        <html lang="en"><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Music Player</title>
        <style>body{font-family:sans-serif;margin:0;padding:16px}button{font-size:20px;padding:12px}</style></head>
        <body><input type="file" id="f" accept="audio/*" multiple><audio id="a" controls></audio>
        <script>document.getElementById('f').onchange=e=>{const a=document.getElementById('a');a.src=URL.createObjectURL(e.target.files[0]);a.play()}</script>
        </body></html>
    """.trimIndent()

    @Test fun recognisesBuildAndChangeInstructions() {
        val build = AppBuilder.buildInstruction("music player bana do")
        val change = AppBuilder.changeInstruction("add a shuffle button")
        assertTrue(AppBuilder.isBuilderInstruction(build))
        assertTrue(AppBuilder.isBuilderInstruction(change))
        assertFalse(AppBuilder.isChangeInstruction(build))
        assertTrue(AppBuilder.isChangeInstruction(change))
        assertFalse(AppBuilder.isBuilderInstruction("Fix the failing test in app/"))
        assertEquals("music player bana do", AppBuilder.requestFrom(build))
        assertEquals("add a shuffle button", AppBuilder.requestFrom(change))
    }

    @Test fun extractsAFencedPage() {
        val reply = "Here is your app:\n```html\n$page\n```\nEnjoy!"
        assertEquals(page, AppBuilder.extractHtml(reply))
        assertEquals(page, AppBuilder.extractHtml("```\n$page\n```"))
    }

    @Test fun extractsAnUnfencedPage() {
        assertEquals(page, AppBuilder.extractHtml("Sure!\n$page\nThat's all."))
    }

    @Test fun rejectsGibberishTruncatedAndPlaceholderReplies() {
        assertEquals(null, AppBuilder.extractHtml("I to the the of music player player and and the"))
        assertEquals(null, AppBuilder.extractHtml("```html\n" + page.substringBefore("<script>")))
        assertEquals(null, AppBuilder.extractHtml("```html\n<html><body>hi</body></html>\n```"))
        val starter = java.nio.file.Files.createTempDirectory("gravity-app").toFile()
        com.gravity.ide.workspace.ProjectTemplate.WEB_APP.write(starter, "Music Player")
        val starterHtml = File(starter, "index.html").readText()
        assertTrue("The starter page must be recognised as unbuilt", AppBuilder.isPlaceholder(starterHtml))
        assertEquals(null, AppBuilder.extractHtml("```html\n$starterHtml\n```"))
        assertTrue(AppBuilder.isPlaceholder("<p>Gravity is building this app…</p>"))
        assertFalse(AppBuilder.isPlaceholder(page))
    }

    @Test fun directPromptCarriesTheRequestAndCurrentPage() {
        val (system, user) = AppBuilder.directPrompt("music player", null)
        assertTrue(system.contains("ONE self-contained HTML file"))
        assertTrue(user.contains("music player"))
        val (_, changeUser) = AppBuilder.directPrompt("add shuffle", page)
        assertTrue(changeUser.contains(page))
        assertTrue(changeUser.contains("add shuffle"))
    }
}
