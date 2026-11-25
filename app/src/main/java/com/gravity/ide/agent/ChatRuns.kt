package com.gravity.ide.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.gravity.ide.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Replies being written, per chat, for the whole app (not per screen): leaving the chat, going
 * home or swiping Gravity away does not stop a reply. While any reply is being written the
 * foreground service keeps Gravity running (with its notification); when one finishes while
 * Gravity is not on screen, a "reply ready" notification says so.
 */
class ChatRuns(private val context: Context) {
    class Run {
        /** The reply so far (null when none is being written). */
        val streaming = MutableStateFlow<String?>(null)
        val busy = MutableStateFlow(false)
        @Volatile var job: Job? = null
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val active = ConcurrentHashMap.newKeySet<String>()

    fun forChat(chatId: String): Run = runs.getOrPut(chatId) { Run() }

    /** A reply started (true) or ended (false) in [chatId]. */
    fun active(chatId: String, on: Boolean) {
        if (on) active.add(chatId) else active.remove(chatId)
        refreshService()
    }

    /**
     * The foreground service is needed only while Gravity is not on screen (on screen Android keeps
     * it running anyway): started when the user leaves during a reply, stopped when they return or
     * the reply ends. So short replies do not flash a notification.
     */
    fun refreshService() = TaskForegroundService.updateChats(context, if (Visibility.onScreen) 0 else active.size)

    /** Tells the user a reply is ready when Gravity is not on screen. */
    fun notifyReady(chatId: String, title: String, preview: String) {
        if (Visibility.onScreen) return
        TaskForegroundService.ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, chatId.hashCode(), Intent(context, MainActivity::class.java).putExtra("open_chat", chatId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, TaskForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { "Gravity replied" })
            .setContentText(preview.take(120))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(5000 + (chatId.hashCode() and 0xFFF), n) }
    }

    /** Whether a Gravity screen is visible (set by MainActivity). */
    object Visibility {
        @Volatile var onScreen = false
    }

    companion object {
        /** A chat to open (from a "reply ready" notification); GravityRoot opens it and clears this. */
        val openChat = MutableStateFlow<String?>(null)
    }
}
