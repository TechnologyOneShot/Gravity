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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * App Builder through the real UI: Home → type one sentence → Build my app → App screen with
 * the finished app. Named to run after the other suites (the scripted model is installed only
 * for this class). Uses the same scripted OpenAI-compatible server as AppBuilderDeviceTest.
 */
@RunWith(AndroidJUnit4::class)
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
            Ci.log("builder-ui", "TIMEOUT waiting for $what (${timeoutMs} ms). Title='${runCatching { title() }.getOrDefault("?")}' Screen: ${visibleText().take(1500)}")
            Ci.screenshot("08_timeout_${what.replace(' ', '_')}")
            throw e
        }
    }

    @Test
    fun homeScreenBuildsAnAppFromOneSentence() {
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
