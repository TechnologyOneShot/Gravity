package com.gravity.ide

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * App Builder through the real UI: Home → type one sentence → Build my app → App screen with
 * the finished app. Named to run after the other suites (the scripted model is installed only
 * for this class). Uses the same scripted OpenAI-compatible server as AppBuilderDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ZzAppBuilderUiTest {

    @get:Rule(order = 0)
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun title(): String =
        compose.onNodeWithTag("screen-title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun visibleText(): String {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            if (node.boundsInRoot.right > 0f) node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotBlank()) out += it.text }
            node.children.forEach { walk(it) }
        }
        runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { walk(it) } }
        return out.joinToString(" | ")
    }

    /** Waits, and on timeout logs what the screen showed before failing. */
    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        try {
            compose.waitUntil(timeoutMs) { runCatching(condition).getOrDefault(false) }
            Ci.log("builder-ui", "$what after ${System.currentTimeMillis() - start} ms")
        } catch (e: Throwable) {
            Ci.log("builder-ui", "FAILED waiting for $what (${timeoutMs} ms): ${android.util.Log.getStackTraceString(e).take(2500)}")
            Ci.log("builder-ui", "Title='${runCatching { title() }.getOrDefault("?")}' Screen: ${visibleText().take(1200)}")
            Ci.screenshot("08_timeout_${what.replace(' ', '_')}")
            throw e
        }
    }

    /** Opens an existing app by tapping it (no coroutine involved in the navigation). */
    @Test
    fun a_openExistingAppFromMyApps() {
        val project = runBlocking { Ci.container.workspace.createProject("Tap Test App", com.gravity.ide.workspace.ProjectTemplate.WEB_APP) }
        waitFor("home screen", 30_000) { title() == "Gravity" }
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("nav:My Apps").performScrollTo().performClick()
        waitFor("My Apps", 30_000) { title() == "My Apps" }
        waitFor("app row", 30_000) { compose.onAllNodesWithText(project.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(project.name)[0].performClick()
        waitFor("App screen via tap", 60_000) { title() == "App" }
        waitFor("preview", 30_000) { compose.onAllNodesWithTag("app-preview").fetchSemanticsNodes().isNotEmpty() }
        Ci.log("builder-ui", "Opened ${project.name} from My Apps: ${visibleText().take(600)}")
        Ci.screenshot("07a_app_screen_from_my_apps")
    }

    @Test
    fun b_homeScreenBuildsAnAppFromOneSentence() {
        waitFor("home screen", 30_000) { title() == "Gravity" }
        waitFor("AI provider shown", 30_000) { compose.onAllNodesWithText("Scripted CI model", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("build-prompt").performTextInput("calculator bana do")
        compose.onNodeWithTag("build-button").performClick()
        waitFor("App screen", 60_000) { title() == "App" }
        waitFor("finished app", 180_000) { compose.onAllNodesWithTag("app-summary").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2000)
        compose.waitForIdle()
        val summary = compose.onNodeWithTag("app-summary").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        Ci.log("builder-ui", "Home → Build → App screen; summary shown: $summary; screen: ${visibleText().take(800)}")
        Ci.screenshot("07_app_screen_after_build")
        assertTrue(compose.onAllNodesWithTag("install-app").fetchSemanticsNodes().isNotEmpty())
        val app = runBlocking { Ci.container.db.projects().all().firstOrNull { it.name == "Calculator" && it.type == "APP" } }
        assertNotNull(app)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun start() = AppBuilderDeviceTest.installScriptedModel()

        @AfterClass
        @JvmStatic
        fun stop() = AppBuilderDeviceTest.removeScriptedModel()
    }
}
