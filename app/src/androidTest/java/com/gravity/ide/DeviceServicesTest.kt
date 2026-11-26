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
     * Chat warm-up: reading the system prompt and the chat so far before the user sends (as the
     * chat screen does when it opens) means the reply only reads the new message.
     */
    @Test
    fun t12df_warmUpMeansTheReplyReadsOnlyTheNewMessage() {
        Assume.assumeTrue("llama.cpp runtime is not bundled in this build", c.localHost.runtimeAvailable)
        val model = runBlocking { c.db.models().all() }.firstOrNull { it.status == ModelStatus.READY.name && it.filePath.contains("0.5b-instruct", ignoreCase = true) }
        Assume.assumeTrue("Needs the instruct model downloaded by t12d", model != null)
        val loaded = runBlocking { c.localHost.load(model!!, 2048, 2) }
        val earlier = listOf(LlmMessage(Role.USER, "Hi, my name is Asha."), LlmMessage(Role.ASSISTANT, "Hello Asha! How can I help you today?"))
        // Cold first word (nothing read yet) with the long cloud prompt and with the short phone one.
        fun coldFirstWord(prompt: String): Long {
            val t = System.currentTimeMillis()
            var first = -1L
            runBlocking {
                c.providers.local.stream(LlmRequest(loaded.name, listOf(LlmMessage(Role.SYSTEM, prompt)) + earlier + LlmMessage(Role.USER, "What is my name?"), maxOutputTokens = 2, temperature = 0.1)) { ev ->
                    if (ev is com.gravity.ide.ai.LlmEvent.TextDelta && first < 0) first = System.currentTimeMillis() - t
                }
            }
            return first
        }
        val coldLong = coldFirstWord(com.gravity.ide.core.ChatPrompt.system())
        val coldShort = coldFirstWord(com.gravity.ide.core.ChatPrompt.phone())
        val longChars = com.gravity.ide.core.ChatPrompt.system().length
        val shortChars = com.gravity.ide.core.ChatPrompt.phone().length
        Ci.log("local-prompt", "Cold first word: long prompt ($longChars chars) $coldLong ms, phone prompt ($shortChars chars) $coldShort ms")
        // Phone models get the short prompt (as the chat screen sends it).
        val system = LlmMessage(Role.SYSTEM, com.gravity.ide.core.ChatPrompt.system(compact = true))
        // Something else first, so the warm-up below really reads the prompt again.
        runBlocking { c.providers.local.stream(LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Count to three.")), maxOutputTokens = 1)) { } }
        val t0 = System.currentTimeMillis()
        runBlocking { c.providers.local.stream(LlmRequest(loaded.name, listOf(system) + earlier, maxOutputTokens = 1)) { } }
        val warmMs = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        var firstMs = -1L
        val reply = StringBuilder()
        runBlocking {
            c.providers.local.stream(LlmRequest(loaded.name, listOf(system) + earlier + LlmMessage(Role.USER, "What is my name?"), maxOutputTokens = 16, temperature = 0.1)) { ev ->
                if (ev is com.gravity.ide.ai.LlmEvent.TextDelta) { if (firstMs < 0) firstMs = System.currentTimeMillis() - t1; reply.append(ev.text) }
            }
        }
        val reused = com.gravity.ide.ai.local.LlamaBridge.modelInfo(loaded.handle).getOrElse(5) { 0 }
        Ci.log("local-warm", "Warm-up read the prompt in $warmMs ms; the reply then reused $reused tokens, first word after $firstMs ms: \"${reply.toString().trim()}\"")
        assertTrue("The reply did not reuse the warmed-up prompt (reused $reused tokens)", reused > 150)
        assertTrue("The model forgot the chat: ${reply}", reply.contains("Asha", ignoreCase = true))
    }

    /**
     * What a warm-up read is saved, so the next start of the model does not read the long system
     * prompt again (a 7B model reads it for 40 s or more): after unloading and loading the model,
     * the first question reuses the saved prompt, answers right, and starts much sooner.
     */
    @Test
    fun t12dg_savedPromptMakesTheFirstReplyFastAfterARestart() {
        Assume.assumeTrue("llama.cpp runtime is not bundled in this build", c.localHost.runtimeAvailable)
        val model = runBlocking { c.db.models().all() }.firstOrNull { it.status == ModelStatus.READY.name && it.filePath.contains("0.5b-instruct", ignoreCase = true) }
        Assume.assumeTrue("Needs the instruct model downloaded by t12d", model != null)
        java.io.File(ctx.cacheDir, "kv").deleteRecursively()
        val system = LlmMessage(Role.SYSTEM, com.gravity.ide.core.ChatPrompt.system(compact = true))
        val earlier = listOf(LlmMessage(Role.USER, "Hi, my name is Ravi."), LlmMessage(Role.ASSISTANT, "Hello Ravi! How can I help you today?"))
        val question = listOf(system) + earlier + LlmMessage(Role.USER, "What is my name?")
        fun firstWord(): Pair<Long, String> {
            val t = System.currentTimeMillis()
            var first = -1L
            val reply = StringBuilder()
            runBlocking {
                c.providers.local.stream(LlmRequest(model!!.name, question, maxOutputTokens = 12, temperature = 0.1)) { ev ->
                    if (ev is com.gravity.ide.ai.LlmEvent.TextDelta) { if (first < 0) first = System.currentTimeMillis() - t; reply.append(ev.text) }
                }
            }
            return first to reply.toString().trim()
        }
        runBlocking { c.localHost.unload() }
        var loaded = runBlocking { c.localHost.load(model!!, 2048, 2) }
        val (cold, coldReply) = firstWord()
        // The chat screen's warm-up when a chat opens: it reads the prompt and saves what it read.
        runBlocking { c.localHost.unload() }
        loaded = runBlocking { c.localHost.load(model!!, 2048, 2) }
        runBlocking { c.providers.local.stream(LlmRequest(loaded.name, listOf(system) + earlier + LlmMessage(Role.USER, ""), maxOutputTokens = 1, warmUp = true)) { } }
        val saved = java.io.File(ctx.cacheDir, "kv").listFiles().orEmpty().filter { it.name.endsWith(".bin") }
        Ci.log("local-state", "Saved after the warm-up: ${saved.map { "${it.name} (${it.length() / 1024} KB)" }}")
        assertTrue("Nothing was saved after the warm-up", saved.isNotEmpty())
        // A restart of the model (as after a phone picture or a new app start).
        runBlocking { c.localHost.unload() }
        loaded = runBlocking { c.localHost.load(model!!, 2048, 2) }
        val (warm, warmReply) = firstWord()
        val reused = com.gravity.ide.ai.local.LlamaBridge.modelInfo(loaded.handle).getOrElse(5) { 0 }
        Ci.log("local-state", "First word after loading: $cold ms reading everything (\"$coldReply\"), $warm ms with the saved prompt (\"$warmReply\"), reused $reused tokens")
        assertTrue("The saved prompt was not used (reused $reused tokens)", reused > 150)
        assertTrue("The answer is wrong with the saved prompt: $warmReply", warmReply.contains("Ravi", ignoreCase = true))
        assertTrue("The first word did not come sooner ($warm ms vs $cold ms)", warm < cold)
    }

    /**
     * Two chats, two phone models (the user's case: Gemma in one chat, Qwen in another), both
     * asking at the same time: both must answer, each with its own model. The same file is
     * registered twice, so Gravity really unloads one model and loads the other between them.
     */
    @Test
    fun t12e_twoChatsWithTwoPhoneModelsBothAnswer() {
        Assume.assumeTrue("llama.cpp runtime is not bundled in this build", c.localHost.runtimeAvailable)
        val a = runBlocking { c.db.models().all() }.firstOrNull { it.status == ModelStatus.READY.name && it.filePath.contains("0.5b-instruct", ignoreCase = true) }
        Assume.assumeTrue("Needs the instruct model downloaded by t12d", a != null)
        val b = a!!.copy(id = "ci-second-model", name = "Second copy of ${a.name}", isDefault = false)
        runBlocking { c.db.models().upsert(b); c.localHost.unload() }
        val answers = java.util.concurrent.ConcurrentHashMap<String, String>()
        fun ask(m: com.gravity.ide.data.db.LocalModelEntity) = Thread {
            val r = runCatching {
                runBlocking {
                    val resolved = c.providers.resolve(com.gravity.ide.ai.local.LocalLlamaProvider.ID, m.id)
                    val res = resolved.provider.complete(
                        LlmRequest(resolved.model, listOf(LlmMessage(Role.USER, "What is the capital of France? Answer with one word.")), maxOutputTokens = 12, temperature = 0.1),
                    )
                    "${resolved.model} → ${res.text.trim()}"
                }
            }
            answers[m.id] = r.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }
        }
        val started = System.currentTimeMillis()
        val ta = ask(a).apply { start() }
        Thread.sleep(300)
        val tb = ask(b).apply { start() }
        ta.join(300_000); tb.join(300_000)
        Ci.log("local-switch", "Two chats, two models, ${System.currentTimeMillis() - started} ms: A=${answers[a.id]} | B=${answers[b.id]}; loaded now ${c.localHost.loadedOrNull()?.name}")
        runBlocking { c.localHost.unload(); c.db.models().delete(b.id) }
        assertTrue("Chat A failed: ${answers[a.id]}", answers[a.id]?.startsWith(a.name) == true && answers[a.id]!!.contains("Paris", ignoreCase = true))
        assertTrue("Chat B failed: ${answers[b.id]}", answers[b.id]?.startsWith(b.name) == true && answers[b.id]!!.contains("Paris", ignoreCase = true))
    }

    /**
     * "ek bandar ki image banao" in a chat with a phone model, drawn on the phone, as a user does it:
     * the reply must finish and end in a picture or a clear message, the model must draw in the
     * context it already has (a bigger one cost memory and phones closed Gravity for it), and the
     * work is noted for the crash report while it runs and cleared after.
     */
    @Test
    fun t12f_pictureRequestOnPhoneModelFinishes() {
        Assume.assumeTrue("llama.cpp runtime is not bundled in this build", c.localHost.runtimeAvailable)
        val model = runBlocking { c.db.models().all() }.firstOrNull { it.status == ModelStatus.READY.name && it.filePath.contains("0.5b-instruct", ignoreCase = true) }
        Assume.assumeTrue("Needs the instruct model downloaded by t12d", model != null)
        val inst = InstrumentationRegistry.getInstrumentation()
        val work = inst.targetContext.getSharedPreferences("crash_reports", android.content.Context.MODE_PRIVATE)
        val loaded = runBlocking { c.localHost.unload(); c.localHost.load(model!!, 4096, 2) }
        runBlocking { c.settings.update { it.copy(pictureMaker = "draw") } }
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val chatId = runBlocking {
                val id = com.gravity.ide.ui.screens.freshChatId(c)
                c.db.chats().upsert(c.db.chats().get(id)!!.copy(providerId = com.gravity.ide.ai.local.LocalLlamaProvider.ID, model = model!!.id))
                id
            }
            val vm = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = com.gravity.ide.ui.screens.ChatViewModel(c, chatId) as T
            })[com.gravity.ide.ui.screens.ChatViewModel::class.java]
            val started = System.currentTimeMillis()
            inst.runOnMainSync { vm.send("ek bandar ki image banao") }
            // While it draws, the work is noted for the crash report.
            var noted: String? = null
            var reply: com.gravity.ide.data.db.MessageEntity? = null
            val until = started + 10 * 60_000L
            while (reply == null && System.currentTimeMillis() < until) {
                if (noted == null) noted = work.getStringSet("work", emptySet()).orEmpty().firstOrNull()?.substringAfter('|')
                reply = runBlocking { c.db.chats().messages(chatId) }.firstOrNull { it.role == "assistant" && (it.content.isNotBlank() || it.error != null) }
                if (reply == null) Thread.sleep(500)
            }
            assertNotNull("The picture reply did not finish in 10 minutes", reply)
            val replyMs = System.currentTimeMillis() - started
            // Then the picture is made from the drawing (or a clear message says why not).
            var made: String? = null
            val makeUntil = System.currentTimeMillis() + 60_000
            while (made == null && System.currentTimeMillis() < makeUntil) {
                val m = runBlocking { c.db.chats().messages(chatId) }.first { it.id == reply!!.id }
                made = when {
                    m.attachments.contains("image/png") -> "PNG " + org.json.JSONArray(m.attachments).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.optString("mime") == "image/png" }.optLong("size") } + " bytes"
                    vm.error.value?.startsWith("Could not make the image") == true -> "message: ${vm.error.value}"
                    else -> null
                }
                if (made == null) Thread.sleep(300)
            }
            val kept = com.gravity.ide.ai.local.LlamaBridge.modelInfo(loaded.handle).getOrNull(6)
            // The reply's speed line, as shown under it in the chat.
            val speedLine = c.replySpeeds.get(reply!!.id)
            Ci.log("picture-speed", "Speed line under the reply: $speedLine")
            assertTrue("No speed line for the phone model's reply", speedLine?.startsWith("Read ") == true && speedLine.contains("threads"))
            val left = work.getStringSet("work", emptySet()).orEmpty()
            Ci.log(
                "picture-local",
                "Reply in $replyMs ms, ${reply!!.content.length} chars, svg=${com.gravity.ide.core.MakeFiles.svg(reply.content) != null}, error=${reply.error}; " +
                    "result: $made; context kept ${kept} (loaded ${loaded.contextSize}); noted while working: \"$noted\"; left noted after: ${left.size}",
            )
            assertTrue("Neither a picture nor a message after the reply", made != null || reply.error != null)
            assertEquals("The drawing made the model's context bigger", loaded.contextSize, kept)
            assertTrue("The drawing was not noted for the crash report: $noted", noted?.contains("drawing the image") == true)
            val clearBy = System.currentTimeMillis() + 5_000
            while (work.getStringSet("work", emptySet()).orEmpty().isNotEmpty() && System.currentTimeMillis() < clearBy) Thread.sleep(100)
            assertTrue("Work still noted after it finished", work.getStringSet("work", emptySet()).orEmpty().isEmpty())
        } finally {
            inst.runOnMainSync { store.clear() }
            runBlocking { c.settings.update { it.copy(pictureMaker = "ask") } }
        }
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

    /** A real MP4 is made on the phone from scenes (a picture + narration each), and Android can read it back. */
    @Test
    fun t16b_videoIsMadeOnThePhone() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val reply = "Scene 1: Diwali ki raat hai, har taraf roshni hai.\n```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 720 1280\">" +
            "<rect width=\"720\" height=\"1280\" fill=\"#1a1040\"/><circle cx=\"360\" cy=\"500\" r=\"120\" fill=\"#ffb703\"/></svg>\n```\n" +
            "Scene 2: Diye jal rahe hain.\n```svg\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 720 1280\">" +
            "<rect width=\"720\" height=\"1280\" fill=\"#0b3d2e\"/><rect x=\"260\" y=\"700\" width=\"200\" height=\"80\" fill=\"#ff4d6d\"/></svg>\n```"
        val scenes = com.gravity.ide.core.MakeFiles.scenes(reply)
        assertEquals(2, scenes.size)
        val started = System.currentTimeMillis()
        val made = runBlocking { com.gravity.ide.files.VideoMaker.make(ctx, "diwali", scenes, java.util.Locale.forLanguageTag("en-IN")) }
        val ex = android.media.MediaExtractor()
        ex.setDataSource(made.file.absolutePath)
        val tracks = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
        val videoFormat = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.first { it.getString(android.media.MediaFormat.KEY_MIME) == "video/avc" }
        val durationUs = videoFormat.getLong(android.media.MediaFormat.KEY_DURATION)
        ex.release()
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(made.file.absolutePath)
        val frame = retriever.getFrameAtTime(1_000_000)
        val pixel = frame?.getPixel(frame.width / 2, frame.height / 3) ?: 0
        retriever.release()
        Ci.log("make", "MP4 ${made.file.length()} bytes in ${System.currentTimeMillis() - started} ms: tracks $tracks, ${durationUs / 1000} ms, voice=${made.withVoice}, " +
            "frame ${frame?.width}x${frame?.height}, pixel #${Integer.toHexString(pixel)}")
        assertTrue("No H.264 video track: $tracks", "video/avc" in tracks)
        if (made.withVoice) assertTrue("No AAC audio track: $tracks", "audio/mp4a-latm" in tracks)
        assertTrue("Video too short: ${durationUs / 1000} ms", durationUs >= 4_500_000)
        assertNotNull("Android could not read a frame of the video", frame)
        assertEquals(720, frame!!.width)
    }

    /** The free online image AI (Pollinations) really returns a picture. An outside service: if it is down, the test is skipped, not passed. */
    @Test
    fun t16c_onlineImageAiMakesARealPicture() {
        val started = System.currentTimeMillis()
        val result = runCatching { runBlocking { com.gravity.ide.files.OnlineImages.bitmap("a cute cartoon monkey eating a banana on a tree, bright colours", 512, 512) } }
        val bmp = result.getOrNull()
        Ci.log("make", "Online image AI: ${if (bmp != null) "${bmp.width}x${bmp.height} picture" else "failed: ${result.exceptionOrNull()?.message}"} in ${System.currentTimeMillis() - started} ms")
        org.junit.Assume.assumeTrue("The online image service could not be reached from the emulator: ${result.exceptionOrNull()?.message}", bmp != null)
        assertEquals(512, bmp!!.width)
        // A real picture has many colours (not one flat colour).
        val colours = (0 until 20).map { i -> bmp.getPixel(25 * i + 10, 25 * i + 10) }.toSet()
        assertTrue("The picture is one flat colour", colours.size > 3)
    }

    /** After a crash, the next start says what happened, with the saved stack trace to share. */
    @Test
    fun t17_crashReportIsShownAfterACrash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(ctx.filesDir, "crash/last.txt").apply { parentFile?.mkdirs() }
        file.writeText("Time: test\nThread: main\njava.lang.IllegalStateException: test crash\n\tat com.gravity.ide.Example.run(Example.kt:1)")
        val reports = com.gravity.ide.CrashReports(ctx)
        reports.check()
        val r = reports.pending.value
        Ci.log("crash-report", "Report after a saved crash: ${r?.title} | ${r?.details?.take(120)}")
        assertNotNull("No report after a crash", r)
        assertEquals("Yamini crashed last time", r!!.title)
        assertTrue(r.details.contains("IllegalStateException: test crash"))
        assertFalse("The saved crash was not cleared", file.exists())
        // The report stays until it is dismissed, also after a restart.
        val again = com.gravity.ide.CrashReports(ctx).also { it.check() }
        val start = System.currentTimeMillis()
        while (again.pending.value == null && System.currentTimeMillis() - start < 8_000) Thread.sleep(100)
        assertEquals("The report did not stay until dismissed", "Yamini crashed last time", again.pending.value?.title)
        again.dismiss()
        val third = com.gravity.ide.CrashReports(ctx).also { it.check() }
        Thread.sleep(1500)
        assertEquals("A dismissed report came back", null, third.pending.value)
        val onScreen = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_SIGNALED, null, 9, emptyList(), "details", onScreen = true)
        val swipedOnScreen = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_OTHER, "remove task", 0, emptyList(), "details", onScreen = true)
        Ci.log("crash-report", "Closed on screen: ${onScreen?.title} | ${onScreen?.explanation}")
        assertEquals("Yamini closed unexpectedly", onScreen?.title)
        assertEquals("Swiping away is not reported", null, swipedOnScreen)
    }

    /**
     * The on-phone picture engine without its 2.2 GB model (CI has none; the arm64-image-check job runs
     * the same engine code with the real model): the library loads where it is bundled, the model's
     * files never show up as chat models, a missing model gives a clear message, and the JNI calls
     * fail cleanly instead of crashing.
     */
    @Test
    fun t18_phonePictureEngineWithoutItsModel() {
        val pictures = c.phonePictures
        val state = runBlocking { pictures.state() }
        Ci.log("phone-picture", "Bundled: ${BuildConfig.IMAGE_BUNDLED}; state: $state; library: ${com.gravity.ide.ai.image.NativeImage.loadError ?: "loaded"}")
        if (!BuildConfig.IMAGE_BUNDLED) {
            assertTrue("Without the engine it must say so: $state", state is com.gravity.ide.ai.image.PhonePictures.State.Unavailable)
            return
        }
        assertEquals("The picture engine library did not load", null, com.gravity.ide.ai.image.NativeImage.loadError)
        assertEquals(com.gravity.ide.ai.image.PhonePictures.State.NotDownloaded, state)
        // A picture-model file row is not a chat model.
        val row = com.gravity.ide.data.db.LocalModelEntity(
            id = "test-picture-row", name = "diffusion_pytorch_model.safetensors", repoId = "madebyollin/taesd", filePath = "/nonexistent",
            status = com.gravity.ide.models.ModelStatus.QUEUED.name, role = "picture", createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        runBlocking { c.db.models().upsert(row) }
        try {
            val chatModels = runBlocking { c.db.models().all() }
            val pictureFiles = runBlocking { c.db.models().pictureFiles() }
            assertTrue("A picture file was listed as a chat model", chatModels.none { it.id == row.id })
            assertTrue("The picture file was not listed as one", pictureFiles.any { it.id == row.id })
            assertTrue("A half-downloaded model must show as downloading", runBlocking { pictures.state() } is com.gravity.ide.ai.image.PhonePictures.State.Downloading)
        } finally {
            runBlocking { c.db.models().delete(row.id) }
        }
        // Making a picture without the model: a plain message, no crash.
        val missing = runCatching { runBlocking { pictures.make("a red apple", 384, 384) { _, _ -> } } }.exceptionOrNull()
        assertTrue("Wrong message: ${missing?.message}", missing?.message?.contains("not downloaded") == true)
        // The JNI calls with files that do not exist: they fail cleanly with an error message.
        val n = com.gravity.ide.ai.image.NativeImage
        val handle = n.load("/nonexistent/model.safetensors", "", "", 2, "q8_0")
        val out = if (handle != 0L) n.generate(handle, "a red apple", "", 64, 64, 1, 1.0f, 1, "lcm", 1.0f) { _, _ -> } else null
        val err = n.lastError()
        if (handle != 0L) n.unload(handle)
        Ci.log("phone-picture", "Missing model: handle=${handle != 0L}, picture=${out != null}, error=\"$err\"")
        assertEquals("A picture came out of a missing model", null, out)
        assertTrue("No error message for a missing model", err.isNotBlank())
    }

    /** Gravity closed in the middle of work with no crash (a phone killing it for memory): the next start says so. */
    @Test
    fun t17b_closingDuringWorkIsReported() {
        val doing = listOf("writing a reply with Gemma 3 4B on the phone, context 4096, drawing the image (up to 2500 tokens)")
        val killed = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_SIGNALED, null, 9, doing, "details")
        val lowMem = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, null, 0, doing, "details")
        val noRecord = com.gravity.ide.CrashReports.reportFor(-1, null, 0, doing, "details")
        val idleKill = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_SIGNALED, null, 9, emptyList(), "details")
        val swiped = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, null, 0, doing, "details")
        val cleaner = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, "stop by powerkeeper", 0, doing, "details")
        Ci.log("crash-report", "Killed while drawing: ${killed?.title} | ${killed?.explanation}")
        assertEquals("The phone closed Yamini while it was working", killed?.title)
        assertTrue(killed!!.explanation.contains("kill signal") && killed.explanation.contains("It was writing a reply with Gemma 3 4B"))
        assertTrue(lowMem!!.title.contains("memory") && lowMem.explanation.contains("It was writing"))
        assertNotNull("No report when Android kept no exit record", noRecord)
        assertEquals("An idle kill (e.g. by the system) is not reported", null, idleKill)
        assertEquals("Swiping Yamini away is not reported", null, swiped)
        assertNotNull("The phone's cleaner during work is reported", cleaner)
        val dependency = com.gravity.ide.CrashReports.reportFor(android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED, "depends on provider", 0, doing, "details")
        assertEquals("Android closed Yamini when a system app it used restarted", dependency?.title)
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
        val wav = runCatching { runBlocking { com.gravity.ide.files.FileMaker.audio(ctx, "speech", "Hello from Yamini. School starts at eight.", java.util.Locale.forLanguageTag("en-IN")) } }
        wav.onSuccess { f ->
            val head = f.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
            Ci.log("make", "WAV ${f.length()} bytes ($head)")
            assertEquals("RIFF", head)
            assertTrue("The audio is empty", f.length() > 1000)
        }.onFailure { Ci.log("make", "Audio not made on this device: ${it.message}") }
        listOf(pdf, zip, png).forEach { it.delete() }
        wav.getOrNull()?.delete()
    }

    /**
     * Speaking like a person: Android must honour a speed set between phrases (that is how one
     * word is drawn out and the next said quickly), and an audio file made that way has real
     * pauses between its phrases. Needs a text-to-speech engine; none is reported, not faked.
     */
    @Test
    fun t19_voiceSpeaksLikeAPerson() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = kotlinx.coroutines.CompletableDeferred<Int>()
        val tts = runBlocking(kotlinx.coroutines.Dispatchers.Main) { android.speech.tts.TextToSpeech(ctx.applicationContext) { ready.complete(it) } }
        try {
            val status = runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000) { ready.await() } }
            if (status != android.speech.tts.TextToSpeech.SUCCESS) { Ci.log("voice", "No working text-to-speech engine on this device (status $status)"); return }
            tts.setLanguage(java.util.Locale.US)
            fun seconds(rate: Float): Double {
                val f = java.io.File.createTempFile("rate-", ".wav", ctx.cacheDir)
                val done = java.util.concurrent.CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { done.countDown() }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.countDown() }
                })
                tts.setSpeechRate(rate)
                assertEquals(android.speech.tts.TextToSpeech.SUCCESS, tts.synthesizeToFile("one two three four five six seven eight nine ten", android.os.Bundle(), f, "rate-$rate"))
                assertTrue("speech at $rate did not finish", done.await(60, java.util.concurrent.TimeUnit.SECONDS))
                val (samples, sr) = com.gravity.ide.core.WavJoin.pcmMono16(f.readBytes())
                f.delete()
                return samples.size.toDouble() / sr
            }
            val slow = seconds(0.7f)
            val quick = seconds(1.4f)
            Ci.log("voice", "Same words at speed 0.7: %.2f s, at 1.4: %.2f s (engine ${tts.defaultEngine})".format(slow, quick))
            assertTrue("The engine ignores a speed set between phrases (%.2f s vs %.2f s)".format(slow, quick), slow > quick * 1.3)
        } finally {
            runBlocking(kotlinx.coroutines.Dispatchers.Main) { tts.shutdown() }
        }

        val text = "Arre, ye toh bahut achha hai! Main aapke liye ek gaana bana sakti hoon, aur phir hum use sun sakte hain. Kya aap taiyaar hain?"
        val plan = com.gravity.ide.core.HumanSpeech.plan(text)
        Ci.log("voice", "Plan: " + plan.joinToString(" | ") { "%s (speed %.2f, pitch %.2f, loud %.2f, pause %d ms)".format(it.text, it.rate, it.pitch, it.volume, it.pauseAfterMs) })
        val was = com.gravity.ide.files.VoicePrefs.natural(ctx)
        try {
            com.gravity.ide.files.VoicePrefs.setNatural(ctx, false)
            val flat = runBlocking { com.gravity.ide.files.FileMaker.audio(ctx, "flat", text, java.util.Locale.forLanguageTag("en-IN")) }
            com.gravity.ide.files.VoicePrefs.setNatural(ctx, true)
            val human = runBlocking { com.gravity.ide.files.FileMaker.audio(ctx, "human", text, java.util.Locale.forLanguageTag("en-IN")) }
            val (fs, fr) = com.gravity.ide.core.WavJoin.pcmMono16(flat.readBytes())
            val (hs, hr) = com.gravity.ide.core.WavJoin.pcmMono16(human.readBytes())
            // The longest stretch of exact silence: the pauses the plan put between phrases.
            fun longestSilenceMs(x: ShortArray, rate: Int): Int {
                var best = 0; var run = 0
                x.forEach { if (it.toInt() == 0) { run++; if (run > best) best = run } else run = 0 }
                return best * 1000 / rate
            }
            val planned = plan.dropLast(1).maxOf { it.pauseAfterMs }
            Ci.log("voice", "Flat file %.2f s, like a person %.2f s; longest pause %d ms (planned up to %d ms)".format(fs.size.toDouble() / fr, hs.size.toDouble() / hr, longestSilenceMs(hs, hr), planned))
            assertEquals("RIFF", human.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertTrue("The pauses are missing from the file", longestSilenceMs(hs, hr) >= planned - 20)
            flat.delete(); human.delete()
        } finally {
            com.gravity.ide.files.VoicePrefs.setNatural(ctx, was)
        }
    }

    /**
     * When a page's web engine crashes or the phone closes it for memory, Android closes the whole
     * app unless the WebView handles it. The app test (PageChecker, also used by App Builder) must
     * survive both and say what happened; this test would not finish if Yamini closed.
     */
    @Test
    fun t20_webEngineDyingDoesNotCloseYamini() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROCESS) ||
            !androidx.webkit.WebViewCompat.isMultiProcessEnabled()
        ) {
            Ci.log("web-engine", "This device runs web pages inside the app's own process: a crash test cannot run here")
            return
        }
        val checker = com.gravity.ide.build.PageChecker(ctx, ctx.filesDir)
        val root = java.io.File(ctx.filesDir, "web-engine-test").apply { mkdirs() }
        val crashed = runBlocking { checker.check(root, timeoutMs = 20_000, startUrl = "chrome://crash") }
        Ci.log("web-engine", "Page crashed its engine: loaded=${crashed.loaded}, closed=${crashed.engineClosed}, errors=${crashed.errors}")
        assertTrue("The engine crash was not reported: ${crashed.errors}", crashed.errors.any { "crashed the phone's web engine" in it })
        val killed = runBlocking { checker.check(root, timeoutMs = 20_000, startUrl = "chrome://kill") }
        Ci.log("web-engine", "Engine closed by the system: loaded=${killed.loaded}, closed=${killed.engineClosed}, errors=${killed.errors}")
        assertTrue("A closed engine was not noticed", killed.engineClosed || killed.errors.any { "web engine" in it })
        // A normal page still works afterwards.
        java.io.File(root, "index.html").writeText("<!doctype html><button onclick=\"document.title='ok'\">Tap</button>")
        val normal = runBlocking { checker.check(root) }
        Ci.log("web-engine", "Normal page after that: loaded=${normal.loaded}, tapped=${normal.controlsTapped}, errors=${normal.errors}")
        assertTrue("The web engine did not come back", normal.loaded && normal.controlsTapped == 1)
        root.deleteRecursively()
    }

    /**
     * Yamini's natural voice, end to end: the Hindi voice pack downloads (SHA-256 checked) and
     * unpacks, Android binds Yamini's own voice engine, Hinglish is read in Devanagari, the first
     * audio comes quickly, speed changes are honoured, and an audio file is made with it.
     */
    @Test
    fun t21_naturalVoiceSpeaksOffline() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nv = com.gravity.ide.voice.NeuralVoices
        if (!BuildConfig.VOICE_BUNDLED) {
            Ci.log("natural-voice", "Not in this build: ${nv.unavailableReason}")
            return
        }
        assertTrue("The voice engine library did not load", nv.available)
        val pack = nv.pack("hi-female")!!
        val c = (ctx.applicationContext as GravityApp).container
        if (!nv.isReady(ctx, pack)) {
            val t0 = System.currentTimeMillis()
            val row = runBlocking { c.models.enqueueDownload(pack.url, pack.archiveName, pack.sha256, pack.size, "k2-fsa/sherpa-onnx", nv.LICENSE_NOTE, role = "voice") }
            var status = ""
            while (System.currentTimeMillis() - t0 < 8 * 60_000) {
                status = runBlocking { c.db.models().get(row.id) }?.status.orEmpty()
                if (status == com.gravity.ide.models.ModelStatus.READY.name || status == com.gravity.ide.models.ModelStatus.FAILED.name) break
                Thread.sleep(2000)
            }
            val r = runBlocking { c.db.models().get(row.id) }
            Ci.log("natural-voice", "Download: ${r?.status} in ${(System.currentTimeMillis() - t0) / 1000} s, ${r?.error ?: "ok"}")
            assertEquals("The voice pack did not download and unpack: ${r?.error}", com.gravity.ide.models.ModelStatus.READY.name, status)
            assertFalse("The archive was kept after unpacking", java.io.File(r!!.filePath).exists())
        }
        assertTrue(nv.isReady(ctx, pack))
        nv.choose(ctx, pack)
        nv.setEnabled(ctx, true)
        assertEquals("The natural voice is not used for Hindi", ctx.packageName, com.gravity.ide.files.VoicePrefs.engine(ctx, true))
        // Not in the chat model list.
        assertTrue(runBlocking { c.db.models().all() }.none { it.role == "voice" })

        val ready = kotlinx.coroutines.CompletableDeferred<Int>()
        val tts = runBlocking(kotlinx.coroutines.Dispatchers.Main) { com.gravity.ide.files.VoicePrefs.create(ctx, ctx.packageName) { ready.complete(it) } }
        try {
            assertEquals(android.speech.tts.TextToSpeech.SUCCESS, runBlocking { kotlinx.coroutines.withTimeout(20_000) { ready.await() } })
            val lang = tts.setLanguage(java.util.Locale.forLanguageTag("hi-IN"))
            assertTrue("Hindi is not available on the natural voice ($lang)", lang >= android.speech.tts.TextToSpeech.LANG_AVAILABLE)
            fun make(text: String, rate: Float): Triple<Double, Long, Long> {
                val f = java.io.File.createTempFile("nat-", ".wav", ctx.cacheDir)
                val done = java.util.concurrent.CountDownLatch(1)
                var firstAudio = 0L
                val start = System.currentTimeMillis()
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) { if (firstAudio == 0L) firstAudio = System.currentTimeMillis() - start }
                    override fun onDone(utteranceId: String?) { done.countDown() }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { done.countDown() }
                })
                tts.setSpeechRate(rate)
                assertEquals(android.speech.tts.TextToSpeech.SUCCESS, tts.synthesizeToFile(text, android.os.Bundle(), f, "nat-$rate"))
                assertTrue("The natural voice did not finish", done.await(60, java.util.concurrent.TimeUnit.SECONDS))
                val total = System.currentTimeMillis() - start
                val (samples, sr) = com.gravity.ide.core.WavJoin.pcmMono16(f.readBytes())
                f.delete()
                return Triple(samples.size.toDouble() / sr, firstAudio, total)
            }
            val sentence = com.gravity.ide.core.HinglishReader.forVoice(ctx, "Arre, ye toh bahut achha hai! Main aapke liye ek gaana bana sakti hoon.").text
            Ci.log("natural-voice", "Read as: $sentence")
            assertTrue("Hinglish was not written in Devanagari for the voice", sentence.startsWith("अरे"))
            make("नमस्ते", 1f) // first use loads the voice
            val (secs, first, total) = make(sentence, 1f)
            Ci.log("natural-voice", "Sentence: %.2f s of speech, first audio after %d ms, all made in %d ms".format(secs, first, total))
            assertTrue("The speech is too short: $secs s", secs > 2.0)
            val slow = make(sentence, 0.7f).first
            val quick = make(sentence, 1.4f).first
            Ci.log("natural-voice", "Speed 0.7: %.2f s, 1.4: %.2f s".format(slow, quick))
            assertTrue("The natural voice ignores speed", slow > quick * 1.3)
        } finally {
            runBlocking(kotlinx.coroutines.Dispatchers.Main) { tts.shutdown() }
        }
        // An audio file through the app's own path (speak like a person, natural voice).
        val wav = runBlocking { com.gravity.ide.files.FileMaker.audio(ctx, "natural", "Arre, ye toh bahut achha hai! Kya aap taiyaar hain?", java.util.Locale.forLanguageTag("hi-IN")) }
        val (samples, sr) = com.gravity.ide.core.WavJoin.pcmMono16(wav.readBytes())
        Ci.log("natural-voice", "Audio file: %.2f s at %d Hz".format(samples.size.toDouble() / sr, sr))
        assertTrue(samples.size.toDouble() / sr > 2.0)
        wav.delete()
    }
}

