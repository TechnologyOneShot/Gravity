package com.gravity.ide.testing

import android.content.Context
import com.gravity.ide.ai.ImagePart
import com.gravity.ide.ai.LlmMessage
import com.gravity.ide.ai.LlmRequest
import com.gravity.ide.ai.ProviderRegistry
import com.gravity.ide.ai.Role
import com.gravity.ide.ai.complete
import com.gravity.ide.ai.local.LocalModelHost
import com.gravity.ide.capture.ScreenCaptureController
import com.gravity.ide.capture.ScreenshotStore
import com.gravity.ide.core.BackupCrypto
import com.gravity.ide.core.GgufReader
import com.gravity.ide.core.LineDiff
import com.gravity.ide.core.SecretScanner
import com.gravity.ide.core.ZipSafety
import com.gravity.ide.data.EventCategory
import com.gravity.ide.data.EventLevel
import com.gravity.ide.data.EventLog
import com.gravity.ide.data.db.EventEntity
import com.gravity.ide.data.db.GravityDatabase
import com.gravity.ide.data.db.ScreenshotEntity
import com.gravity.ide.data.db.TestResultEntity
import com.gravity.ide.data.settings.SettingsRepository
import com.gravity.ide.device.AppInstaller
import com.gravity.ide.device.LogcatReader
import com.gravity.ide.network.NetworkMonitor
import com.gravity.ide.security.SecretStore
import com.gravity.ide.terminal.TerminalManager
import com.gravity.ide.workspace.CheckpointManager
import com.gravity.ide.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs real checks on this device and records Test Name / Expected / Actual / Outcome /
 * Details for each (acceptance-test evidence). Nothing here is mocked.
 */
class SelfTestRunner(
    private val context: Context,
    private val db: GravityDatabase,
    private val secrets: SecretStore,
    private val workspace: WorkspaceManager,
    private val checkpoints: CheckpointManager,
    private val terminals: TerminalManager,
    private val network: NetworkMonitor,
    private val registry: ProviderRegistry,
    private val localHost: LocalModelHost,
    private val settings: SettingsRepository,
    private val events: EventLog,
) {
    private class Skip(message: String) : Exception(message)

    private suspend fun record(name: String, expected: String, block: suspend () -> String): TestResultEntity {
        val start = System.currentTimeMillis()
        val entity = try {
            val actual = block()
            TestResultEntity(suite = SUITE, name = name, expected = expected, actual = actual, outcome = "PASS", durationMs = System.currentTimeMillis() - start, createdAt = System.currentTimeMillis())
        } catch (s: Skip) {
            TestResultEntity(suite = SUITE, name = name, expected = expected, actual = s.message ?: "skipped", outcome = "SKIPPED", durationMs = System.currentTimeMillis() - start, createdAt = System.currentTimeMillis())
        } catch (e: Throwable) {
            TestResultEntity(suite = SUITE, name = name, expected = expected, actual = e.message ?: e.javaClass.simpleName, outcome = "FAIL", details = e.stackTraceToString().take(4000), durationMs = System.currentTimeMillis() - start, createdAt = System.currentTimeMillis())
        }
        val id = db.testResults().insert(entity)
        return entity.copy(id = id)
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    suspend fun runAll(): List<TestResultEntity> = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "selftest-${System.currentTimeMillis()}").apply { mkdirs() }
        val results = mutableListOf<TestResultEntity>()
        try {
            results += record("Database write/read", "Inserted event is read back") {
                val marker = "selftest-${System.nanoTime()}"
                db.events().insert(EventEntity(category = EventCategory.SYSTEM.name, title = marker, createdAt = System.currentTimeMillis()))
                val found = db.events().recent(50).any { it.title == marker }
                check(found) { "Event not found after insert" }
                "Room round-trip OK"
            }
            results += record("Keystore credential encryption", "Encrypt/decrypt round-trip with AndroidKeyStore key") {
                check(secrets.selfTest()) { "Round-trip mismatch" }
                "AES-256-GCM key in AndroidKeyStore OK"
            }
            results += record("File create / rename / delete / restore", "File survives trash round-trip") {
                val dir = File(scratch, "ws").apply { mkdirs() }
                val f = workspace.createFile(dir, "a.txt", "hello")
                val renamed = workspace.rename(f, "b.txt")
                val trashId = workspace.delete(renamed, null)
                check(!renamed.exists()) { "File still present after delete" }
                val restored = workspace.restoreFromTrash(trashId)
                check(restored.readText() == "hello") { "Restored content differs" }
                "create→rename→trash→restore OK (${restored.name})"
            }
            results += record("Checkpoint rollback", "Restoring a checkpoint brings back the previous content") {
                val projectId = "selftest"
                val root = File(scratch, "proj").apply { mkdirs() }
                val f = File(root, "Main.kt").apply { writeText("v1") }
                val version = checkpoints.snapshot(projectId, root, f, "selftest")
                f.writeText("v2")
                checkpoints.restore(version, root, projectId)
                check(f.readText() == "v1") { "Expected v1, found ${f.readText()}" }
                "v2 rolled back to v1"
            }
            results += record("Terminal exit codes", "echo returns 0, `exit 3` returns 3") {
                val shell = terminals.ephemeral(scratch, "selftest")
                try {
                    val ok = shell.run("echo gravity-selftest", 15_000)
                    check(ok.exitCode == 0 && ok.output.contains("gravity-selftest")) { "echo: exit=${ok.exitCode} out=${ok.output}" }
                    val bad = shell.run("(exit 3)", 15_000)
                    check(bad.exitCode == 3) { "Expected exit 3, got ${bad.exitCode}" }
                    "exit 0 and exit 3 reported correctly (cwd ${shell.cwd.value})"
                } finally {
                    shell.close()
                }
            }
            results += record("ZIP export/import + zip-slip protection", "Round-trip preserves files; ../ entries are rejected") {
                val src = File(scratch, "zipsrc/sub").apply { mkdirs() }
                File(src, "x.txt").writeText("zip-content")
                val bytes = ByteArrayOutputStream().also { ZipSafety.zipDirectory(src.parentFile!!, it) }.toByteArray()
                val dest = File(scratch, "zipdest")
                ZipSafety.extract(ByteArrayInputStream(bytes), dest)
                check(File(dest, "sub/x.txt").readText() == "zip-content") { "Content mismatch" }
                val evil = ByteArrayOutputStream().also { bos ->
                    ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("../../evil.txt")); z.write("x".toByteArray()); z.closeEntry() }
                }.toByteArray()
                val r = ZipSafety.extract(ByteArrayInputStream(evil), File(scratch, "slip"))
                check(r.rejected.isNotEmpty() && !File(scratch, "evil.txt").exists()) { "Traversal entry was not rejected" }
                "round-trip OK; rejected ${r.rejected}"
            }
            results += record("GGUF header parsing", "Synthetic GGUF v3 header parses to llama / Q4_K_M") {
                val f = File(scratch, "tiny.gguf").apply { writeBytes(syntheticGguf()) }
                val info = GgufReader.read(f)
                check(info.architecture == "llama" && info.quantization == "Q4_K_M") { "Parsed $info" }
                "arch=${info.architecture} quant=${info.quantization} ctx=${info.contextLength}"
            }
            results += record("Backup encryption", "Encrypted backup decrypts with the right password and fails with a wrong one") {
                val plain = File(scratch, "plain.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
                val enc = File(scratch, "enc.bin")
                BackupCrypto.encryptFile(plain, enc, "correct horse".toCharArray())
                val out = ByteArrayOutputStream()
                BackupCrypto.decrypt(enc, out, "correct horse".toCharArray())
                check(out.toByteArray().contentEquals(plain.readBytes())) { "Decrypted bytes differ" }
                val wrong = runCatching { BackupCrypto.decrypt(enc, ByteArrayOutputStream(), "wrong".toCharArray()) }
                check(wrong.isFailure) { "Wrong password was accepted" }
                "AES-CTR + HMAC verified; wrong password rejected"
            }
            results += record("Diff engine", "Line diff reports one change") {
                val d = LineDiff.stats(LineDiff.diff("a\nb\nc", "a\nB\nc"))
                check(d.added == 1 && d.removed == 1) { "Got $d" }
                "+${d.added} −${d.removed}"
            }
            results += record("Secret redaction", "Google API key pattern is redacted") {
                val (out, findings) = SecretScanner.redact("key=AIza" + "X".repeat(35))
                check(findings.isNotEmpty() && !out.contains("AIzaXXXX")) { "Not redacted: $out" }
                "redacted ${findings.size} finding(s)"
            }
            results += record("Network state", "Reports real connectivity") {
                val st = network.state.value
                if (!st.online) throw Skip("Device is offline (${st.transport}) — online features not tested")
                "online via ${st.transport}${if (st.metered) " (metered)" else ""}"
            }
            results += record("Cloud provider connection", "Default provider lists models with the stored key") {
                val s = settings.current()
                val provider = db.providers().all().firstOrNull { it.id == s.defaultProviderId } ?: db.providers().all().firstOrNull { it.enabled && it.kind != "LOCAL" }
                    ?: throw Skip("No cloud provider configured")
                if (!network.isOnline) throw Skip("Offline")
                val models = registry.test(provider).getOrThrow()
                "${provider.name}: ${models.size} models"
            }
            results += record("Local model inference", "Loaded local model generates tokens") {
                if (!localHost.runtimeAvailable) throw Skip(localHost.runtimeError ?: "runtime unavailable")
                val loaded = localHost.loadedOrNull() ?: throw Skip("No local model loaded")
                var text = ""
                val r = registry.local.complete(LlmRequest(loaded.name, listOf(LlmMessage(Role.USER, "Reply with the single word: ready")), maxOutputTokens = 16)) { text = it }
                check(r.outputTokens > 0) { "No tokens generated" }
                "${r.outputTokens} tokens: ${text.take(60)}"
            }
        } finally {
            scratch.deleteRecursively()
        }
        val failed = results.count { it.outcome == "FAIL" }
        events.log(EventCategory.TEST, "Self-test: ${results.count { it.outcome == "PASS" }} passed, $failed failed, ${results.count { it.outcome == "SKIPPED" }} skipped", level = if (failed > 0) EventLevel.WARN else EventLevel.INFO)
        results
    }

    private fun syntheticGguf(): ByteArray {
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) { val b = s.toByteArray(); buf.putLong(b.size.toLong()); buf.put(b) }
        buf.putInt(0x46554747) // GGUF
        buf.putInt(3)
        buf.putLong(0) // tensors
        buf.putLong(3) // kv count
        str("general.architecture"); buf.putInt(8); str("llama")
        str("general.file_type"); buf.putInt(4); buf.putInt(15)
        str("llama.context_length"); buf.putInt(4); buf.putInt(4096)
        return buf.array().copyOf(buf.position())
    }

    companion object {
        const val SUITE = "self-test"
    }
}

/**
 * Same-device app test: launch the installed target, wait, capture a real screenshot (when
 * a capture session is active), read the real logcat and detect crashes. Optional AI visual
 * analysis sends the screenshot to a vision-capable provider.
 */
class AppTestRunner(
    private val db: GravityDatabase,
    private val installer: AppInstaller,
    private val logcat: LogcatReader,
    private val screenshots: ScreenshotStore,
    private val registry: ProviderRegistry,
    private val events: EventLog,
) {
    data class AppTestResult(val result: TestResultEntity, val screenshot: ScreenshotEntity?, val logExcerpt: String)

    suspend fun run(packageName: String, waitSeconds: Int, capture: Boolean): AppTestResult {
        val suite = "device:$packageName"
        val start = System.currentTimeMillis()
        if (!installer.isInstalled(packageName)) {
            val r = TestResultEntity(suite = suite, name = "Launch $packageName", expected = "App installed and launches", actual = "Not installed", outcome = "FAIL", createdAt = start)
            return AppTestResult(r.copy(id = db.testResults().insert(r)), null, "")
        }
        val launched = withContext(Dispatchers.Main) { installer.launch(packageName) }
        if (!launched) {
            val r = TestResultEntity(suite = suite, name = "Launch $packageName", expected = "Launch intent exists", actual = "No launcher activity", outcome = "FAIL", createdAt = start)
            return AppTestResult(r.copy(id = db.testResults().insert(r)), null, "")
        }
        delay(waitSeconds.coerceIn(1, 120) * 1000L)
        var shot: ScreenshotEntity? = null
        var captureNote = "no capture session"
        if (capture && ScreenCaptureController.isActive) {
            shot = try {
                screenshots.captureNow("Test: $packageName", packageName).also { captureNote = "screenshot ${it.width}×${it.height}" }
            } catch (e: Exception) {
                captureNote = "capture failed: ${e.message}"
                null
            }
        }
        val lines = logcat.dump(since = start - 1000)
        val crashes = logcat.findCrashes(lines, packageName)
        val excerpt = (if (crashes.isNotEmpty()) crashes.first().stackTrace else lines.takeLast(80).joinToString("\n") { it.raw }).take(8000)
        val passed = crashes.isEmpty()
        val r = TestResultEntity(
            suite = suite,
            name = "Launch + ${waitSeconds}s runtime check",
            expected = "No FATAL EXCEPTION for $packageName in logcat",
            actual = if (passed) "No crash detected (${lines.size} log lines read; $captureNote)" else "Crash: ${crashes.first().exception}",
            outcome = if (passed) "PASS" else "FAIL",
            details = excerpt,
            evidencePath = shot?.filePath,
            durationMs = System.currentTimeMillis() - start,
            createdAt = System.currentTimeMillis(),
        )
        val id = db.testResults().insert(r)
        shot?.let { db.screenshots().update(it.copy(testRunId = id)) }
        events.log(EventCategory.TEST, "App test ${r.outcome}: $packageName", r.actual, if (passed) EventLevel.INFO else EventLevel.ERROR)
        return AppTestResult(r.copy(id = id), shot, excerpt)
    }

    /** Sends a real screenshot to a vision model and stores its findings. */
    suspend fun analyze(screenshot: ScreenshotEntity, providerId: String?, expectation: String): String {
        val resolved = registry.resolve(providerId, null)
        val image = withContext(Dispatchers.IO) { screenshots.encodeForVision(screenshot.filePath) }
        val prompt = """
            You are a mobile UI QA engineer. Inspect this real screenshot of an Android app${screenshot.packageName?.let { " ($it)" } ?: ""}.
            ${if (expectation.isNotBlank()) "Expected: $expectation\n" else ""}Report concrete problems only: clipped/overflowing text, overlapping or missing elements,
            inconsistent spacing or alignment, poor contrast, controls too small to tap, error messages, blank screens.
            For each: what, where, severity (high/medium/low) and a suggested fix. If nothing is wrong, say so.
        """.trimIndent()
        val result = resolved.provider.complete(
            LlmRequest(resolved.model, listOf(LlmMessage(Role.USER, prompt, images = listOf(ImagePart("image/jpeg", image)))))
        )
        registry.recordUsage(resolved.entity, result.inputTokens, result.outputTokens)
        val analysis = result.text.trim()
        db.screenshots().update(screenshot.copy(analysis = analysis))
        events.log(EventCategory.TEST, "Visual analysis complete", analysis.take(2000), model = resolved.model)
        return analysis
    }
}
