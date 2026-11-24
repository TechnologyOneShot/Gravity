package com.gravity.ide

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.gravity.ide.data.db.MessageEntity
import com.gravity.ide.ui.drawerItems
import com.gravity.ide.ui.titleFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Drives the real app UI on an emulator: launch, dashboard, every navigation-drawer entry,
 * and a chat message with no AI provider configured (which must fail honestly).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiNavigationTest {

    @get:Rule(order = 0)
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun title(): String =
        compose.onNodeWithTag("screen-title").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun waitForTitle(expected: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) { runCatching { title() == expected }.getOrDefault(false) }
    }

    /** Text currently on screen (nodes of the closed drawer sit off-screen and are skipped). */
    private fun visibleText(): String {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode, width: Float) {
            val b = node.boundsInRoot
            if (b.right > 0f && b.left < width) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (it.text.isNotBlank()) out += it.text }
                node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += "[field:${it.text}]" }
            }
            node.children.forEach { walk(it, width) }
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach { root ->
            walk(root, root.size.width.toFloat())
        }
        return out.joinToString(" | ")
    }

    private fun openDrawerEntry(label: String) {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("nav:$label").fetchSemanticsNodes().isEmpty()) {
            // Advanced screens are folded under "Developer tools".
            compose.onNodeWithTag("nav:Developer tools").performScrollTo().performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("nav:$label").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun <T> poll(timeoutMs: Long, block: suspend () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runBlocking { block() }?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    @Test
    fun t01_appLaunchesOnChatHome_andDashboardStillWorks() {
        waitForTitle("Gravity")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("How can I help you today?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-input").assertExists()
        compose.onNodeWithTag("mode-chat").assertExists()
        compose.onNodeWithTag("mode-app").assertExists()
        Thread.sleep(1500)
        compose.waitForIdle()
        Ci.log("launch", "Home: ${visibleText().take(2500)}")
        Ci.screenshot("01_home")

        openDrawerEntry("Dashboard")
        waitForTitle("Dashboard")
        compose.onNodeWithText("Welcome to Gravity").assertExists()
        Ci.log("launch", "Dashboard: ${visibleText().take(2000)}")
        compose.onNodeWithText("Offline readiness →").also { runCatching { it.performScrollTo() } }.performClick()
        waitForTitle("Offline Readiness")
        Thread.sleep(2000)
        compose.waitForIdle()
        Ci.log("launch", "Offline Readiness: ${visibleText().take(2500)}")
        Ci.screenshot("02_offline_readiness")
    }

    @Test
    fun t02_everyDrawerEntryOpens() {
        waitForTitle("Gravity")
        val failures = ArrayList<String>()
        drawerItems.forEachIndexed { i, item ->
            val expected = titleFor(item.route)
            try {
                openDrawerEntry(item.label)
                waitForTitle(expected, 20_000)
                Thread.sleep(1200) // let database / device-probe content arrive
                compose.waitForIdle()
                Ci.log("screen", "OK ${i + 1}/${drawerItems.size} ${item.label} → '${title()}' :: ${visibleText().take(1800)}")
                Ci.screenshot("screen_%02d_%s".format(i + 1, item.label))
            } catch (e: Throwable) {
                val msg = "${item.label}: expected title '$expected', got '${runCatching { title() }.getOrDefault("?")}' — ${e.javaClass.simpleName}: ${e.message?.take(400)}"
                failures += msg
                Ci.log("screen", "FAIL ${i + 1}/${drawerItems.size} $msg")
                Ci.screenshot("screen_%02d_%s_FAILED".format(i + 1, item.label))
            }
        }
        Ci.log("screen", "Drawer entries opened: ${drawerItems.size - failures.size}/${drawerItems.size}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun openNewChat() {
        waitForTitle("Gravity")
        openDrawerEntry("New Chat")
        waitForTitle("Chat")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("How can I help you today?").fetchSemanticsNodes().isNotEmpty() }
    }

    /** The chat composer: + menu with files and photos, the mic and the text box. Ends with the menu open. */
    @Test
    fun t03a_chatComposerHasAttachMenuAndMic() {
        openNewChat()
        compose.onNodeWithTag("model-pill").assertExists()
        compose.onNodeWithTag("mic-button").assertExists()
        compose.onNodeWithTag("chat-input").assertExists()
        compose.onNodeWithTag("attach-button").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Photos and images").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Files (code, text, PDF, ZIP)").assertExists()
        compose.onNodeWithTag("mode-chat").assertExists()
        compose.onNodeWithTag("mode-app").assertExists()
        Ci.screenshot("03a_chat_attach_menu")
    }

    /** The model picker sheet opened from the pill. Ends with the sheet open. */
    @Test
    fun t03b_chatModelPickerSheet() {
        openNewChat()
        compose.onNodeWithTag("model-pill").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Choose AI model").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(800)
        compose.waitForIdle()
        val sheet = visibleText()
        Ci.log("chat-ui", "Model sheet: ${sheet.take(1500)}")
        Ci.screenshot("03b_chat_model_sheet")
        assertTrue("Auto option missing", sheet.contains("Auto (best available)"))
        assertTrue("Phone models section missing", sheet.contains("ON THIS PHONE (OFFLINE)"))
        assertTrue("Cloud section missing", sheet.contains("CLOUD AI"))
    }

    @Test
    fun t03_chatWithoutProviderFailsHonestlyAndPersists() {
        waitForTitle("Gravity")
        val prompt = "hello from CI ${System.currentTimeMillis() % 100000}"
        openDrawerEntry("New Chat")
        waitForTitle("Chat")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(prompt)
        compose.onNodeWithContentDescription("Send").performClick()

        val db = Ci.container.db
        // With a downloaded phone model, Gravity first loads it, then the model writes its answer.
        val reply: MessageEntity? = poll(120_000) {
            val chat = db.chats().all().firstOrNull { it.title == prompt } ?: return@poll null
            db.chats().messages(chat.id).firstOrNull { it.role == "assistant" }
        }
        if (reply == null) {
            val chat = runBlocking { db.chats().all().firstOrNull { it.title == prompt } }
            Ci.log("chat", "No reply after 120 s. Chat found: ${chat != null}; phone model state: ${Ci.container.localHost.state.value}; " +
                "messages: ${chat?.let { runBlocking { db.chats().messages(it.id) }.map { m -> "${m.role}:${m.content.take(80)}|${m.error}" } }}")
            Ci.log("chat", "Chat screen: ${visibleText().take(1500)}")
            Ci.screenshot("03_chat_no_reply")
        }
        assertNotNull("No assistant row was written for the message", reply)
        reply!!
        Ci.log("chat", "Assistant row: content='${reply.content}' error='${reply.error}' model=${reply.model}")
        val phoneModel = Ci.container.localHost.runtimeAvailable && runBlocking { db.models().all() }.any { it.status == "READY" }
        if (phoneModel) {
            // No cloud key, but a downloaded phone model: Gravity loads it by itself and it answers.
            Ci.log("chat", "Answered by the phone model, loaded automatically: ${Ci.container.localHost.loadedOrNull()?.name}")
            assertTrue("The phone model did not answer: ${reply.error}", reply.error.isNullOrBlank() && reply.content.isNotBlank())
        } else {
            assertTrue("A reply was produced with no AI configured: '${reply.content}'", reply.content.isBlank())
            assertTrue("No error was recorded", !reply.error.isNullOrBlank())
        }
        Thread.sleep(800)
        compose.waitForIdle()
        Ci.log("chat", "Chat screen: ${visibleText().take(2000)}")
        Ci.screenshot("03_chat_no_provider")

        // Recreate the activity: the chat must still be listed.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        openDrawerEntry("Chat History")
        waitForTitle("Chat History")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(prompt, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Ci.log("chat", "Chat History after recreate: ${visibleText().take(1500)}")
        Ci.screenshot("04_chat_history_after_recreate")
    }

    /** Regression: closing tabs used to crash Material's tab indicator (IndexOutOfBounds). */
    @Test
    fun t04_closingTerminalTabsDoesNotCrash() {
        waitForTitle("Gravity")
        openDrawerEntry("Terminal")
        waitForTitle("Terminal")
        repeat(2) {
            compose.onNodeWithContentDescription("New terminal").performClick()
            compose.waitForIdle()
        }
        val before = compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().size
        repeat(2) {
            compose.onAllNodesWithContentDescription("Close").onLast().performClick()
            compose.waitForIdle()
            Thread.sleep(300)
            compose.waitForIdle()
        }
        val after = compose.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().size
        Ci.log("tabs", "Terminal tabs: $before → $after after closing two; screen: ${visibleText().take(600)}")
        Ci.screenshot("05_terminal_tabs_closed")
        assertTrue("Tabs were not closed ($before → $after)", after == before - 2)
    }

    /** Phone Models → Check the phone AI: the on-device check runs and reports correct answers (llama.cpp APK). */
    @Test
    fun t05_phoneAiCheckReportsCorrectAnswers() {
        val c = Ci.container
        val hasModel = c.localHost.runtimeAvailable && runBlocking { c.db.models().all() }.any { it.status == "READY" }
        org.junit.Assume.assumeTrue("Needs the llama.cpp runtime and a downloaded model", hasModel)
        waitForTitle("Gravity")
        openDrawerEntry("Phone Models")
        waitForTitle("Phone Models")
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction())
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("phone-ai-check"))
        compose.onNodeWithTag("phone-ai-check").performClick()
        compose.waitUntil(240_000) { compose.onAllNodesWithTag("phone-ai-report").fetchSemanticsNodes().isNotEmpty() }
        val report = compose.onNodeWithTag("phone-ai-report").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        Ci.log("phone-ai-check", report)
        Ci.screenshot("05_phone_ai_check")
        assertTrue("The check did not report correct answers:\n$report", report.contains("answers correctly"))
    }
}
