package com.gravity.ide

import com.gravity.ide.core.TermuxScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Executes the real Termux build script with bash against a fake Gradle project. */
class TermuxScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bashAvailable() = try {
        ProcessBuilder("bash", "-c", "exit 0").start().waitFor(5, TimeUnit.SECONDS)
    } catch (_: Exception) {
        false
    }

    private fun run(script: String, home: File): Pair<Int, String> {
        val pb = ProcessBuilder("bash", "-c", script).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment().remove("ANDROID_HOME")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    private fun fakeProject(share: File, exitCode: Int): File {
        val dir = File(share, "My App-1").apply { mkdirs() }
        File(dir, "gradlew").writeText(
            """
            #!/bin/sh
            echo "fake gradle ${'$'}@"
            mkdir -p app/build/outputs/apk/debug && echo APK > app/build/outputs/apk/debug/app-debug.apk
            exit $exitCode
            """.trimIndent()
        )
        return dir
    }

    @Test fun successfulBuildExportsArtifact() {
        assumeTrue(bashAvailable())
        val share = tmp.newFolder("share")
        val dir = fakeProject(share, 0)
        val script = TermuxScript.build(share.absolutePath, dir.name, "./gradlew assembleDebug --offline", "app/build/outputs/apk/debug/*.apk", clean = true)
        val (code, out) = run(script, tmp.newFolder("home"))
        assertEquals(out, 0, code)
        assertTrue(out.contains("exported 1 artifact"))
        assertTrue(File(dir, ".gravity-out/app-debug.apk").isFile)
    }

    @Test fun failingBuildPropagatesExitCode() {
        assumeTrue(bashAvailable())
        val share = tmp.newFolder("share")
        val dir = fakeProject(share, 3)
        val script = TermuxScript.build(share.absolutePath, dir.name, "./gradlew assembleDebug", "app/build/outputs/apk/debug/*.apk", clean = false)
        val (code, out) = run(script, tmp.newFolder("home"))
        assertEquals(out, 3, code)
        assertTrue(out.contains("build exit code 3"))
    }

    @Test fun quotesPathsWithSpacesAndQuotes() {
        assertEquals("'it'\\''s'", TermuxScript.quote("it's"))
    }
}
