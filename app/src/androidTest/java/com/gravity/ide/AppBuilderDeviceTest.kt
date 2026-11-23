package com.gravity.ide

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.gravity.ide.ai.ProviderKind
import com.gravity.ide.build.WebAppPackager
import com.gravity.ide.data.db.ProviderEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * End-to-end App Builder check on the emulator. The AI is a scripted OpenAI-compatible server
 * on 127.0.0.1 (no real model is available in CI): Gravity talks to it over real HTTP/SSE and
 * the agent executes the returned tool calls for real. Everything after the model — project,
 * files, change, undo, APK packaging, signing, install and launch — is the production path.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppBuilderDeviceTest {

    @get:Rule(order = 0)
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val c get() = Ci.container
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> poll(timeoutMs: Long, block: suspend () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runBlocking { block() }?.let { return it }
            Thread.sleep(400)
        }
        return null
    }

    private fun finished(taskId: String) = poll(120_000) { c.db.tasks().get(taskId)?.takeIf { it.status !in ACTIVE } }

    @Test
    fun t01_buildChangeUndoPackageInstallLaunch() {
        val (project, buildId) = runBlocking { c.appBuilder.create("music player bana do") }
        assertEquals("Music Player", project.name)
        val built = finished(buildId)
        Ci.log("builder", "Build run → ${built?.status} after ${built?.stepCount} steps; summary=${built?.summary}; error=${built?.error}")
        assertEquals("COMPLETED", built?.status)
        val index = File(project.rootPath, "index.html")
        assertTrue(index.readText().contains("CI Music Player"))

        val changeId = runBlocking { c.appBuilder.change(project, "add a shuffle button") }
        val changed = finished(changeId)
        Ci.log("builder", "Change run → ${changed?.status}; summary=${changed?.summary}; error=${changed?.error}")
        assertEquals("COMPLETED", changed?.status)
        assertTrue(index.readText().contains("id=\"shuffle\""))

        val restored = runBlocking { c.appBuilder.undoLast(project) }
        Ci.log("builder", "Undo restored $restored file(s); shuffle present: ${index.readText().contains("id=\"shuffle\"")}")
        assertTrue(restored >= 1)
        assertFalse(index.readText().contains("id=\"shuffle\""))
        assertTrue(index.readText().contains("CI Music Player"))

        val out = runBlocking { c.packager.build(c.db.projects().get(project.id)!!) }
        Ci.log("builder", "APK ${out.apk.name}: ${out.bytes} bytes, package ${out.packageName}, label '${out.label}', ${out.webFiles} web files")
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(out.apk.path, 0)
        assertNotNull("Android cannot parse the generated APK", info)
        assertEquals(out.packageName, info!!.packageName)
        val appInfo = info.applicationInfo!!.apply { sourceDir = out.apk.path; publicSourceDir = out.apk.path }
        assertEquals("Music Player", ctx.packageManager.getApplicationLabel(appInfo).toString())

        val staged = File(ctx.getExternalFilesDir(null), "ci-generated-app.apk")
        out.apk.copyTo(staged, overwrite = true)
        val install = Ci.shell("pm install -r ${staged.absolutePath}").trim()
        Ci.log("builder", "pm install → $install")
        assertTrue("Install failed: $install", install.contains("Success"))
        try {
            Ci.shell("am start -W -n ${out.packageName}/${WebAppPackager.SHELL_ACTIVITY}")
            val loaded = poll(30_000) {
                Ci.shell("logcat -d -s GravityWebShell:I").lineSequence().firstOrNull { "title=CI Music Player" in it }
            }
            val resumed = Ci.shell("dumpsys activity activities").lineSequence().filter { "ResumedActivity" in it }.joinToString(" / ") { it.trim() }
            Ci.log("builder", "Installed app launched: ${loaded ?: "no page-load log"} | resumed: $resumed")
            Ci.screenshot("06_generated_app_installed")
            assertNotNull("The installed app did not load its page", loaded)
            assertTrue(resumed.contains(out.packageName))
        } finally {
            Ci.shell("am force-stop ${out.packageName}")
            Ci.shell("pm uninstall ${out.packageName}")
            staged.delete()
        }
    }

    @Test
    fun t02_homeScreenBuildsAnAppFromOneSentence() {
        fun title() = compose.onNodeWithTag("screen-title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        compose.waitUntil(15_000) { runCatching { title() == "Gravity" }.getOrDefault(false) }
        compose.onNodeWithTag("build-prompt").performTextInput("calculator bana do")
        compose.onNodeWithTag("build-button").performClick()
        compose.waitUntil(20_000) { runCatching { title() == "App" }.getOrDefault(false) }
        compose.waitUntil(120_000) { compose.onAllNodesWithTag("app-summary").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1500)
        compose.waitForIdle()
        val summary = compose.onNodeWithTag("app-summary").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        Ci.log("builder-ui", "Home → Build → App screen; summary shown: $summary")
        Ci.screenshot("07_app_screen_after_build")
        assertTrue(compose.onAllNodesWithTag("install-app").fetchSemanticsNodes().isNotEmpty())
        val app = runBlocking { c.db.projects().all().firstOrNull { it.name == "Calculator" && it.type == "APP" } }
        assertNotNull(app)
    }

    companion object {
        private val ACTIVE = setOf("PENDING", "RUNNING", "WAITING_APPROVAL", "WAITING_NETWORK")
        private const val PROVIDER_ID = "ci-scripted-model"
        private lateinit var server: MockWebServer

        private val APP_V1 = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>CI Music Player</title>
              <style>body{font-family:sans-serif;padding:16px}button{font-size:20px;padding:12px 18px}</style>
            </head>
            <body>
              <h1>CI Music Player</h1>
              <input id="files" type="file" accept="audio/*" multiple />
              <audio id="player" controls></audio>
              <div><button id="play">Play / Pause</button><!--controls--></div>
              <script>
                const files = document.getElementById('files'), player = document.getElementById('player');
                let list = [];
                files.addEventListener('change', () => { list = [...files.files]; if (list.length) player.src = URL.createObjectURL(list[0]); });
                document.getElementById('play').onclick = () => player.paused ? player.play() : player.pause();
                console.log('music player ready');
              </script>
            </body>
            </html>
        """.trimIndent()

        private fun sse(vararg chunks: String): MockResponse = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody((chunks.toList() + "[DONE]").joinToString("") { "data: $it\n\n" })

        private fun text(t: String) = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", t)).put("finish_reason", "stop"))).toString()

        private fun toolCall(name: String, args: JSONObject) = JSONObject().put(
            "choices", JSONArray().put(
                JSONObject().put(
                    "delta", JSONObject().put(
                        "tool_calls", JSONArray().put(
                            JSONObject().put("index", 0).put("id", "call_${System.nanoTime()}").put("type", "function")
                                .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
                        )
                    )
                ).put("finish_reason", "tool_calls")
            )
        ).toString()

        /** Plays the model: write the app, then finish; for a change, edit the file, then finish. */
        private object ScriptedModel : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().endsWith("/models")) return MockResponse().setBody("""{"data":[{"id":"scripted-model"}]}""")
                val body = request.body.readUtf8()
                if (!body.contains("\"tools\"")) return sse(text("1. Write index.html with the player. 2. Finish."))
                val toolResults = Regex("\"role\"\\s*:\\s*\"tool\"").findAll(body).count()
                val isChange = body.contains("Change request for this app")
                return when {
                    toolResults == 0 && !isChange -> sse(toolCall("write_file", JSONObject().put("path", "index.html").put("content", APP_V1)))
                    toolResults == 0 -> sse(
                        toolCall(
                            "replace_in_file",
                            JSONObject().put("path", "index.html").put("old_text", "<!--controls-->").put("new_text", "<button id=\"shuffle\">Shuffle</button>"),
                        )
                    )
                    else -> sse(toolCall("finish", JSONObject().put("summary", if (isChange) "Added a shuffle button." else "Built a music player: choose songs, then play or pause.")))
                }
            }
        }

        @BeforeClass
        @JvmStatic
        fun startScriptedModel() {
            server = MockWebServer().apply { dispatcher = ScriptedModel; start() }
            runBlocking {
                Ci.container.db.providers().upsert(
                    ProviderEntity(
                        id = PROVIDER_ID, name = "Scripted CI model", kind = ProviderKind.OPENAI_COMPAT.name,
                        baseUrl = server.url("/v1").toString().trimEnd('/'), defaultModel = "scripted-model", createdAt = System.currentTimeMillis(),
                    )
                )
                Ci.container.settings.update { it.copy(defaultProviderId = PROVIDER_ID, defaultModel = "scripted-model") }
            }
        }

        @AfterClass
        @JvmStatic
        fun removeScriptedModel() {
            // Other suites check behaviour with no provider configured.
            runBlocking {
                Ci.container.db.providers().delete(PROVIDER_ID)
                Ci.container.settings.update { it.copy(defaultProviderId = null, defaultModel = null) }
            }
            server.shutdown()
        }
    }
}
