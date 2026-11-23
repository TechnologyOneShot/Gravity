package com.gravity.ide.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gravity.ide.MainActivity
import com.gravity.ide.R

/**
 * Keeps the process alive while agent tasks run so long tasks are not killed when the user
 * switches apps. Task state is persisted after every step regardless.
 *
 * Android requires every startForegroundService() to be followed by startForeground() within
 * a few seconds, even if the work already finished; stopping the service before that crashes the
 * app (ForegroundServiceDidNotStartInTimeException). So: the service is never stopped from the
 * outside. Each start always calls startForeground() first and then stops itself if no task is
 * running any more; while it runs, updates go straight to the live instance.
 */
class TaskForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Always satisfy the startForeground contract first, whatever the current count is.
        val shown = try {
            val text = runningText(maxOf(desiredCount, 1))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(this, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification(this, text))
            }
            true
        } catch (e: Exception) {
            Log.w("Gravity", "Task service could not enter the foreground: ${e.message}")
            false
        }
        startedForeground = shown
        if (!shown || desiredCount <= 0) stopNow() else refresh()
        return START_NOT_STICKY
    }

    /** Android 15+: data-sync foreground time is capped; stop cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("Gravity", "Task service reached Android's foreground time limit; tasks keep running in the background")
        stopNow()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        startedForeground = false
        super.onDestroy()
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(this, runningText(desiredCount)))
    }

    private var lastStartId = 0

    /**
     * Leaves the foreground and stops, unless a newer start is already queued: stopSelf(startId)
     * is ignored then, and that start's onStartCommand enters the foreground again.
     */
    private fun stopNow() {
        startedForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    companion object {
        const val CHANNEL_ID = "gravity_tasks"
        private const val NOTIFICATION_ID = 4101

        @Volatile private var desiredCount = 0
        @Volatile private var instance: TaskForegroundService? = null
        @Volatile private var startedForeground = false

        private fun runningText(count: Int) = "$count agent task${if (count == 1) "" else "s"} running"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_tasks), NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun notification(context: Context, text: String): Notification {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Gravity")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        }

        /** Called whenever the number of running agent tasks changes. Never throws. */
        fun update(context: Context, runningCount: Int) {
            desiredCount = runningCount
            // Post to the main thread: onStartCommand also runs there, so the two never interleave.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val live = instance
                when {
                    live != null && startedForeground -> if (desiredCount > 0) live.refresh() else live.stopNow()
                    // Not in the foreground (yet): start. An extra start is harmless, because every
                    // onStartCommand enters the foreground first; a pending start with nothing left
                    // to run stops itself after doing so.
                    desiredCount > 0 -> start(context)
                }
            }
        }

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, TaskForegroundService::class.java))
            } catch (e: Exception) {
                // Android 12+ forbids starting foreground services from the background; the task
                // still runs (state is persisted) but may be killed if the system needs memory.
                Log.w("Gravity", "Could not start task service: ${e.message}")
            }
        }
    }
}
