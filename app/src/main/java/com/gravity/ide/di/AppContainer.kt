package com.gravity.ide.di

import android.content.Context
import com.gravity.ide.agent.AgentEngine
import com.gravity.ide.agent.ApprovalGate
import com.gravity.ide.ai.ProviderRegistry
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.backup.BackupManager
import com.gravity.ide.build.BuildEngine
import com.gravity.ide.capture.ScreenshotStore
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.device.AppInstaller
import com.gravity.ide.device.DeviceInspector
import com.gravity.ide.device.LogcatReader
import com.gravity.ide.models.ModelManager
import com.gravity.ide.network.NetworkMonitor
import com.gravity.ide.search.WebSearchService
import com.gravity.ide.security.SecretStore
import com.gravity.ide.terminal.TerminalManager
import com.gravity.ide.testing.AppTestRunner
import com.gravity.ide.testing.SelfTestRunner
import com.gravity.ide.workspace.CheckpointManager
import com.gravity.ide.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection: one instance of each service for the process, created in
 * dependency order. (A DI framework adds annotation processing to the build without
 * changing behaviour; this keeps the graph explicit and easy to audit.)
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> android.util.Log.e("Gravity", "Uncaught in appScope", e) }
    )

    val db: GravityDatabase = GravityDatabase.create(appContext)
    val events = EventLog(db.events(), appScope)
    val settings = SettingsRepository(appContext)
    val secrets = SecretStore(appContext)
    val network = NetworkMonitor(appContext, events)

    val workspace = WorkspaceManager(appContext, db, events)
    val checkpoints = CheckpointManager(appContext, db, events)
    val terminals = TerminalManager(appContext, appScope, events)

    val localHost = LocalModelHost(appContext, events)
    val providers = ProviderRegistry(db, secrets, settings, network, localHost, events)
    val models = ModelManager(appContext, db, secrets, localHost, events)
    val search = WebSearchService(settings, secrets, network, events)

    val termux = com.gravity.ide.build.TermuxBridge(appContext, events)
    val builds = BuildEngine(appContext, db, terminals, events, workspace, settings, termux)
    val device = DeviceInspector(appContext)
    val installer = AppInstaller(appContext, events)
    val logcat = LogcatReader()
    val screenshots = ScreenshotStore(appContext, db, events)
    val backups = BackupManager(appContext, db, workspace, events)
    val appTests = AppTestRunner(db, installer, logcat, screenshots, providers, events)

    val approvals = ApprovalGate()
    val agent = AgentEngine(
        appContext, db, providers, localHost, workspace, checkpoints, terminals, search, approvals, settings, network, events, appScope,
        builds = builds, installer = installer, appTests = appTests,
    )
    /** One-line app creation and change requests (Home screen). */
    val appBuilder = com.gravity.ide.agent.AppBuilder(db, workspace, agent, checkpoints, events)
    /** Packages App Builder projects as installable APKs on the phone. */
    val packager = com.gravity.ide.build.WebAppPackager(appContext, events)

    val selfTest = SelfTestRunner(appContext, db, secrets, workspace, checkpoints, terminals, network, providers, localHost, settings, events)
    val offlineReadiness = com.gravity.ide.device.OfflineReadinessChecker(db, localHost, models, device, network, settings, events)
}
