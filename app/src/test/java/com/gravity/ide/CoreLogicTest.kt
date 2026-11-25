package com.gravity.ide

import com.gravity.ide.core.BackupCrypto
import com.gravity.ide.core.CommandSafety
import com.gravity.ide.core.GgufReader
import com.gravity.ide.core.Glob
import com.gravity.ide.core.Language
import com.gravity.ide.core.ModelPicker
import com.gravity.ide.core.LineDiff
import com.gravity.ide.core.PathGuard
import com.gravity.ide.core.SecretScanner
import com.gravity.ide.core.SseParser
import com.gravity.ide.core.SyntaxHighlighter
import com.gravity.ide.core.TextToolProtocol
import com.gravity.ide.core.TokenType
import com.gravity.ide.core.Utf8Accumulator
import com.gravity.ide.core.ZipSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PathGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun resolvesRelativePathsInsideRoot() {
        val root = tmp.newFolder("proj")
        assertEquals(File(root, "src/Main.kt").canonicalPath, PathGuard.resolveInside(root, "src/Main.kt").path)
        assertEquals(root.canonicalPath, PathGuard.resolveInside(root, ".").path)
    }

    @Test fun rejectsTraversal() {
        val root = tmp.newFolder("proj")
        try {
            PathGuard.resolveInside(root, "../outside.txt")
            fail("expected PathEscapeException")
        } catch (_: PathGuard.PathEscapeException) {
        }
    }

    @Test fun rejectsAbsolutePathOutsideRoot() {
        val root = tmp.newFolder("proj")
        val resolved = PathGuard.resolveInside(root, "/etc/passwd")
        // Absolute paths outside the root are re-rooted, never followed.
        assertTrue(PathGuard.isInside(root, resolved))
    }

    @Test fun validatesNames() {
        assertEquals("a.txt", PathGuard.validateName(" a.txt "))
        for (bad in listOf("", "..", "a/b", "a\\b")) {
            try {
                PathGuard.validateName(bad)
                fail("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

class ZipSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun extractsNormalEntries() {
        val dest = tmp.newFolder("out")
        val r = ZipSafety.extract(ByteArrayInputStream(zip("a.txt" to "A", "dir/b.txt" to "B")), dest)
        assertEquals(2, r.files)
        assertEquals("B", File(dest, "dir/b.txt").readText())
    }

    @Test fun rejectsZipSlip() {
        val dest = tmp.newFolder("out")
        val r = ZipSafety.extract(ByteArrayInputStream(zip("../evil.txt" to "x", "/abs.txt" to "y", "ok.txt" to "z")), dest)
        assertEquals(listOf("../evil.txt", "/abs.txt"), r.rejected)
        assertFalse(File(dest.parentFile, "evil.txt").exists())
        assertTrue(File(dest, "ok.txt").exists())
    }

    @Test fun enforcesSizeLimit() {
        val dest = tmp.newFolder("out")
        try {
            ZipSafety.extract(ByteArrayInputStream(zip("big.txt" to "x".repeat(10_000))), dest, ZipSafety.Limits(maxTotalBytes = 1000))
            fail("limit not enforced")
        } catch (_: ZipSafety.ZipLimitException) {
        }
    }

    @Test fun roundTripExcludesBuildOutputs() {
        val src = tmp.newFolder("src")
        File(src, "app/src").mkdirs(); File(src, "app/src/Main.kt").writeText("fun main() {}")
        File(src, "app/build").mkdirs(); File(src, "app/build/out.bin").writeText("junk")
        val bytes = ByteArrayOutputStream().also { ZipSafety.zipDirectory(src, it, ZipSafety.DEFAULT_EXCLUDES) }.toByteArray()
        val dest = tmp.newFolder("dest")
        ZipSafety.extract(ByteArrayInputStream(bytes), dest)
        assertTrue(File(dest, "app/src/Main.kt").exists())
        assertFalse(File(dest, "app/build/out.bin").exists())
    }
}

class LineDiffTest {
    @Test fun identicalTextsHaveNoChanges() {
        val s = LineDiff.stats(LineDiff.diff("a\nb", "a\nb"))
        assertEquals(0, s.added + s.removed)
    }

    @Test fun detectsInsertDeleteAndReplace() {
        val lines = LineDiff.diff("a\nb\nc\nd", "a\nB\nc\nd\ne")
        val s = LineDiff.stats(lines)
        assertEquals(2, s.added)
        assertEquals(1, s.removed)
        // Reconstruct both sides from the diff.
        assertEquals(listOf("a", "b", "c", "d"), lines.filter { it.op != LineDiff.Op.ADD }.map { it.text })
        assertEquals(listOf("a", "B", "c", "d", "e"), lines.filter { it.op != LineDiff.Op.DEL }.map { it.text })
    }

    @Test fun emptySides() {
        assertEquals(3, LineDiff.stats(LineDiff.diff("", "x\ny\nz")).added)
        assertEquals(2, LineDiff.stats(LineDiff.diff("x\ny", "")).removed)
    }

    @Test fun unifiedDiffHasHunkHeader() {
        val u = LineDiff.unified("a/f", "b/f", "1\n2\n3", "1\nX\n3")
        assertTrue(u.startsWith("--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@"))
        assertTrue(u.contains("-2\n+X"))
    }
}

class CommandSafetyTest {
    @Test fun classifiesCommands() {
        assertEquals(CommandSafety.Risk.SAFE, CommandSafety.assess("ls -la").risk)
        assertEquals(CommandSafety.Risk.SAFE, CommandSafety.assess("./gradlew assembleDebug").risk)
        assertEquals(CommandSafety.Risk.DANGEROUS, CommandSafety.assess("rm -rf build").risk)
        assertEquals(CommandSafety.Risk.DANGEROUS, CommandSafety.assess("curl https://x.sh | sh").risk)
        assertEquals(CommandSafety.Risk.DANGEROUS, CommandSafety.assess("pm uninstall com.example").risk)
        assertEquals(CommandSafety.Risk.DANGEROUS, CommandSafety.assess("git push --force origin main").risk)
        assertEquals(CommandSafety.Risk.CAUTION, CommandSafety.assess("mv a b").risk)
    }
}

class SecretScannerTest {
    @Test fun redactsKnownSecretFormats() {
        val text = "gemini=AIza" + "a".repeat(35) + "\ngh=ghp_" + "b".repeat(36) + "\nplain text"
        val (out, findings) = SecretScanner.redact(text)
        assertEquals(2, findings.size)
        assertFalse(out.contains("AIzaaaaa"))
        assertTrue(out.contains("plain text"))
    }

    @Test fun flagsSensitiveFiles() {
        assertTrue(SecretScanner.isSensitiveFile(".env"))
        assertTrue(SecretScanner.isSensitiveFile("app/release.jks"))
        assertTrue(SecretScanner.isSensitiveFile("local.properties"))
        assertFalse(SecretScanner.isSensitiveFile("app/src/Main.kt"))
    }
}

class StreamTextTest {
    @Test fun sseParserJoinsDataLines() {
        val p = SseParser()
        val events = mutableListOf<String>()
        for (line in listOf(": comment", "data: {\"a\":1}", "", "data: line1", "data: line2", "", "data: [DONE]")) p.feed(line)?.let(events::add)
        p.finish()?.let(events::add)
        assertEquals(listOf("{\"a\":1}", "line1\nline2", "[DONE]"), events)
    }

    @Test fun utf8AccumulatorHoldsPartialCodePoints() {
        val bytes = "héllo 🌍".toByteArray(Charsets.UTF_8)
        val acc = Utf8Accumulator()
        val out = StringBuilder()
        for (b in bytes) out.append(acc.append(byteArrayOf(b)))
        out.append(acc.flush())
        assertEquals("héllo 🌍", out.toString())
    }
}

class GgufReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gguf(): ByteArray {
        val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) { val b = s.toByteArray(); buf.putLong(b.size.toLong()); buf.put(b) }
        buf.putInt(0x46554747); buf.putInt(3); buf.putLong(0); buf.putLong(5)
        str("general.architecture"); buf.putInt(8); str("qwen2")
        str("general.name"); buf.putInt(8); str("Qwen2.5 Coder 1.5B")
        str("general.file_type"); buf.putInt(4); buf.putInt(15)
        str("qwen2.context_length"); buf.putInt(4); buf.putInt(32768)
        // A string array (like tokenizer vocab) that must be skipped.
        str("tokenizer.ggml.tokens"); buf.putInt(9); buf.putInt(8); buf.putLong(3); str("a"); str("bb"); str("ccc")
        return buf.array().copyOf(buf.position())
    }

    @Test fun parsesHeaderAndSkipsArrays() {
        val f = tmp.newFile("m.gguf").apply { writeBytes(gguf()) }
        val info = GgufReader.read(f)
        assertEquals("qwen2", info.architecture)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(32768L, info.contextLength)
        assertEquals("Qwen2.5 Coder 1.5B", info.name)
    }

    @Test fun explainsBigEndianFiles() {
        // Magic is written as bytes "GGUF"; a big-endian file stores the version as 00 00 00 03.
        val bytes = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0, 0, 0, 3) + ByteArray(32)
        val f = tmp.newFile("be.gguf").apply { writeBytes(bytes) }
        val e = runCatching { GgufReader.read(f) }.exceptionOrNull()
        assertTrue("expected a big-endian explanation, got $e", e?.message?.contains("Big-endian") == true)
    }

    @Test fun rejectsNonGguf() {
        val f = tmp.newFile("x.bin").apply { writeBytes("not a model".toByteArray()) }
        try {
            GgufReader.read(f)
            fail("accepted a non-GGUF file")
        } catch (_: Exception) {
        }
    }
}

class BackupCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripAndWrongPassword() {
        val plain = tmp.newFile("p").apply { writeBytes(ByteArray(150_000) { (it * 7).toByte() }) }
        val enc = tmp.newFile("e")
        BackupCrypto.encryptFile(plain, enc, "secret".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(enc))
        val out = ByteArrayOutputStream()
        BackupCrypto.decrypt(enc, out, "secret".toCharArray())
        assertTrue(out.toByteArray().contentEquals(plain.readBytes()))
        try {
            BackupCrypto.decrypt(enc, ByteArrayOutputStream(), "wrong".toCharArray())
            fail("wrong password accepted")
        } catch (_: BackupCrypto.BadPasswordOrCorruptException) {
        }
    }

    @Test fun detectsTampering() {
        val plain = tmp.newFile("p").apply { writeText("important data") }
        val enc = tmp.newFile("e")
        BackupCrypto.encryptFile(plain, enc, "pw".toCharArray())
        val bytes = enc.readBytes()
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 1).toByte()
        enc.writeBytes(bytes)
        try {
            BackupCrypto.decrypt(enc, ByteArrayOutputStream(), "pw".toCharArray())
            fail("tampered file accepted")
        } catch (_: BackupCrypto.BadPasswordOrCorruptException) {
        }
    }
}

class GlobTest {
    @Test fun matchesArtifactPatterns() {
        val r = Glob.toRegex("app/build/outputs/apk/debug/*.apk")
        assertTrue(r.matches("app/build/outputs/apk/debug/app-debug.apk"))
        assertFalse(r.matches("app/build/outputs/apk/release/app-release.apk"))
        assertTrue(Glob.toRegex("**/*.apk").matches("x/y/z.apk"))
    }
}

class TextToolProtocolTest {
    @Test fun parsesToolBlocks() {
        val text = "I'll read it.\n```tool\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.kt\"}}\n```\nand\n```tool\nnot json\n```"
        val calls = TextToolProtocol.parse(text)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("a.kt"))
        assertEquals("I'll read it.\n\nand", TextToolProtocol.stripCalls(text))
    }
}

class SyntaxHighlighterTest {
    @Test fun tokenizesKotlin() {
        val src = "// c\nval s = \"x\" + 42 // tail\n@Composable fun A() {}"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        fun typeAt(i: Int) = tokens.firstOrNull { i >= it.start && i < it.end }?.type
        assertEquals(TokenType.COMMENT, typeAt(0))
        assertEquals(TokenType.KEYWORD, typeAt(src.indexOf("val")))
        assertEquals(TokenType.STRING, typeAt(src.indexOf("\"x\"")))
        assertEquals(TokenType.NUMBER, typeAt(src.indexOf("42")))
        assertEquals(TokenType.ANNOTATION, typeAt(src.indexOf("@Composable")))
    }

    @Test fun markdownHeadingOnlyAtLineStart() {
        val src = "# Title\ntext # not heading"
        val tokens = SyntaxHighlighter.tokenize(src, Language.MARKDOWN)
        assertTrue(tokens.any { it.type == TokenType.HEADING && it.start == 0 })
        assertFalse(tokens.any { it.type == TokenType.HEADING && it.start > 0 })
    }

    @Test fun matchesBrackets() {
        val src = "f(a, \")\", g(b))"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        assertEquals(src.length - 1, SyntaxHighlighter.matchingBracket(src, 1, tokens))
    }

    @Test fun detectsLanguage() {
        assertEquals(Language.KOTLIN, Language.forFileName("build.gradle.kts"))
        assertEquals(Language.PYTHON, Language.forFileName("main.py"))
        assertEquals(Language.PLAIN, Language.forFileName("LICENSE"))
        assertNotNull(Language.forFileName("x.tsx"))
    }
}

class ModelPickerTest {
    @Test fun recognisesUsedUpFreeQuotaAndPlansFallbacks() {
        val daily = com.gravity.ide.ai.LlmException(
            "Rate limited — retry later: You exceeded your current quota. * Quota exceeded for metric: generate_content_free_tier_requests, limit: 20, " +
                "model: gemini-3.8-flash, description: Generate content requests per day limit exceeded on Free Tier. Please retry in 25m25.077803779s.",
            httpCode = 429,
        )
        assertTrue(ModelPicker.isQuotaExhausted(daily))
        assertFalse(ModelPicker.isModelUnavailable(daily))
        assertEquals("26 min", ModelPicker.retryHint(daily))
        assertFalse(ModelPicker.isQuotaExhausted(com.gravity.ide.ai.LlmException("Bad request: invalid argument", httpCode = 400)))
        val busy = com.gravity.ide.ai.LlmException("HTTP 503: This model is currently experiencing high demand. Spikes in demand are usually temporary.", httpCode = 503)
        assertTrue(ModelPicker.isOverloaded(busy))
        assertTrue(ModelPicker.isTemporarilyUnavailable(busy))
        assertTrue(ModelPicker.isTemporarilyUnavailable(daily))
        assertFalse(ModelPicker.isTemporarilyUnavailable(com.gravity.ide.ai.LlmException("Authentication failed — check the API key", httpCode = 401)))
        val listed = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-pro", "gemini-3.1-pro-preview", "gemini-3.1-flash-image", "text-embedding-004")
        assertEquals(listOf("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-pro"), ModelPicker.quotaFallbacks(listed, "gemini-3.8-flash", emptySet()))
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.5-pro"), ModelPicker.quotaFallbacks(listed, "gemini-3.8-flash", setOf("gemini-3.5-flash")))
    }

    @Test fun ranksModelsForThePicker() {
        val listed = listOf("gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-pro-preview", "text-embedding-004", "gemini-3.5-pro")
        val ranked = ModelPicker.rankForChat(listed.map { "models/$it" })
        assertEquals("gemini-3.8-flash", ranked.first())
        assertEquals(listed.size, ranked.size)
        assertTrue(ranked.indexOf("gemini-3.5-pro") < ranked.indexOf("gemini-2.5-flash"))
        assertTrue(ranked.indexOf("gemini-2.5-flash") < ranked.indexOf("gemini-3.1-pro-preview"))
        assertTrue(ranked.indexOf("gemini-3.5-pro") < ranked.indexOf("text-embedding-004"))
        assertFalse(ModelPicker.isGeneral("gemini-3.5-flash-lite"))
        assertTrue(ModelPicker.isGeneral("gemini-3.5-pro"))
    }

    @Test fun picksNewestStableFlash() {
        val listed = listOf(
            "gemini-2.5-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.7-flash",
            "gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-3.1-flash-image", "gemini-3.1-flash-tts-preview", "gemini-flash-latest",
        )
        assertEquals("gemini-3.8-flash", com.gravity.ide.core.ModelPicker.bestGemini(listed))
    }

    @Test fun versionsCompareNumerically() {
        assertEquals("gemini-3.10-flash", com.gravity.ide.core.ModelPicker.bestGemini(listOf("gemini-3.9-flash", "gemini-3.10-flash", "gemini-3-flash")))
    }

    @Test fun fallsBackToAliasThenGeneralModels() {
        assertEquals("gemini-flash-latest", com.gravity.ide.core.ModelPicker.bestGemini(listOf("gemini-flash-latest", "gemini-3.1-pro-preview")))
        assertEquals("gemini-4-pro", com.gravity.ide.core.ModelPicker.bestGemini(listOf("models/gemini-4-pro", "gemini-3.1-flash-image")))
        assertNull(com.gravity.ide.core.ModelPicker.bestGemini(emptyList()))
    }

    @Test fun recognisesRetiredModelErrors() {
        val retired = com.gravity.ide.ai.LlmException(
            "Model or endpoint not found: This model models/gemini-2.5-flash is no longer available to new users.", httpCode = 404,
        )
        assertTrue(com.gravity.ide.core.ModelPicker.isModelUnavailable(retired))
        assertFalse(com.gravity.ide.core.ModelPicker.isModelUnavailable(com.gravity.ide.ai.LlmException("Rate limited", httpCode = 429)))
        assertFalse(com.gravity.ide.core.ModelPicker.isModelUnavailable(com.gravity.ide.ai.LlmException("Authentication failed", httpCode = 401)))
    }
}

class ContextPlannerTest {
    private val mb = 1024L * 1024

    @Test fun qwen15bPerTokenEstimate() {
        // Qwen2.5 1.5B: 28 layers, 1536 dims, 12 heads, 2 KV heads (head size 128).
        val perToken = com.gravity.ide.core.ContextPlanner.bytesPerToken(28, 1536, 12, 2)
        assertEquals(28L * 2 * 256 * 2 + 512L * 12 * 4, perToken)
    }

    @Test fun growsOnlyWhenMemoryAllows() {
        val perToken = com.gravity.ide.core.ContextPlanner.bytesPerToken(28, 1536, 12, 2) // ≈ 53 KB
        assertEquals(8192, com.gravity.ide.core.ContextPlanner.choose(4096, 8192, 32768, perToken, 3000 * mb))
        assertEquals(6144, com.gravity.ide.core.ContextPlanner.choose(4096, 8192, 32768, perToken, 900 * mb))
        assertEquals(4096, com.gravity.ide.core.ContextPlanner.choose(4096, 8192, 32768, perToken, 300 * mb))
        assertEquals(4096, com.gravity.ide.core.ContextPlanner.choose(4096, 8192, 2048, perToken, 3000 * mb))
        assertEquals(8192, com.gravity.ide.core.ContextPlanner.choose(8192, 4096, 32768, perToken, 3000 * mb))
    }

    @Test fun theModelsOwnFileIsNotFreeMemory() {
        // Android said 3 GB available, but 2.5 GB of it is the running Gemma 3 4B file: 0.5 GB is spare.
        assertEquals(512 * mb, com.gravity.ide.core.ContextPlanner.reallyFree(3072 * mb, 2560 * mb))
        assertEquals(0L, com.gravity.ide.core.ContextPlanner.reallyFree(1024 * mb, 4700 * mb))
        val perToken = com.gravity.ide.core.ContextPlanner.bytesPerToken(28, 1536, 12, 2)
        // With the model counted, a bigger context is no longer chosen on such a phone.
        assertEquals(4096, com.gravity.ide.core.ContextPlanner.choose(4096, 8192, 32768, perToken, com.gravity.ide.core.ContextPlanner.reallyFree(1200 * mb, 1000 * mb)))
    }

    @Test fun aDrawingFitsTheContextTheModelHas() {
        assertEquals(2500, com.gravity.ide.core.MakeFiles.drawTokens(com.gravity.ide.core.MakeFiles.Kind.IMAGE, 4096))
        assertEquals(1365, com.gravity.ide.core.MakeFiles.drawTokens(com.gravity.ide.core.MakeFiles.Kind.IMAGE, 2048))
        assertEquals(2730, com.gravity.ide.core.MakeFiles.drawTokens(com.gravity.ide.core.MakeFiles.Kind.VIDEO, 4096))
        assertEquals(4000, com.gravity.ide.core.MakeFiles.drawTokens(com.gravity.ide.core.MakeFiles.Kind.VIDEO, 8192))
    }
}
