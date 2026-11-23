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
 */
class TaskForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (count <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(this, "$count agent task${if (count == 1) "" else "s"} running")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "gravity_tasks"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_COUNT = "count"

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

        fun update(context: Context, runningCount: Int) {
            val intent = Intent(context, TaskForegroundService::class.java).putExtra(EXTRA_COUNT, runningCount)
            try {
                if (runningCount > 0) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            } catch (e: Exception) {
                // Android 12+ forbids starting foreground services from the background; the task
                // still runs (state is persisted) but may be killed if the system needs memory.
                Log.w("Gravity", "Could not start task service: ${e.message}")
            }
        }
    }
}
