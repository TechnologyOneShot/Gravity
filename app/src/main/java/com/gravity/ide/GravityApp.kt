package com.gravity.ide

import android.app.Application
import com.gravity.ide.agent.TaskForegroundService
import com.gravity.ide.data.EventCategory
import com.gravity.ide.di.AppContainer
import kotlinx.coroutines.launch

class GravityApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TaskForegroundService.ensureChannel(this)
        container.appScope.launch {
            // Crash/process-death recovery: anything that was mid-run is marked resumable.
            container.agent.recoverInterrupted()
            container.events.log(EventCategory.SYSTEM, "Gravity started", "Version ${BuildConfig.VERSION_NAME} · local runtime ${if (BuildConfig.LLAMA_BUNDLED) "bundled" else "not bundled"}")
            // Keep the activity log bounded (90 days).
            container.db.events().prune(System.currentTimeMillis() - 90L * 24 * 3600 * 1000)
        }
    }
}
