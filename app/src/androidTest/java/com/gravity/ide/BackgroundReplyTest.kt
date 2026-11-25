package com.gravity.ide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gravity.ide.ui.screens.ChatViewModel
import com.gravity.ide.ui.screens.freshChatId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reply goes on when the chat screen closes (the user goes home or swipes Gravity away): the
 * chat's view model is destroyed mid-reply, and the reply must still finish and be saved.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundReplyTest {
    @Test
    fun replyFinishesAfterTheChatScreenCloses() {
        val c = Ci.container
        AppBuilderDeviceTest.installScriptedModel()
        try {
            val chatId = runBlocking {
                val id = freshChatId(c)
                c.db.chats().upsert(c.db.chats().get(id)!!.copy(providerId = "ci-scripted-model", model = "scripted-model"))
                id
            }
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(c, chatId) as T
            })[ChatViewModel::class.java]
            val inst = InstrumentationRegistry.getInstrumentation()
            inst.runOnMainSync { vm.send("CI_SLOW_REPLY hello") }
            Thread.sleep(800)
            val busyWhenClosed = c.chatRuns.forChat(chatId).busy.value
            // The screen closes while the AI is still answering.
            inst.runOnMainSync { store.clear() }
            var reply: com.gravity.ide.data.db.MessageEntity? = null
            val until = System.currentTimeMillis() + 30_000
            while (reply == null && System.currentTimeMillis() < until) {
                reply = runBlocking { c.db.chats().messages(chatId) }.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }
                if (reply == null) Thread.sleep(200)
            }
            Ci.log("background", "Busy when the screen closed: $busyWhenClosed; saved reply: ${reply?.content?.take(120)} | error=${reply?.error}")
            assertTrue("The reply was not running when the screen closed", busyWhenClosed)
            assertNotNull("No reply was saved after the chat screen closed", reply)
            assertTrue("The reply did not finish: ${reply!!.content}", reply.content.contains("The reply finished."))
            assertFalse("The reply was stopped when the screen closed", reply.content.contains("generation stopped"))
            // The busy mark is cleared right after the reply is saved (a moment later than the save).
            val clearBy = System.currentTimeMillis() + 5_000
            while (c.chatRuns.forChat(chatId).busy.value && System.currentTimeMillis() < clearBy) Thread.sleep(50)
            assertFalse("Still marked as busy 5 s after the reply was saved", c.chatRuns.forChat(chatId).busy.value)
        } finally {
            AppBuilderDeviceTest.removeScriptedModel()
        }
    }
}
