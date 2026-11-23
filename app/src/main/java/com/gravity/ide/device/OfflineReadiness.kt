package com.gravity.ide.device

import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ProjectEntity
import com.gravity.ide.data.db.TestResultEntity
import com.gravity.ide.data.settings.OperatingMode
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.models.ModelManager
import com.gravity.ide.models.ModelStatus
import com.gravity.ide.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Offline Readiness Checker (spec §17). Every item is measured on the device right now:
 * installed models, runtime, memory, storage, toolchain and the selected project's
 * dependency caches. The result says which operations will work without a network.
 */
class OfflineReadinessChecker(
    private val db: GravityDatabase,
    private val localHost: LocalModelHost,
    private val models: ModelManager,
    private val device: DeviceInspector,
    private val network: NetworkMonitor,
    private val settings: SettingsRepository,
    private val events: EventLog,
) {
    data class Check(val name: String, val ready: Boolean, val detail: String, val fix: String? = null)

    data class Operation(val name: String, val offline: Boolean, val reason: String)

    data class Report(val checks: List<Check>, val operations: List<Operation>, val online: Boolean, val createdAt: Long) {
        val readyForLocalAi: Boolean get() = operations.firstOrNull { it.name == "Local AI chat and agents" }?.offline == true
    }

    private fun onPath(tool: String): String? = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", "command -v $tool").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(5, TimeUnit.SECONDS)
        out.takeIf { it.isNotEmpty() && p.exitValue() == 0 }
    } catch (_: Exception) {
        null
    }

    suspend fun check(project: ProjectEntity?, termuxToolchain: Map<String, String>? = null): Report = withContext(Dispatchers.IO) {
        val s = settings.current()
        val info = device.info()
        val checks = mutableListOf<Check>()

        val installed = db.models().all().filter { it.status == ModelStatus.READY.name && File(it.filePath).isFile }
        checks += Check(
            "Downloaded local models", installed.isNotEmpty(),
            if (installed.isEmpty()) "none" else installed.joinToString { "${it.name} (${it.quantization ?: "?"})" },
            if (installed.isEmpty()) "Model Manager → Search Hub → download a small GGUF model while online" else null,
        )
        checks += Check(
            "Local inference runtime", localHost.runtimeAvailable, localHost.runtimeError ?: "llama.cpp bundled",
            if (!localHost.runtimeAvailable) "Install an APK built with -Pgravity.llama=true (docs/OFFLINE_SETUP.md)" else null,
        )
        val loaded = localHost.loadedOrNull()
        val default = installed.firstOrNull { it.isDefault } ?: installed.firstOrNull()
        checks += Check(
            "Model loaded or loadable", loaded != null || (default != null && localHost.runtimeAvailable),
            loaded?.let { "loaded: ${it.name}" } ?: default?.let { "can load ${it.name}" } ?: "nothing to load",
        )
        if (default != null) {
            val ctx = PerformanceManager.contextSize(s, info)
            val warn = localHost.memoryWarning(default, ctx)
            checks += Check("Memory for ${default.name}", warn == null, warn ?: "fits in currently free RAM (${info.availRamBytes / (1024 * 1024)} MB free, ctx $ctx)")
        }
        val freeMb = models.freeBytes() / (1024 * 1024)
        checks += Check("Free storage", freeMb > 1024, "$freeMb MB free", if (freeMb <= 1024) "Free space for build outputs and checkpoints" else null)
        val modeOk = s.operatingMode == OperatingMode.LOCAL_ONLY || (s.operatingMode != OperatingMode.CLOUD_ONLY && s.offlineFallbackToLocal)
        checks += Check(
            "Operating mode allows offline AI", modeOk, "${s.operatingMode.label}${if (s.offlineFallbackToLocal) ", offline fallback on" else ""}",
            if (!modeOk) "Settings → Hybrid Intelligence: choose Local only, or enable offline fallback" else null,
        )

        val java = onPath("java")
        checks += Check("JDK in Gravity's shell", java != null, java ?: "not found", if (java == null) "Android builds need a toolchain: use the Termux companion (Build Center)" else null)
        termuxToolchain?.let { t ->
            checks += Check("Termux JDK", t["java"] != null, t["java"] ?: "not found")
            checks += Check("Termux Gradle", t["gradle"] != null, t["gradle"] ?: "not found")
            checks += Check("Termux Gradle cache", t["cache"]?.startsWith("none") == false, t["cache"] ?: "unknown", "Run one online build in Termux to fill ~/.gradle")
        }

        if (project != null) {
            val root = File(project.rootPath)
            when (project.type) {
                "ANDROID" -> {
                    val wrapper = File(root, "gradle/wrapper/gradle-wrapper.properties").isFile
                    checks += Check("Gradle wrapper in project", wrapper, if (wrapper) "present" else "missing (Termux 'gradle' is used instead)")
                    val offlineFlag = project.buildCommand?.contains("--offline") == true
                    checks += Check("Build command uses --offline", offlineFlag, project.buildCommand ?: "no build command")
                }
                "WEB", "GAME", "APP" -> {
                    val pkg = File(root, "package.json")
                    if (pkg.isFile) {
                        val nodeModules = File(root, "node_modules").isDirectory
                        checks += Check("node_modules cached", nodeModules, if (nodeModules) "present" else "missing", "Run npm install while online (needs Node in a companion environment)")
                    } else {
                        checks += Check("Static web project", true, "no dependencies to download")
                    }
                }
                else -> Unit
            }
        }

        val canLocalAi = localHost.runtimeAvailable && (loaded != null || default != null)
        val androidOffline = (java != null || termuxToolchain?.get("gradle") != null) && termuxToolchain?.get("cache")?.startsWith("none") == false
        val operations = listOf(
            Operation("Local AI chat and agents", canLocalAi && modeOk, if (canLocalAi) "uses the local model" else "needs the runtime and a downloaded model"),
            Operation("Files, editor, project search, checkpoints", true, "fully local"),
            Operation("Terminal", true, "device shell"),
            Operation("Static web / HTML5 game build and preview", true, "built and served from app storage"),
            Operation("Android APK build", androidOffline, if (androidOffline) "toolchain with cached dependencies" else "no runnable toolchain with a warm Gradle cache"),
            Operation("Install, launch, logcat, screenshots, app tests", true, "same-device APIs"),
            Operation("Backups and exports", true, "local storage"),
            Operation("Cloud AI, web search, Hub search, downloads", false, "require a network connection"),
        )
        val report = Report(checks, operations, network.isOnline, System.currentTimeMillis())
        for (c in checks) {
            db.testResults().insert(
                TestResultEntity(suite = "offline-readiness", name = c.name, expected = "Ready", actual = c.detail, outcome = if (c.ready) "PASS" else "FAIL", details = c.fix ?: "", createdAt = report.createdAt)
            )
        }
        events.log(EventCategory.SYSTEM, "Offline readiness: ${checks.count { it.ready }}/${checks.size} ready", operations.joinToString("\n") { "${if (it.offline) "✓" else "✗"} ${it.name}" })
        report
    }
}
