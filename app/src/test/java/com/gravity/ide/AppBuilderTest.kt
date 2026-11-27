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
        assertTrue(AppBuilder.isPlaceholder("<p>Yamini is building this app…</p>"))
        assertFalse(AppBuilder.isPlaceholder(page))
    }

    @Test fun recognisesRequestsForAnApp() {
        for (t in listOf("calculator bana do", "music player bana do", "Build a to-do list app", "make a quiz game", "mujhe ek app chahiye", "कैलकुलेटर बना दो")) {
            assertTrue(t, AppBuilder.looksLikeAppRequest(t))
        }
        for (t in listOf("what is an app?", "make a function that sorts a list", "explain this code", "write a message for my boss", "how do I make tea")) {
            assertFalse(t, AppBuilder.looksLikeAppRequest(t))
        }
    }

    @Test fun shortModelNamesForTheModelButton() {
        assertEquals("qwen2.5-coder-1.5b", com.gravity.ide.ui.screens.shortModelName("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("Qwen2.5-Coder-7B", com.gravity.ide.ui.screens.shortModelName("Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf"))
        assertEquals("gemini-3.5-flash", com.gravity.ide.ui.screens.shortModelName("gemini-3.5-flash"))
        assertEquals("stories260K", com.gravity.ide.ui.screens.shortModelName("stories260K.gguf"))
    }

    @Test fun fileGuidanceOnlyForAppsThatUseTheUsersFiles() {
        for (r in listOf("music player bana do", "a photo gallery", "video player", "mere gaane chalane wala app", "PDF reader", "गाना सुनने का ऐप")) {
            assertTrue(r, AppBuilder.usesUserFiles(r))
            assertTrue(r, AppBuilder.directPrompt(r, null).second.contains("<input type=\"file\">"))
        }
        for (r in listOf("calculator bana do", "a to-do list app", "tic tac toe game", "BMI calculator", "my profile page")) {
            assertFalse(r, AppBuilder.usesUserFiles(r))
            assertFalse(r, AppBuilder.directPrompt(r, null).second.contains("<input type=\"file\">"))
        }
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

/**
 * Writes the exact on-device App Builder prompts to build/app-builder-prompts, so CI can run them
 * through a real small coder model with llama.cpp (.github/scripts/phone-model-check.py).
 */
class AppBuilderPromptExportTest {
    @Test fun exportsPromptsForTheModelCheck() {
        val dir = File("build/app-builder-prompts").apply { mkdirs() }
        val requests = mapOf("calculator" to "calculator bana do", "music-player" to "music player bana do", "todo" to "a to-do list app")
        for ((name, request) in requests) {
            val (system, user) = AppBuilder.directPrompt(AppBuilder.requestFrom(AppBuilder.buildInstruction(request)), null)
            val json = org.json.JSONObject()
                .put("name", name).put("system", system).put("user", user)
                .put("prefix", AppBuilder.DIRECT_PREFIX).put("stop", org.json.JSONArray().put("</html>"))
            File(dir, "$name.json").writeText(json.toString(2))
        }
        // A build from a design picture with the phone model: the starting page DesignReader made from a
        // real screenshot (a music player's now-playing screen, from a browser window).
        val (designSystem, designUser) = AppBuilder.directPrompt(
            AppBuilder.requestFrom(AppBuilder.buildInstruction("music player is design jaisa bana do")), null, designText = "(measured)", designLayout = MEASURED_PLAYER,
        )
        assertTrue(designUser.contains("You cannot see it") && designUser.contains("aria-label=\"pause\"") && designUser.contains("display:none"))
        assertFalse("The starting page replaces the description", designUser.contains("(measured)"))
        File(dir, "music-player-design.json").writeText(
            org.json.JSONObject().put("name", "music-player-design").put("kind", "music-player").put("system", designSystem).put("user", designUser)
                .put("prefix", AppBuilder.DIRECT_PREFIX).put("stop", org.json.JSONArray().put("</html>")).toString(2),
        )
        val (repairSystem, repairUser) = AppBuilder.repairPrompt("{{HTML}}", listOf("{{ERRORS}}"))
        File(dir.parentFile, "app-builder-repair.json").writeText(
            org.json.JSONObject().put("system", repairSystem).put("user", repairUser).put("prefix", AppBuilder.DIRECT_PREFIX).toString(2),
        )
        assertTrue(repairUser.contains("- {{ERRORS}}"))
        assertEquals(4, dir.listFiles { f -> f.name.endsWith(".json") }!!.size)
    }

    companion object {
        /** DesignReader's starting page for a real now-playing screenshot (see DesignReaderTest for how it is measured). */
        val MEASURED_PLAYER = """
<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
*{box-sizing:border-box}html,body{margin:0}
body{min-height:100vh;background:linear-gradient(180deg, #263d0d, #41370d, #39300d);color:#dee0d7;font-family:system-ui,-apple-system,Roboto,sans-serif;padding-bottom:16px}
button{background:none;border:0;padding:0;margin:0;color:inherit;font:inherit;display:inline-flex;align-items:center;justify-content:center;cursor:pointer}
</style></head>
<body>
<div style="display:flex;margin-top:15px"><button aria-label="icon" style="color:#65704b;margin:0 auto"><svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2"/></svg></button></div>
<div style="display:flex;margin-top:13px"><div class="photo" role="img" style="width:64%;aspect-ratio:495/495;border-radius:7px;background:linear-gradient(135deg, #97ae70, #566b4c, #4f3526);margin:0 auto"></div></div>
<div style="position:relative;height:22px;margin-top:20px">
  <span style="font-size:16px;font-weight:700;color:#dee0d7;white-space:nowrap;position:absolute;top:50%;left:6%;transform:translateY(-50%)">Deeper Well</span>
  <button aria-label="icon" style="width:19px;height:19px;border-radius:50%;background:#b1aa9e;color:#5f4f35;position:absolute;top:50%;left:84%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2"/></svg></button>
  <button aria-label="more" style="width:19px;height:19px;border-radius:50%;background:#544026;color:#bfb8af;position:absolute;top:50%;left:91%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><circle cx="5" cy="12" r="2.2"/><circle cx="12" cy="12" r="2.2"/><circle cx="19" cy="12" r="2.2"/></svg></button>
</div>
<div style="display:flex;margin-top:1px"><span style="font-size:15px;color:#9ea18c;white-space:nowrap;margin-left:6%">Kacey Musgraves — Deeper Well</span></div>
<div style="display:flex;margin-top:17px"><div class="bar" style="width:88%;height:5px;border-radius:5px;background:#64624a;margin:0 auto"><div class="fill" style="width:20%;height:100%;border-radius:5px;background:#9fa492"></div></div></div>
<div style="position:relative;height:15px;margin-top:8px">
  <span style="font-size:11px;color:#969c89;white-space:nowrap;position:absolute;top:50%;left:6%;transform:translateY(-50%)">0:46</span>
  <span style="font-size:11px;color:#9b9186;white-space:nowrap;position:absolute;top:50%;right:6%;transform:translateY(-50%)">-3:06</span>
</div>
<div style="position:relative;height:26px;margin-top:23px">
  <button aria-label="rewind / previous" style="color:#fefefe;position:absolute;top:50%;left:22%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="25" height="25" fill="currentColor"><path d="M22 4l-10 8 10 8zM12 4L2 12l10 8z"/></svg></button>
  <button aria-label="pause" style="color:#fefefe;position:absolute;top:50%;left:50%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><rect x="5" y="3" width="5" height="18" rx="1"/><rect x="14" y="3" width="5" height="18" rx="1"/></svg></button>
  <button aria-label="forward / next" style="color:#fefefe;position:absolute;top:50%;left:78%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M2 4l10 8-10 8zM12 4l10 8-10 8z"/></svg></button>
</div>
<div style="position:relative;height:10px;margin-top:31px">
  <button aria-label="a triangle pointing left" style="color:#94978e;position:absolute;top:50%;left:8%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><path d="M18 3L3 12l15 9z"/></svg></button>
  <div class="bar" style="width:82%;height:5px;border-radius:5px;background:#6a5d48;position:absolute;top:50%;left:11%;transform:translateY(-50%)"><div class="fill" style="width:47%;height:100%;border-radius:5px;background:#95948e"></div></div>
</div>
<div style="position:relative;height:14px;margin-top:22px">
  <button aria-label="icon" style="color:#91908d;position:absolute;top:50%;left:28%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor"><circle cx="12" cy="12" r="8" fill="none" stroke="currentColor" stroke-width="2"/></svg></button>
  <button aria-label="a list or menu" style="color:#95928e;position:absolute;top:50%;left:72%;transform:translate(-50%,-50%)"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><rect x="3" y="5" width="18" height="2.4" rx="1"/><rect x="3" y="11" width="18" height="2.4" rx="1"/><rect x="3" y="17" width="18" height="2.4" rx="1"/></svg></button>
</div>
</body></html>
        """.trimIndent()
    }
}
