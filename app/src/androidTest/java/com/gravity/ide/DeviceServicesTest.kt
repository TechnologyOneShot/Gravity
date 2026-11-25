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

        // Model facts for memory planning, and a request with its own context size, a reply prefix and a stop string.
        val info = com.gravity.ide.ai.local.LlamaBridge.modelInfo(loaded.handle).toList()
        Ci.log("local-ai", "modelInfo [ctx_train, layers, embd, heads, kv_heads] = $info; long-answer context: ${c.localHost.contextForLongAnswer(loaded, 8192)}")
        assertTrue("modelInfo returned no layer count: $info", info[1] > 0)
        val r2 = runBlocking {
            c.providers.local.complete(
                LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Tell a short story.")), maxOutputTokens = 96, contextSize = 1024, assistantPrefix = "Tim", stop = listOf(".")),
            )
        }
        Ci.log("local-ai", "Prefix+stop request: ${r2.outputTokens} tokens, finish=${r2.finishReason}: ${r2.text.take(300)}")
        assertTrue("The reply does not start with the prefix", r2.text.startsWith("Tim"))
        if ("." in r2.text) assertTrue("Generation did not stop at the stop string", r2.text.substringAfter(".").length <= 12)
    }

    /**
     * The crash a user hit: the model was unloaded (or another one loaded) while it was still
     * writing a reply, and the engine's threads read freed memory. Now unloading stops the reply,
     * waits for it, then frees the model; the app must still be alive and the reply must end.
     */
    @Test
    fun t12bb_unloadWhileWritingDoesNotCrash() {
        if (!c.localHost.runtimeAvailable) {
            Ci.log("local-ai", "Skipped: ${c.localHost.runtimeError}")
            Assume.assumeTrue("llama.cpp runtime is not bundled in this build", false)
        }
        val model = runBlocking { c.db.models().all().firstOrNull { it.status == ModelStatus.READY.name } }
        assertNotNull("No verified model to load (the download test must pass first)", model)
        val loaded = runBlocking { c.localHost.load(model!!, 512, 2) }
        val pieces = java.util.concurrent.atomic.AtomicInteger(0)
        val result = java.util.concurrent.atomic.AtomicReference<String>("running")
        val writer = Thread {
            val r = runCatching {
                runBlocking {
                    c.providers.local.stream(LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Write a very long story about a cat.")), maxOutputTokens = 400)) { ev ->
                        if (ev is com.gravity.ide.ai.LlmEvent.TextDelta) pieces.incrementAndGet()
                    }
                }
            }
            result.set(r.exceptionOrNull()?.let { "error: ${it.message}" } ?: "ended")
        }
        writer.start()
        // Unload as soon as the reply has started.
        val started = System.currentTimeMillis()
        while (pieces.get() < 3 && System.currentTimeMillis() - started < 60_000) Thread.sleep(20)
        val before = pieces.get()
        runBlocking { c.localHost.unload() }
        writer.join(60_000)
        Ci.log("local-ai", "Unloaded while writing: ${before} pieces before unload, ${pieces.get()} in total, reply ${result.get()}, state ${c.localHost.state.value}")
        assertTrue("The reply had not started before the unload", before >= 3)
        assertTrue("The reply did not end after the unload", !writer.isAlive)
        assertTrue("The model is still loaded", c.localHost.loadedOrNull() == null)
        assertTrue("The reply kept going after the unload (${pieces.get()} pieces)", pieces.get() < 400)
        // A new reply after loading again works.
        val again = runBlocking { c.localHost.load(model!!, 512, 2) }
        val r = runBlocking { c.providers.local.complete(LlmRequest(again.name, listOf(LlmMessage(Role.USER, "Once upon a time")), maxOutputTokens = 8)) }
        assertTrue("No reply after loading again", r.outputTokens > 0)
    }

    /**
     * App Builder on the real on-device model (llama.cpp, the tiny CI model): whatever the model
     * writes, Gravity must never report an app as built unless a real page was written. A tiny
     * story model cannot write HTML, so the expected result is an honest failure with the
     * starter page untouched and packaging refused.
     */
    @Test
    fun t12c_appBuilderOnPhoneModelNeverFakesSuccess() {
        if (!c.localHost.runtimeAvailable) {
            Ci.log("builder-local", "Skipped: ${c.localHost.runtimeError}")
            Assume.assumeTrue("llama.cpp runtime is not bundled in this build", false)
        }
        val cloud = runBlocking { c.db.providers().all().filter { it.enabled } }
        Assume.assumeTrue("A cloud provider is configured, so App Builder would not use the phone model", cloud.isEmpty())
        val model = runBlocking { c.db.models().all().firstOrNull { it.status == ModelStatus.READY.name } }
        assertNotNull("No verified model to load (the download test must pass first)", model)
        val loaded = runBlocking { c.localHost.unload(); c.localHost.load(model!!, 2048, 2) }
        Ci.log("builder-local", "Loaded ${loaded.name} with context ${loaded.contextSize}")
        val (project, taskId) = runBlocking { c.appBuilder.create("a simple music player") }
        val finished = setOf("COMPLETED", "FAILED", "PAUSED", "CANCELLED")
        val task = poll(6 * 60_000L) { c.db.tasks().get(taskId)?.takeIf { it.status in finished } }
        assertNotNull("The App Builder run on the phone model did not finish", task)
        task!!
        val index = File(project.rootPath, "index.html").readText()
        val packaged = runCatching { runBlocking { c.packager.build(project) } }
        Ci.log(
            "builder-local",
            "status=${task.status} tokens=${task.inputTokens}/${task.outputTokens} error=${task.error} summary=${task.summary} " +
                "index=${index.length} chars, starter page=${com.gravity.ide.agent.AppBuilder.isPlaceholder(index)}, " +
                "packaging=${packaged.exceptionOrNull()?.message ?: "ok ${packaged.getOrNull()?.bytes} bytes"}",
        )
        if (task.status == "COMPLETED") {
            assertFalse("Completed but index.html is still the starter page", com.gravity.ide.agent.AppBuilder.isPlaceholder(index))
            assertNotNull("Completed but index.html is not a complete page", com.gravity.ide.agent.AppBuilder.extractHtml(index))
            assertTrue("A completed app could not be packaged: ${packaged.exceptionOrNull()?.message}", packaged.isSuccess)
        } else {
            assertEquals("FAILED", task.status)
            assertFalse("The failure has no explanation", task.error.isNullOrBlank())
            assertTrue("A failed build changed the starter page", com.gravity.ide.agent.AppBuilder.isPlaceholder(index))
            assertTrue("An unbuilt app was packaged", packaged.isFailure)
        }
    }

    /**
     * A real instruct model (Qwen2.5-0.5B-Instruct Q4_K_M, quantized like the models users run)
     * through the app's own download, load and chat path: the answer must be correct, not just
     * "some tokens". Catches prompt-format and inference bugs that a tiny story model hides.
     */
    @Test
    fun t12d_realInstructModelAnswersCorrectly() {
        if (!c.localHost.runtimeAvailable) {
            Ci.log("local-qwen", "Skipped: ${c.localHost.runtimeError}")
            Assume.assumeTrue("llama.cpp runtime is not bundled in this build", false)
        }
        val repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF"
        val file = runBlocking { c.models.listHubFiles(repo) }.firstOrNull { it.path == "qwen2.5-0.5b-instruct-q4_k_m.gguf" }
        assertNotNull("qwen2.5-0.5b-instruct-q4_k_m.gguf not found in $repo", file)
        Ci.log("local-qwen", "Downloading $repo/${file!!.path} (${file.size} bytes, sha256 ${file.sha256})")
        val entity = runBlocking { c.models.enqueueHubDownload(repo, file, null) }
        val done = poll(10 * 60_000L) {
            c.db.models().get(entity.id)?.takeIf { it.status == ModelStatus.READY.name || it.status == ModelStatus.FAILED.name }
        }
        assertEquals("Download did not finish: ${done?.error}", ModelStatus.READY.name, done?.status)
        val loaded = runBlocking { c.localHost.unload(); c.localHost.load(done!!, 2048, 2) }
        val start = System.currentTimeMillis()
        val r = runBlocking {
            c.providers.local.complete(
                LlmRequest(
                    loaded.name,
                    listOf(LlmMessage(Role.SYSTEM, "You are a helpful assistant. Answer briefly."), LlmMessage(Role.USER, "What is the capital of France? Answer with one word.")),
                    temperature = 0.2, maxOutputTokens = 24,
                )
            )
        }
        Ci.log("local-qwen", "Answer in ${System.currentTimeMillis() - start} ms (${r.outputTokens} tokens, finish=${r.finishReason}): ${r.text.take(200)}")
        assertTrue("The model's answer is wrong or garbled: ${r.text.take(200)}", r.text.contains("Paris", ignoreCase = true))
        Ci.log("local-qwen", "Load mode on this device: ${c.localHost.loadMode(done!!.id)}")

        // A second turn reuses the kept context (only the new message is read) and still answers right.
        run {
            val bridge = com.gravity.ide.ai.local.LlamaBridge
            val handle = bridge.load(done.filePath, 1024, 2, 0, true)
            fun ask(roles: Array<String>, contents: Array<String>): Pair<String, Long> {
                val out = StringBuilder()
                val utf8 = com.gravity.ide.core.Utf8Accumulator()
                val t0 = System.currentTimeMillis()
                bridge.generate(handle, roles, contents, 16, 0.1f, 0.95f, 0, "") { bytes -> out.append(utf8.append(bytes)); true }
                out.append(utf8.flush())
                return out.toString() to (System.currentTimeMillis() - t0)
            }
            val sys = "You are a helpful assistant. Answer briefly."
            val (first, ms1) = ask(arrayOf("system", "user"), arrayOf(sys, "What is the capital of France? Answer with one word."))
            val (second, ms2) = ask(
                arrayOf("system", "user", "assistant", "user"),
                arrayOf(sys, "What is the capital of France? Answer with one word.", first, "And of Germany? Answer with one word."),
            )
            val reused = bridge.modelInfo(handle).getOrElse(5) { 0 }
            bridge.unload(handle)
            Ci.log("local-qwen", "Two turns: \"$first\" ($ms1 ms), then \"$second\" ($ms2 ms), reusing $reused tokens")
            assertTrue("The second turn did not reuse the kept context", reused > 0)
            assertTrue("The second turn's answer is wrong: $second", second.contains("Berlin", ignoreCase = true))
        }

        // Every engine this device can run, file mapped and read into memory, must answer correctly.
        runBlocking { c.localHost.unload() }
        val bridge = com.gravity.ide.ai.local.LlamaBridge
        Ci.log("local-qwen", "Engines here: ${bridge.engines.joinToString { it.label }}; fast engine: ${bridge.fastUnavailableReason ?: "usable"}; HWCAP 0x${java.lang.Long.toHexString(bridge.cpuHwcap())}")
        for (engine in bridge.engines) {
            for (mmap in listOf(true, false)) {
                val handle = bridge.load(done.filePath, 1024, 2, 0, mmap, engine)
                assertTrue("Loading (${engine.label}, mmap=$mmap) failed: ${bridge.lastError()}", handle != 0L)
                val out = StringBuilder()
                val utf8 = com.gravity.ide.core.Utf8Accumulator()
                val t0 = System.currentTimeMillis()
                bridge.generate(
                    handle, arrayOf("system", "user"), arrayOf("You are a helpful assistant. Answer briefly.", "What is the capital of France? Answer with one word."),
                    16, 0.1f, 0.95f, 0, "",
                ) { bytes -> out.append(utf8.append(bytes)); true }
                out.append(utf8.flush())
                bridge.unload(handle)
                Ci.log("local-qwen", "${engine.label}, ${if (mmap) "file mapped" else "without mmap"}: $out (${System.currentTimeMillis() - t0} ms)")
                assertTrue("${engine.label}, mmap=$mmap: the answer is wrong: $out", out.contains("Paris", ignoreCase = true))
            }
        }
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
            // Gravity loads a downloaded phone model by itself when the AI is needed, also offline.
            val canUsePhone = c.localHost.loadedOrNull() != null ||
                (c.localHost.runtimeAvailable && runBlocking { c.db.models().all() }.any { it.status == ModelStatus.READY.name })
            val chat = runCatching { runBlocking { c.providers.resolve(null, null) } }
            val local = c.localHost.loadedOrNull()
            Ci.log("offline", "AI request while offline (phone model usable: $canUsePhone, loaded: ${local?.name}) → ${chat.exceptionOrNull()?.message ?: "routed to ${chat.getOrNull()?.model} (local=${chat.getOrNull()?.provider?.isLocal})"}")
            if (!canUsePhone) {
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

    /**
     * Regression for a crash reported from a phone: an agent task that ends immediately made
     * Gravity stop its foreground service before Android delivered the start, which Android 12+
     * punishes with ForegroundServiceDidNotStartInTimeException. Run with Gravity in the
     * foreground (so the service is actually allowed to start), as on the phone.
     */
    @Test
    fun t14_taskServiceSurvivesInstantStartStop() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            fun serviceRunning() = am.getRunningServices(50).any { it.service.className == com.gravity.ide.agent.TaskForegroundService::class.java.name }

            repeat(25) {
                com.gravity.ide.agent.TaskForegroundService.update(ctx, 1)
                com.gravity.ide.agent.TaskForegroundService.update(ctx, 0)
            }
            // Agent tasks that fail at once, several in a row, as the phone did. (A missing project makes
            // them fail instantly even though a phone model is downloaded by now and would otherwise run them.)
            repeat(4) {
                val id = runBlocking { c.agent.create("ci-missing-project", "Instant failure $it", null, null, ReasoningMode.FAST, null) }
                c.agent.start(id)
            }
            Thread.sleep(15_000) // longer than Android's startForeground deadline in this window
            val idle = poll(10_000) { if (!serviceRunning()) true else null }
            Ci.log("task-service", "25 instant start/stop cycles + 4 instantly failing tasks: no crash; service stopped afterwards: ${idle == true}")
            assertNotNull("Task service still running with no tasks", idle)

            com.gravity.ide.agent.TaskForegroundService.update(ctx, 2)
            val up = poll(10_000) { if (serviceRunning()) true else null }
            com.gravity.ide.agent.TaskForegroundService.update(ctx, 0)
            val down = poll(10_000) { if (!serviceRunning()) true else null }
            Ci.log("task-service", "Normal start → running=${up == true}; stop → stopped=${down == true}")
            assertNotNull("Task service did not start", up)
            assertNotNull("Task service did not stop", down)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun t15_pdfIsReadFromItsTextLayerAndWithOcr() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        fun makePdf(file: java.io.File, asPicture: Boolean) {
            val doc = android.graphics.pdf.PdfDocument()
            val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val paint = android.graphics.Paint().apply { textSize = 22f; color = android.graphics.Color.BLACK; isAntiAlias = true }
            val lines = listOf("School Rules", "Students must arrive by 8 AM.", "विद्यालय के नियम", "सभी छात्र समय पर आएँ।")
            if (asPicture) {
                // Text drawn into a picture: the PDF has no text layer, like a scanned page.
                val bmp = android.graphics.Bitmap.createBitmap(1190, 1684, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                c.drawColor(android.graphics.Color.WHITE)
                val big = android.graphics.Paint(paint).apply { textSize = 56f }
                lines.forEachIndexed { i, l -> c.drawText(l, 80f, 160f + i * 110f, big) }
                page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, 595, 842), null)
            } else {
                lines.forEachIndexed { i, l -> page.canvas.drawText(l, 40f, 80f + i * 40f, paint) }
            }
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
            doc.close()
        }
        val textPdf = java.io.File(ctx.cacheDir, "rules-text.pdf").also { makePdf(it, asPicture = false) }
        val scanPdf = java.io.File(ctx.cacheDir, "rules-scan.pdf").also { makePdf(it, asPicture = true) }

        val a = com.gravity.ide.files.PdfReader.read(ctx, Uri.fromFile(textPdf))
        Ci.log("pdf", "Text PDF via ${a.method}: ${a.text.replace('\n', ' ').take(300)} [${a.layerInfo}]")
        val b = com.gravity.ide.files.PdfReader.read(ctx, Uri.fromFile(scanPdf))
        Ci.log("pdf", "Scanned PDF via ${b.method}: ${b.text.replace('\n', ' ').take(300)}")
        textPdf.delete(); scanPdf.delete()
        // Android's own PDF writer may not leave a text layer PDFBox can use; then OCR reads the page
        // (OCR can miss a letter, so only whole, easy words are checked).
        assertTrue("A short text page is read with OCR or from its text: ${a.text}",
            a.text.contains("School Rules", ignoreCase = true) && a.text.contains("Students must", ignoreCase = true) && a.text.contains("विद्यालय"))
        assertEquals("on-device OCR", b.method)
        assertTrue("OCR did not read the English line: ${b.text}", b.text.contains("arrive", ignoreCase = true))
        assertTrue("OCR did not read the Hindi line: ${b.text}", b.text.contains("नियम") || b.text.contains("छात्र"))
    }

    @Test
    fun t16_filesAreMadeOnThePhone() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val reply = "# School Rules\nविद्यालय के नियम: सभी छात्र समय पर आएँ।\n\n## Timing\n- Arrive by 8 AM\n- Leave at 2 PM\n\n" +
            "File: index.html\n```html\n<html><body>Hi</body></html>\n```\n```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\">" +
            "<rect width=\"100\" height=\"50\" fill=\"#00e5ff\"/><circle cx=\"25\" cy=\"25\" r=\"20\" fill=\"#ff4d6d\"/></svg>\n```"
        val pdf = com.gravity.ide.files.FileMaker.pdf(ctx, "rules", reply)
        val back = com.gravity.ide.files.PdfReader.read(ctx, Uri.fromFile(pdf), previewPages = 0)
        Ci.log("make", "PDF ${pdf.length()} bytes, ${back.pages} page(s), read back via ${back.method}: ${back.text.replace('\n', ' ').take(200)} [${back.layerInfo}]")
        // Read back from its text layer or (if Android's PDF writer left none PDFBox can use) with OCR,
        // which can misread a digit: check whole words.
        assertTrue("The PDF does not contain the text: ${back.text}", back.text.contains("School Rules") && back.text.contains("Arrive by 8 AM") && back.text.contains("विद्यालय"))

        val zip = com.gravity.ide.files.FileMaker.zip(ctx, "site", com.gravity.ide.core.MakeFiles.codeFiles(reply).map { it.first to it.second.toByteArray() })
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        Ci.log("make", "ZIP ${zip.length()} bytes: $names")
        assertEquals(listOf("index.html", "image.svg"), names)

        val png = com.gravity.ide.files.FileMaker.png(ctx, "picture", com.gravity.ide.core.MakeFiles.svg(reply)!!)
        val bmp = android.graphics.BitmapFactory.decodeFile(png.absolutePath)
        val pixel = bmp.getPixel(bmp.width / 4, bmp.height / 2)
        Ci.log("make", "PNG ${bmp.width}x${bmp.height}, pixel in the circle = #${Integer.toHexString(pixel)}")
        assertEquals(1024, bmp.width)
        assertEquals(512, bmp.height)
        assertTrue("The circle was not drawn (pixel #${Integer.toHexString(pixel)})", android.graphics.Color.red(pixel) > 200 && android.graphics.Color.green(pixel) < 120)

        // Audio needs a text-to-speech engine; emulators may have none, which is reported, not faked.
        val wav = runCatching { runBlocking { com.gravity.ide.files.FileMaker.audio(ctx, "speech", "Hello from Gravity. School starts at eight.", java.util.Locale.forLanguageTag("en-IN")) } }
        wav.onSuccess { f ->
            val head = f.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
            Ci.log("make", "WAV ${f.length()} bytes ($head)")
            assertEquals("RIFF", head)
            assertTrue("The audio is empty", f.length() > 1000)
        }.onFailure { Ci.log("make", "Audio not made on this device: ${it.message}") }
        listOf(pdf, zip, png).forEach { it.delete() }
        wav.getOrNull()?.delete()
    }
}
