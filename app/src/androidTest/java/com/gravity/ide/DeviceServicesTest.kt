package com.gravity.ide

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ReasoningMode
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.complete
import com.gravity.ide.build.BuildKind
import com.gravity.ide.core.BackupCrypto
import com.gravity.ide.data.db.AgentTaskEntity
import com.gravity.ide.models.HubFile
import com.gravity.ide.models.ModelStatus
import com.gravity.ide.workspace.ProjectTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Exercises Gravity's real services on an emulator: the built-in self-test, project
 * templates, file operations, builds, export, backup/restore, credential storage, task
 * recovery, agent behaviour without a provider, a real model download and offline mode.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceServicesTest {

    @get:Rule
    val notifications: TestRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }

    private val c get() = Ci.container
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> poll(timeoutMs: Long, block: suspend () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            runBlocking { block() }?.let { return it }
            Thread.sleep(500)
        }
        return null
    }

    @Test
    fun t01_builtInSelfTest() {
        runBlocking {
            val results = c.selfTest.runAll()
            results.forEach { Ci.log("self-test", "${it.outcome} | ${it.name} | expected: ${it.expected} | actual: ${it.actual}") }
            val failed = results.filter { it.outcome == "FAIL" }
            assertTrue(failed.joinToString("\n") { "${it.name}: ${it.actual}\n${it.details}" }, failed.isEmpty())
        }
    }

    @Test
    fun t02_device_and_terminal() {
        runBlocking {
            val info = c.device.info()
            Ci.log("device", "$info")
            c.device.probeCapabilities().forEach { Ci.log("capability", "${if (it.available) "YES" else "NO "} ${it.name}: ${it.evidence}") }
            val shell = c.terminals.ephemeral(ctx.cacheDir, "ci")
            try {
                for (cmd in listOf("id", "uname -a", "pwd", "ls /system/bin | head -5", "which java gradle python3 git || true", "cat /nonexistent-file")) {
                    val r = shell.run(cmd, 15_000)
                    Ci.log("terminal", "$ $cmd → exit ${r.exitCode}${if (r.timedOut) " (timed out)" else ""}\n${r.output.trim().take(600)}")
                    assertFalse("$cmd timed out", r.timedOut)
                }
                assertEquals(1, shell.run("cat /nonexistent-file", 15_000).exitCode)
            } finally {
                shell.close()
            }
        }
    }

    @Test
    fun t03_projectsFromEveryTemplate_andFileOperations() {
        runBlocking {
            for (t in ProjectTemplate.entries) {
                val p = c.workspace.createProject("ci-${t.name.lowercase()}", t)
                val root = File(p.rootPath)
                val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()
                Ci.log("project", "${t.label}: ${files.size} files → ${files.joinToString()}")
                assertTrue("${t.label} wrote no files", files.isNotEmpty())
                assertNotNull(c.db.projects().get(p.id))
            }
            val project = c.db.projects().all().first { it.name == "ci-empty" }
            val root = File(project.rootPath)
            val folder = c.workspace.createDirectory(root, "notes")
            val file = c.workspace.createFile(folder, "todo.md", "- verify on device\n")
            val renamed = c.workspace.rename(file, "done.md")
            assertTrue(renamed.isFile && !file.exists())
            val trashId = c.workspace.delete(renamed, project.id)
            assertFalse(renamed.exists())
            val restored = c.workspace.restoreFromTrash(trashId)
            assertEquals("- verify on device\n", restored.readText())
            val traversal = runCatching { c.workspace.createFile(root, "../escape.txt", "x") }
            assertTrue("Path traversal name was accepted", traversal.isFailure)
            Ci.log("files", "folder+file create, rename, trash, restore OK; '../escape.txt' rejected: ${traversal.exceptionOrNull()?.message}")
        }
    }

    @Test
    fun t04_projectSearch() {
        val android = runBlocking { c.db.projects().all().first { it.name == "ci-android_compose" } }
        val hits = c.workspace.search(File(android.rootPath), "MainActivity")
        hits.take(10).forEach { Ci.log("search", "${it.relPath}:${it.line}  ${it.text}") }
        assertTrue("No search hits for MainActivity", hits.isNotEmpty())
    }

    @Test
    fun t05_builds() {
        runBlocking {
            val projects = c.db.projects().all()
            fun p(name: String) = projects.first { it.name == name }

            val shellBuild = c.builds.build(p("ci-shell"), BuildKind.COMMAND)
            Ci.log("build", "Shell project: ${shellBuild.status} exit=${shellBuild.exitCode} error=${shellBuild.error}\n${shellBuild.log.takeLast(800)}")
            assertEquals("SUCCEEDED", shellBuild.status)
            assertTrue(File(p("ci-shell").rootPath, "dist/build.txt").isFile)

            val web = c.builds.build(p("ci-web_static"), BuildKind.STATIC_WEB)
            Ci.log("build", "Static web: ${web.status} artifact=${web.artifactPath}\n${web.log.takeLast(800)}")
            assertEquals("SUCCEEDED", web.status)
            val artifact = File(web.artifactPath!!)
            assertTrue(artifact.length() > 0)
            ZipFile(artifact).use { z -> assertTrue(z.entries().toList().any { it.name.endsWith("index.html") }) }

            val androidBuild = c.builds.build(p("ci-android_compose"), BuildKind.COMMAND)
            Ci.log("build", "Android project with the plain device shell: ${androidBuild.status} exit=${androidBuild.exitCode} error=${androidBuild.error}\n${androidBuild.log.takeLast(800)}")
            assertEquals("FAILED", androidBuild.status)
            assertNotEquals(0, androidBuild.exitCode)
            assertTrue(androidBuild.log.isNotBlank())

            val kotlin = c.builds.build(p("ci-kotlin_console"), BuildKind.COMMAND)
            Ci.log("build", "Kotlin console: ${kotlin.status} exit=${kotlin.exitCode} error=${kotlin.error}\n${kotlin.log.takeLast(400)}")
            assertEquals("FAILED", kotlin.status)
        }
    }

    @Test
    fun t06_exportZip() {
        runBlocking {
            val project = c.db.projects().all().first { it.name == "ci-web_game" }
            val dest = File(ctx.cacheDir, "ci-export.zip")
            val count = c.workspace.zipToFile(File(project.rootPath), dest)
            val names = ZipFile(dest).use { z -> z.entries().toList().map { it.name } }
            Ci.log("export", "${dest.name}: ${dest.length()} bytes, $count files → $names")
            assertTrue(dest.length() > 0 && names.isNotEmpty())
        }
    }

    @Test
    fun t07_backupRestore_and_credentialsNeverExported() {
        runBlocking {
            val secret = "sk-ci-" + UUID.randomUUID()
            val keyName = "provider:ci-backup-check"
            c.secrets.put(keyName, secret)
            try {
                assertEquals(secret, c.secrets.get(keyName))
                val prefsXml = File(ctx.dataDir, "shared_prefs/gravity_secrets.xml")
                assertTrue("Secret prefs file missing", prefsXml.isFile)
                assertFalse("API key is stored in plain text", prefsXml.readText().contains(secret))

                val backup = File(ctx.cacheDir, "ci-backup.gravity")
                val password = "ci-backup-password"
                val summary = c.backups.createBackup(Uri.fromFile(backup), password.toCharArray())
                Ci.log("backup", "Created $summary")
                assertTrue(summary.encrypted && BackupCrypto.isEncrypted(backup))
                assertTrue(summary.projects > 0)

                val zipBytes = ByteArrayOutputStream().also { BackupCrypto.decrypt(backup, it, password.toCharArray()) }.toByteArray()
                var entries = 0
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        entries++
                        val content = zin.readBytes().toString(Charsets.ISO_8859_1)
                        assertFalse("Backup entry ${e.name} contains the API key", content.contains(secret))
                    }
                }
                Ci.log("backup", "Decrypted backup has $entries entries; none contains the stored API key")

                val wrong = runCatching { c.backups.restore(Uri.fromFile(backup), "wrong-password".toCharArray()) }
                assertTrue("Wrong backup password was accepted", wrong.isFailure)

                val before = c.db.projects().all().size
                val restored = c.backups.restore(Uri.fromFile(backup), password.toCharArray())
                val after = c.db.projects().all().size
                Ci.log("backup", "Restored $restored; projects $before → $after; wrong password: ${wrong.exceptionOrNull()?.message}")
                assertEquals(before + restored.projects, after)
            } finally {
                c.secrets.remove(keyName)
            }
        }
    }

    @Test
    fun t08_interruptedTaskIsRecovered() {
        runBlocking {
            val project = c.db.projects().all().first()
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            c.db.tasks().upsert(
                AgentTaskEntity(
                    id = id, projectId = project.id, title = "CI interrupted task", instruction = "x", status = "RUNNING",
                    providerId = null, model = null, reasoning = ReasoningMode.BALANCED.name, createdAt = now, updatedAt = now,
                )
            )
            c.agent.recoverInterrupted()
            val status = c.db.tasks().get(id)?.status
            Ci.log("recovery", "Task left RUNNING → after recovery: $status")
            assertEquals("INTERRUPTED", status)
            c.agent.delete(id)
        }
    }

    @Test
    fun t09_agentWithoutProviderDoesNotFakeSuccess() {
        val project = runBlocking { c.db.projects().all().first { it.name == "ci-empty" } }
        val id = runBlocking { c.agent.create(project.id, "Add a README section", null, null, ReasoningMode.FAST, null) }
        c.agent.start(id)
        val settled = poll(30_000) {
            c.db.tasks().get(id)?.takeIf { it.status !in setOf("PENDING", "RUNNING") }
        }
        val t = runBlocking { c.db.tasks().get(id) }
        Ci.log("agent", "No provider configured → status=${t?.status} stage=${t?.stage} error=${t?.error}")
        assertNotNull("Task never left PENDING/RUNNING", settled)
        assertNotEquals("COMPLETED", t?.status)
        c.agent.cancel(id)
    }

    @Test
    fun t10_offlineReadinessReport() {
        runBlocking {
            val r = c.offlineReadiness.check(null)
            r.checks.forEach { Ci.log("offline-readiness", "${if (it.ready) "READY" else "MISSING"} ${it.name}: ${it.detail}${it.fix?.let { f -> " (fix: $f)" } ?: ""}") }
            r.operations.forEach { Ci.log("offline-readiness", "${if (it.offline) "offline OK " else "needs net  "} ${it.name}: ${it.reason}") }
            assertTrue(r.checks.isNotEmpty())
        }
    }

    @Test
    fun t11_sameDeviceAppLaunch() {
        // Start from Gravity in the foreground, as a user would.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        runBlocking {
            val target = "com.android.settings"
            val result = c.appTests.run(target, 3, capture = false)
            val resumed = Ci.shell("dumpsys activity activities").lineSequence().filter { "ResumedActivity" in it }.joinToString(" / ") { it.trim() }
            Ci.log("app-test", "${result.result.outcome}: ${result.result.actual} | resumed: $resumed")
            assertEquals("PASS", result.result.outcome)
            assertTrue("Settings is not the resumed activity: $resumed", resumed.contains(target))
            Ci.shell("am force-stop $target")
        }
        scenario.close()
    }

    @Test
    fun t12_realModelDownloadWithChecksum() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val candidates = listOf("ggml-org/models", "ggml-org/tiny-llamas")
            var picked: Pair<String, HubFile>? = null
            for (repo in candidates) {
                val files = runCatching { runBlocking { c.models.listHubFiles(repo) } }
                    .onFailure { Ci.log("download", "$repo: ${it.message}") }
                    .getOrNull() ?: continue
                val usable = files.filter { it.sha256 != null && it.size in 1L..60_000_000L && !it.path.contains("-be.") && !it.path.contains("vocab") }
                val f = usable.firstOrNull { "stories" in it.path } ?: usable.firstOrNull()
                if (f != null) { picked = repo to f; break }
            }
            assertNotNull("No small GGUF with a published SHA-256 was found on the Hub", picked)
            val (repo, file) = picked!!
            Ci.log("download", "Downloading $repo/${file.path} (${file.size} bytes, sha256 ${file.sha256})")
            val entity = runBlocking { c.models.enqueueHubDownload(repo, file, null) }
            val done = poll(8 * 60_000L) {
                c.db.models().get(entity.id)?.takeIf { it.status == ModelStatus.READY.name || it.status == ModelStatus.FAILED.name }
            }
            val m = runBlocking { c.db.models().get(entity.id) }
            Ci.log("download", "Result: status=${m?.status} bytes=${m?.downloadedBytes}/${m?.sizeBytes} expected=${m?.sha256Expected} actual=${m?.sha256Actual} arch=${m?.architecture} quant=${m?.quantization} ctx=${m?.contextLength} error=${m?.error}")
            assertNotNull("Download did not finish in time (last status ${m?.status})", done)
            assertEquals(ModelStatus.READY.name, m!!.status)
            assertEquals(file.sha256!!.lowercase(), m.sha256Actual?.lowercase())
            assertEquals(file.size, File(m.filePath).length())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun t12b_localModelLoadsAndGenerates() {
        if (!c.localHost.runtimeAvailable) {
            Ci.log("local-ai", "Skipped: ${c.localHost.runtimeError}")
            Assume.assumeTrue("llama.cpp runtime is not bundled in this build", false)
        }
        val model = runBlocking { c.db.models().all().firstOrNull { it.status == ModelStatus.READY.name } }
        assertNotNull("No verified model to load (the download test must pass first)", model)
        val loaded = runBlocking { c.localHost.load(model!!, 512, 2) }
        Ci.log("local-ai", "Loaded ${loaded.name} (context ${loaded.contextSize}) with llama.cpp")
        val start = System.currentTimeMillis()
        val r = runBlocking {
            c.providers.local.complete(LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Once upon a time")), maxOutputTokens = 32))
        }
        Ci.log("local-ai", "Generated ${r.outputTokens} tokens in ${System.currentTimeMillis() - start} ms (finish=${r.finishReason}): ${r.text.take(300)}")
        assertTrue("The local model produced no tokens", r.outputTokens > 0)
    }

    @Test
    fun t13_offlineMode() {
        val project = runBlocking { c.db.projects().all().first { it.name == "ci-android_compose" } }
        Ci.shell("cmd connectivity airplane-mode enable")
        try {
            val offline = poll(30_000) { if (!c.network.isOnline) true else null }
            Ci.log("offline", "Airplane mode on → app reports online=${c.network.isOnline} (${c.network.state.value.transport})")
            assertNotNull("App still reports online after airplane mode was enabled", offline)
            val hits = c.workspace.search(File(project.rootPath), "setContent")
            Ci.log("offline", "Project search while offline: ${hits.size} hits")
            assertTrue(hits.isNotEmpty())
            val shell = c.terminals.ephemeral(ctx.cacheDir, "ci-offline")
            try {
                val r = runBlocking { shell.run("echo offline-ok", 15_000) }
                assertEquals(0, r.exitCode)
            } finally {
                shell.close()
            }
            val local = c.localHost.loadedOrNull()
            val chat = runCatching { runBlocking { c.providers.resolve(null, null) } }
            Ci.log("offline", "AI request while offline (local model loaded: ${local?.name}) → ${chat.exceptionOrNull()?.message ?: "routed to ${chat.getOrNull()?.model} (local=${chat.getOrNull()?.provider?.isLocal})"}")
            if (local == null) {
                assertTrue("An AI request succeeded offline with no local model", chat.isFailure)
            } else {
                val resolved = chat.getOrThrow()
                assertTrue("Offline request was not routed to the local model", resolved.provider.isLocal)
                val r = runBlocking { resolved.provider.complete(LlmRequest(resolved.model, listOf(LlmMessage(Role.USER, "The little dog")), maxOutputTokens = 16)) }
                Ci.log("offline", "Local generation in airplane mode: ${r.outputTokens} tokens: ${r.text.take(200)}")
                assertTrue(r.outputTokens > 0)
            }
        } finally {
            Ci.shell("cmd connectivity airplane-mode disable")
            runBlocking { c.localHost.unload() }
        }
        val online = poll(60_000) { if (c.network.isOnline) true else null }
        Ci.log("offline", "Airplane mode off → online=${online == true}")
    }
}
